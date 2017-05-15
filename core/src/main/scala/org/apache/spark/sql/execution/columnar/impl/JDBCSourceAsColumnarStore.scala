/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution.columnar.impl

import java.sql.{Connection, ResultSet, Statement}
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

import scala.annotation.meta.param
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.gemstone.gemfire.internal.cache.{TXStateProxy, TXManagerImpl, GemFireCacheImpl, AbstractRegion, LocalRegion, PartitionedRegion}
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils
import com.pivotal.gemfirexd.internal.engine.{GfxdConstants, Misc}
import io.snappydata.impl.SparkShellRDDHelper
import io.snappydata.thrift.internal.ClientBlob

import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.ConnectionPropertiesSerializer
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.ParamLiteral
import org.apache.spark.sql.collection._
import org.apache.spark.sql.execution.columnar._
import org.apache.spark.sql.execution.row.{ResultSetTraversal, RowFormatScanRDD, RowDMLExec}
import org.apache.spark.sql.execution.{BufferedRowIterator, ConnectionPool, RDDKryo, WholeStageCodegenExec}
import org.apache.spark.sql.hive.ConnectorCatalog
import org.apache.spark.sql.sources.{ConnectionProperties, Filter, JdbcExtendedUtils}
import org.apache.spark.sql.store.{CodeGeneration, StoreUtils}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{SnappyContext, SnappySession, SparkSession, ThinClientConnectorMode}
import org.apache.spark.{Partition, TaskContext}

/**
 * Column Store implementation for GemFireXD.
 */
class JDBCSourceAsColumnarStore(override val connProperties: ConnectionProperties,
    numPartitions: Int, val tableName: String, val schema: StructType)
    extends ExternalStore {

  self =>
  @transient
  protected lazy val rand = new Random

  private lazy val connectionType = ExternalStoreUtils.getConnectionType(
    connProperties.dialect)

  override def storeColumnBatch(tableName: String, batch: ColumnBatch,
      partitionId: Int, batchId: Option[String], maxDeltaRows: Int): Unit = {
    // noinspection RedundantDefaultArgument
    tryExecute(tableName, doInsert(tableName, batch, batchId,
      getPartitionID(tableName, partitionId), maxDeltaRows),
      closeOnSuccess = true, onExecutor = true)
  }

  /**
   * Insert the base entry and n column entries in Snappy. Insert the base entry
   * in the end to ensure that the partial inserts of a cached batch are ignored
   * during iteration. We are not cleaning up the partial inserts of cached
   * batches for now.
   */
  protected def doGFXDInsert(tableName: String, batch: ColumnBatch,
      batchId: Option[String], partitionId: Int,
      maxDeltaRows: Int): (Connection => Any) = {
    {
      (connection: Connection) => {
        val rowInsertStr = getRowInsertStr(tableName, batch.buffers.length)
        val stmt = connection.prepareStatement(rowInsertStr)
        var columnIndex = 1
        val uuid = batchId.getOrElse(UUID.randomUUID().toString)
        var blobs: Array[ClientBlob] = null
        try {
          // add the columns
          blobs = batch.buffers.map(buffer => {
            stmt.setString(1, uuid)
            stmt.setInt(2, partitionId)
            stmt.setInt(3, columnIndex)
            val blob = new ClientBlob(buffer)
            stmt.setBlob(4, blob)
            columnIndex += 1
            stmt.addBatch()
            blob
          })
          // add the stat row
          stmt.setString(1, uuid)
          stmt.setInt(2, partitionId)
          stmt.setInt(3, JDBCSourceAsStore.STATROW_COL_INDEX)
          stmt.setBytes(4, batch.statsData.clone)
          stmt.addBatch()

          stmt.executeBatch()
          stmt.close()
        } catch {
          // TODO: test this code
          case e: Exception =>
            val deletestmt = connection.prepareStatement(
              s"delete from $tableName where partitionId = $partitionId " +
                  s" and uuid = ? and columnIndex = ? ")
            // delete the base entry
            try {
              deletestmt.setString(1, uuid)
              deletestmt.setInt(2, -1)
              deletestmt.executeUpdate()
            } catch {
              case _: Exception => // Do nothing
            }

            for (idx <- 1 to batch.buffers.length) {
              try {
                deletestmt.setString(1, uuid)
                deletestmt.setInt(2, idx)
                deletestmt.executeUpdate()
              } catch {
                case _: Exception => // Do nothing
              }
            }
            deletestmt.close()
            throw e;
        } finally {
          // free the blobs
          if (blobs != null) {
            for (blob <- blobs) {
              try {
                blob.free()
              } catch {
                case _: Exception => // ignore
              }
            }
          }
        }
      }
    }
  }

  protected val insertStrings: mutable.HashMap[String, String] =
    new mutable.HashMap[String, String]()

  protected def getRowInsertStr(tableName: String, numOfColumns: Int): String = {
    val istr = insertStrings.getOrElse(tableName, {
      lock(makeInsertStmnt(tableName, numOfColumns))
    })
    istr
  }

  private def makeInsertStmnt(tableName: String, numOfColumns: Int) = {
    if (!insertStrings.contains(tableName)) {
      val s = insertStrings.getOrElse(tableName,
        s"insert into $tableName values(?,?,?,?)")
      insertStrings.put(tableName, s)
    }
    insertStrings(tableName)
  }

  protected val insertStmntLock = new ReentrantLock()

  /** Acquires a read lock on the cache for the duration of `f`. */
  protected[sql] def lock[A](f: => A): A = {
    insertStmntLock.lock()
    try f finally {
      insertStmntLock.unlock()
    }
  }

  override def getConnection(id: String, onExecutor: Boolean): Connection = {
    val connProps = if (onExecutor) connProperties.executorConnProps
    else connProperties.connProps
    ConnectionPool.getPoolConnection(id, connProperties.dialect,
      connProperties.poolProps, connProps, connProperties.hikariCP)
  }

  override def getConnectedExternalStore(table: String,
      onExecutor: Boolean): ConnectedExternalStore =
    new JDBCSourceAsColumnarStore(connProperties, numPartitions, tableName, schema)
        with ConnectedExternalStore {
      protected[this] override val connectedInstance: Connection =
        self.getConnection(table, onExecutor)
    }

  override def getColumnBatchRDD(tableName: String,
      rowBuffer: String,
      requiredColumns: Array[String],
      prunePartitions: => Int,
      session: SparkSession,
      schema: StructType): RDD[Any] = {
    val snappySession = session.asInstanceOf[SnappySession]
    connectionType match {
      case ConnectionType.Embedded =>
        new ColumnarStorePartitionedRDD(snappySession,
          tableName, prunePartitions, this)
      case _ =>
        // remove the url property from poolProps since that will be
        // partition-specific
        val poolProps = connProperties.poolProps -
            (if (connProperties.hikariCP) "jdbcUrl" else "url")

        val (parts, embdClusterRelDestroyVersion) =
          SnappyContext.getClusterMode(session.sparkContext) match {
          case ThinClientConnectorMode(_, _) =>
            val catalog = snappySession.sessionCatalog.asInstanceOf[ConnectorCatalog]
            val relInfo = catalog.getCachedRelationInfo(catalog.newQualifiedTableName(rowBuffer))
            (relInfo.partitions, relInfo.embdClusterRelDestroyVersion)
          case _ =>
            (Array.empty[Partition], -1)
        }

        new SmartConnectorColumnRDD(snappySession,
          tableName, requiredColumns, ConnectionProperties(connProperties.url,
            connProperties.driver, connProperties.dialect, poolProps,
            connProperties.connProps, connProperties.executorConnProps,
            connProperties.hikariCP), schema, this, parts, embdClusterRelDestroyVersion)
    }
  }

  protected def doInsert(columnTableName: String, batch: ColumnBatch,
      batchId: Option[String], partitionId: Int,
      maxDeltaRows: Int): (Connection => Any) = {
    (connection: Connection) => {
      // split the batch and put into row buffer if it is small
      if (maxDeltaRows > 0 && batch.numRows < math.max(maxDeltaRows / 10,
        GfxdConstants.SNAPPY_MIN_COLUMN_DELTA_ROWS)) {
        // the lookup key depends only on schema and not on the table
        // name since the prepared statement specific to the table is
        // passed in separately through the references object
        val gen = CodeGeneration.compileCode(
          "COLUMN_TABLE.DECOMPRESS", schema.fields, () => {
            val schemaAttrs = schema.toAttributes
            val tableScan = ColumnTableScan(schemaAttrs, dataRDD = null,
              otherRDDs = Seq.empty, numBuckets = -1,
              partitionColumns = Seq.empty, partitionColumnAliases = Seq.empty,
              baseRelation = null, schema, allFilters = Seq.empty, schemaAttrs)
            val insertPlan = RowDMLExec(tableScan, upsert = true, delete = false,
              Seq.empty, Seq.empty, -1, schema, None, onExecutor = true,
              resolvedName = null, connProperties)
            // now generate the code with the help of WholeStageCodegenExec
            // this is only used for local code generation while its RDD
            // semantics and related methods are all ignored
            val (ctx, code) = ExternalStoreUtils.codeGenOnExecutor(
              WholeStageCodegenExec(insertPlan), insertPlan)
            val references = ctx.references
            // also push the index of connection reference at the end which
            // will be used by caller to update connection before execution
            references += insertPlan.statementRef
            (code, references.toArray)
          })
        val refs = gen._2.clone()
        // set the statement object for current execution
        val statementRef = refs(refs.length - 1).asInstanceOf[Int]
        val resolvedName = ExternalStoreUtils.lookupName(tableName,
          connection.getSchema)
        val putSQL = JdbcExtendedUtils.getInsertOrPutString(resolvedName,
          schema, upsert = true)
        val stmt = connection.prepareStatement(putSQL)
        refs(statementRef) = stmt
        // no harm in passing a references array with extra element at end
        val iter = gen._1.generate(refs).asInstanceOf[BufferedRowIterator]
        // put the single ColumnBatch in the iterator read by generated code
        iter.init(partitionId, Array(Iterator[Any](new ResultSetTraversal(
          conn = null, stmt = null, rs = null, context = null),
          ColumnBatchIterator(batch)).asInstanceOf[Iterator[InternalRow]]))
        // ignore the result which is the update count
        while (iter.hasNext) {
          iter.next()
        }
      } else {
        val resolvedColumnTableName = ExternalStoreUtils.lookupName(
          columnTableName, connection.getSchema)
        connectionType match {
          case ConnectionType.Embedded =>
            val region = Misc.getRegionForTable(resolvedColumnTableName, true)
                .asInstanceOf[PartitionedRegion]
            val batchID = Some(batchId.getOrElse(region.newJavaUUID().toString))
            doGFXDInsert(resolvedColumnTableName, batch, batchID, partitionId,
              maxDeltaRows)(connection)

          case _ =>
            doGFXDInsert(resolvedColumnTableName, batch, batchId, partitionId,
              maxDeltaRows)(connection)
        }
      }
     }
  }

  protected def getPartitionID(tableName: String,
      partitionId: Int = -1): Int = {
    val connection = getConnection(tableName, onExecutor = true)
    try {
      connectionType match {
        case ConnectionType.Embedded =>
          val resolvedName = ExternalStoreUtils.lookupName(tableName,
            connection.getSchema)
          val region = Misc.getRegionForTable(resolvedName, true)
              .asInstanceOf[LocalRegion]
          region match {
            case pr: PartitionedRegion =>
              if (partitionId == -1) {
                val primaryBucketIds = pr.getDataStore.
                    getAllLocalPrimaryBucketIdArray
                // TODO: do load-balancing among partitions instead
                // of random selection
                val numPrimaries = primaryBucketIds.size()
                // if no local primary bucket, then select some other
                if (numPrimaries > 0) {
                  primaryBucketIds.getQuick(rand.nextInt(numPrimaries))
                } else {
                  rand.nextInt(pr.getTotalNumberOfBuckets)
                }
              } else {
                partitionId
              }
            case _ => partitionId
          }
        // TODO: SW: for split mode, get connection to one of the
        // local servers and a bucket ID for only one of those
        case _ => if (partitionId < 0) rand.nextInt(numPartitions) else partitionId
      }
    } finally {
      connection.commit();
      connection.close()
    }
  }
}


final class ColumnarStorePartitionedRDD(
    @transient private val session: SnappySession,
    private var tableName: String,
    @(transient @param) partitionPruner: => Int,
    @transient private val store: JDBCSourceAsColumnarStore)
    extends RDDKryo[Any](session.sparkContext, Nil) with KryoSerializable {

  private[this] var allPartitions: Array[Partition] = _
  private val evaluatePartitions: () => Array[Partition] = () => {
    store.tryExecute(tableName, conn => {
      val resolvedName = ExternalStoreUtils.lookupName(tableName,
        conn.getSchema)
      val region = Misc.getRegionForTable(resolvedName, true)
      partitionPruner match {
        case -1 if allPartitions != null =>
          allPartitions
        case -1 =>
          allPartitions = session.sessionState.getTablePartitions(
            region.asInstanceOf[PartitionedRegion])
          allPartitions
        case bucketId: Int =>
          if (java.lang.Boolean.getBoolean("DISABLE_PARTITION_PRUNING")) {
            allPartitions = session.sessionState.getTablePartitions(
              region.asInstanceOf[PartitionedRegion])
            allPartitions
          } else {
            val pr = region.asInstanceOf[PartitionedRegion]
            import scala.collection.JavaConverters._
            val distMembers = pr.getRegionAdvisor.getBucketOwners(bucketId).asScala
            val prefNodes = distMembers.collect {
              case m if SnappyContext.containsBlockId(m.toString) =>
                Utils.getHostExecutorId(SnappyContext.getBlockId(
                  m.toString).get.blockId)
            }
            Array(new MultiBucketExecutorPartition(0, ArrayBuffer(bucketId),
              pr.getTotalNumberOfBuckets, prefNodes.toSeq))
          }
      }
    })
  }

  override def compute(part: Partition, context: TaskContext): Iterator[Any] = {

    Option(TaskContext.get()).foreach(_.addTaskCompletionListener(_ => {
      val tx = TXManagerImpl.snapshotTxState.get()
      if (tx != null /*&& !(tx.asInstanceOf[TXStateProxy]).isClosed()*/) {
        GemFireCacheImpl.getInstance().getCacheTransactionManager.masqueradeAs(tx)
        GemFireCacheImpl.getInstance().getCacheTransactionManager.commit()
      }
    }
    ))

    val container = GemFireXDUtils.getGemFireContainer(tableName, true)
    // TODO: maybe we can start tx here
    // We can start different tx in each executor for first phase, till global snapshot is available.
    // check if the tx is already running.
    //GemFireCacheImpl.getInstance().getCacheTransactionManager.begin()
    //GemFireCacheImpl.getInstance().getCacheTransactionManager.
    // who will call commit.
    //val txId = GemFireCacheImpl.getInstance().getCacheTransactionManager.getTransactionId;
    val txId = null
    val bucketIds = part match {
      case p: MultiBucketExecutorPartition => p.buckets
      case _ => java.util.Collections.singleton(Int.box(part.index))
    }
    ColumnBatchIterator(container, bucketIds)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[MultiBucketExecutorPartition].hostExecutorIds
  }

  override protected def getPartitions: Array[Partition] = {
    evaluatePartitions()
  }

  def getPartitionEvaluator: () => Array[Partition] = evaluatePartitions

  override def write(kryo: Kryo, output: Output): Unit = {
    super.write(kryo, output)
    output.writeString(tableName)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    super.read(kryo, input)
    tableName = input.readString()
  }
}

 final class SmartConnectorColumnRDD(
    @transient private val session: SnappySession,
    private var tableName: String,
    private var requiredColumns: Array[String],
    private var connProperties: ConnectionProperties,
    private val schema: StructType,
    @transient private val store: ExternalStore,
    val parts: Array[Partition],
    val relDestroyVersion: Int = -1)
    extends RDDKryo[Any](session.sparkContext, Nil)
        with KryoSerializable {

  override def compute(split: Partition,
      context: TaskContext): Iterator[Array[Byte]] = {
    val helper = new SparkShellRDDHelper
    val conn: Connection = helper.getConnection(connProperties, split)
    val resolvedTableName = ExternalStoreUtils.lookupName(tableName, conn.getSchema)
    val (fetchStatsQuery, fetchColQuery) = helper.getSQLStatement(resolvedTableName,
      split.index, requiredColumns.map(_.replace(store.columnPrefix, "")), schema)
    // fetch the stats
    val (statement, rs) = helper.executeQuery(conn, tableName, split, fetchStatsQuery, relDestroyVersion)
    new ColumnBatchIteratorOnRS(conn, requiredColumns, statement, rs, context, fetchColQuery)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[ExecutorMultiBucketLocalShellPartition]
        .hostList.map(_._1.asInstanceOf[String])
  }

  override def getPartitions: Array[Partition] = {
    if (parts != null && parts.length > 0) {
      return parts
    }
    store.tryExecute(tableName, SparkShellRDDHelper.getPartitions(tableName, _))
  }

  override def write(kryo: Kryo, output: Output): Unit = {
    super.write(kryo, output)

    output.writeString(tableName)
    output.writeVarInt(requiredColumns.length, true)
    for (column <- requiredColumns) {
      output.writeString(column)
    }
    ConnectionPropertiesSerializer.write(kryo, output, connProperties)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    super.read(kryo, input)

    tableName = input.readString()
    val numColumns = input.readVarInt(true)
    requiredColumns = Array.fill(numColumns)(input.readString())
    connProperties = ConnectionPropertiesSerializer.read(kryo, input)
  }
}

class SmartConnectorRowRDD(_session: SnappySession,
    _tableName: String,
    _isPartitioned: Boolean,
    _columns: Array[String],
    _connProperties: ConnectionProperties,
    _filters: Array[Filter] = Array.empty[Filter],
    _partEval: () => Array[Partition] = () => Array.empty[Partition],
    _relDestroyVersion: Int = -1)
    extends RowFormatScanRDD(_session, _tableName, _isPartitioned, _columns,
      pushProjections = true, useResultSet = true, _connProperties,
    _filters, _partEval, false) {

  override def computeResultSet(
      thePart: Partition): (Connection, Statement, ResultSet) = {
    val helper = new SparkShellRDDHelper
    val conn: Connection = helper.getConnection(
      connProperties, thePart)
    val resolvedName = StoreUtils.lookupName(tableName, conn.getSchema)

    if (isPartitioned) {
      val ps = conn.prepareStatement(
        s"call sys.SET_BUCKETS_FOR_LOCAL_EXECUTION(?, ?, ${_relDestroyVersion})")
      ps.setString(1, resolvedName)
      val partition = thePart.asInstanceOf[ExecutorMultiBucketLocalShellPartition]
      val bucketString = partition.buckets.mkString(",")
      ps.setString(2, bucketString)
      ps.executeUpdate()
      ps.close()
    }
    val sqlText = s"SELECT $columnList FROM $resolvedName$filterWhereClause"

    val args = filterWhereArgs
    val stmt = conn.prepareStatement(sqlText)
    if (args ne null) {
      ExternalStoreUtils.setStatementParameters(stmt, args.map {
        case pl: ParamLiteral => pl.convertedLiteral
        case v => v
      })
    }
    val fetchSize = connProperties.executorConnProps.getProperty("fetchSize")
    if (fetchSize ne null) {
      stmt.setFetchSize(fetchSize.toInt)
    }

    val rs = stmt.executeQuery()
    (conn, stmt, rs)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[ExecutorMultiBucketLocalShellPartition]
        .hostList.map(_._1.asInstanceOf[String])
  }

  override def getPartitions: Array[Partition] = {
    // use incoming partitions if provided (e.g. for collocated tables)
    val parts = partitionEvaluator()
    if (parts != null && parts.length > 0) {
      return parts
    }
    val conn = ExternalStoreUtils.getConnection(tableName, connProperties,
      forExecutor = true)
    try {
      SparkShellRDDHelper.getPartitions(tableName, conn)
    } finally {
      conn.commit()
      conn.close()
    }
  }

  def getSQLStatement(resolvedTableName: String,
      requiredColumns: Array[String], partitionId: Int): String = {
    "select " + requiredColumns.mkString(", ") + " from " + resolvedTableName
  }
}
