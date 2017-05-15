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
package org.apache.spark.sql

import java.sql.{Connection, DriverManager}
import java.util.{Properties, TimeZone}

import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase
import io.snappydata.benchmark.snappy.{SnappyAdapter, SnappyTPCH, TPCH, TPCH_Snappy}
import io.snappydata.{PlanTest, SnappyFunSuite}
import org.scalatest.BeforeAndAfterEach

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.catalyst.expressions.SubqueryExpression
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Sort}
import org.apache.spark.sql.execution.streaming.{Offset, Sink, Source}
import org.apache.spark.sql.sources.{DataSourceRegister, StreamSinkProvider, StreamSourceProvider}
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Benchmark

class IndexTest extends SnappyFunSuite with PlanTest with BeforeAndAfterEach {
  var existingSkipSPSCompile = false

  override def beforeAll(): Unit = {
    System.setProperty("org.codehaus.janino.source_debugging.enable", "true")
    System.setProperty("spark.sql.codegen.comments", "true")
    System.setProperty("spark.testing", "true")
    existingSkipSPSCompile = FabricDatabase.SKIP_SPS_PRECOMPILE
    FabricDatabase.SKIP_SPS_PRECOMPILE = true
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    System.clearProperty("org.codehaus.janino.source_debugging.enable")
    System.clearProperty("spark.sql.codegen.comments")
    System.clearProperty("spark.testing")
    System.clearProperty("DISABLE_PARTITION_PRUNING")
    FabricDatabase.SKIP_SPS_PRECOMPILE = existingSkipSPSCompile
    super.afterAll()
  }

  test("check CDC") {
    // scalastyle:off println
    val sqldriver = "/data/wrk/w/snappydata/sqljdbc4.jar"
    val conf = new SparkConf().
        setIfMissing("spark.master", "local[4]").
        setAppName(getClass.getName).
        setJars(Seq(sqldriver)).
        set("spark.driver.extraClassPath", sqldriver).
        set("spark.executor.extraClassPath", sqldriver)

    // val spark = new SparkContext(conf)

    val spark = SparkSession.
        builder().
        appName("Spark SQLServer Example").
        config(conf).
        getOrCreate()
    import spark.implicits._

    Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")

    val frames = Array("customer").map { tab =>
      val df = spark.readStream
          .format("jdbcStream")
          .option("partition.1", "clientid > 1 and clientid < 100")
          .option("driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver")
          .option("url", "jdbc:sqlserver://snappydb16.westus.cloudapp.azure.com:1433")
          .option("dbtable", s"(select top 100000 * from tengb..$tab) x")
          .option("user", "sqldb")
          .option("password", "snappydata#msft1")
          .load()

/*
      val xd = df.select(functions.window(functions.col("__startlsn"),
        "10 seconds", "10 seconds", "5 seconds"))
*/

      df.writeStream
          .foreach(new ForeachWriter[Row] {
            /**
             * Called to process the data in the executor side. This method will be called only
             * when `open`
             * returns `true`.
             */
            override def process(value: Row): Unit = {
              value.schema.fields.foreach(_.dataType)
              println(value.mkString)
            }

            /**
             * Called when stopping to process one partition of new data in the executor side.
             * This is
             * guaranteed to be called either `open` returns `true` or `false`. However,
             * `close` won't be called in the following cases:
             *  - JVM crashes without throwing a `Throwable`
             *  - `open` throws a `Throwable`.
             *
             * @param errorOrNull the error thrown during processing data or null if there was no
             *                    error.
             */
            override def close(errorOrNull: Throwable): Unit = {
              // savePoint()
            }

            /**
             * Called when starting to process one partition of new data in the executor. The
             * `version` is
             * for data deduplication when there are failures. When recovering from a failure,
             * some data may
             * be generated multiple times but they will always have the same version.
             *
             * If this method finds using the `partitionId` and `version` that this partition has
             * already been
             * processed, it can return `false` to skip the further data processing. However,
             * `close` still
             * will be called for cleaning up resources.
             *
             * @param partitionId the partition id.
             * @param version     a unique id for data deduplication.
             * @return `true` if the corresponding partition and version id should be processed.
             *        `false`
             *         indicates the partition should be skipped.
             */
            override def open(partitionId: Long, version: Long): Boolean = {
              true
            }

          }).start()
    }

    frames.foreach(_.awaitTermination())

    var from = 0
    var upto = 10000
    val sdf = spark.read.
        option("user", "sqldb").
        option("password", "snappydata#msft1").
        jdbc(s"jdbc:sqlserver://snappydb16.westus.cloudapp.azure.com:1433",
          "(select * from tengb..customer) x",
          Array(s"C_CustKey > $from and C_CustKey < $upto"),
          new Properties()
        )

    sdf.printSchema()

    sdf.explain(true)

    val begin = System.currentTimeMillis()
    sdf.foreachPartition { iter =>
      iter.foreach { r =>
        r.getInt(0) % 1000 match {
          case 0 => println(s"read ${r.getString(1)}")
          case _ =>
        }
        /*
                r.getInt(3) match {
                  case 1 => // delete
                  // println(s"DELETE entry ${r.get(5)}")
                  case 2 => // insert
                  // println(s"INSERT entry ${r.get(5)}")
                  case 4 | 5 => // update
                  // println(s"UPSERT entry ${r.get(5)}")
                }
        */
      }
    }

    println(s"Time taken to execute ${System.currentTimeMillis() - begin} ms")
  }

  /*

    test("dd") {
      // scalastyle:off println
      val toks = Seq("[dd]", "[dd1]", "date '[DATE]'", "date '[DATE]' + interval '1' year",
        "[Quantity]", "[dd2]")

      val args = Seq("y", "1-1-1999", "1", "zz")

      val newArgs = toks.zipWithIndex.sliding(2).flatMap(_.toList match {
        case (l, i) :: (r, _) :: Nil
          if l.indexOf("date '[DATE]'") >= 0 && r.indexOf("date '[DATE]' ") >= 0 =>
          Seq(args(i), args(i))
        case (_, i) :: _ if i < args.length =>
          Seq(args(i))
        case x =>
          Seq.empty
      }).toList

      def sideBySide(left: Seq[String], right: Seq[String]): Seq[String] = {
        val maxLeftSize = left.map(_.length).max
        val leftPadded = left ++ Seq.fill(math.max(right.size - left.size, 0))(" ")
        val rightPadded = right ++ Seq.fill(math.max(left.size - right.size, 0))(" ")
        leftPadded.zip(rightPadded).map {
          case (l, r) => l + (" " * ((maxLeftSize - l.length) + 3)) + r
        }
      }

      if(toks.length != newArgs.length) {
        println(sideBySide(toks, newArgs).mkString("\n"))
      }
      println(newArgs)
      // scalastyle:on println
    }
  */

  test("check varchar index") {
    /*
        snc.sql("Create table ODS.ORGANIZATIONS(" +
            "org_id bigint GENERATED BY DEFAULT AS IDENTITY  NOT NULL," +
            "ver bigint NOT NULL," +
            "client_id bigint NOT NULL," +
            "org_nm  varchar(80), " +
            "org_typ_ref_id bigint NOT NULL," +
            "descr LONG VARCHAR," +
            "empr_tax_id varchar(25)," +
            "web_site varchar(100)," +
            "eff_dt DATE," +
            "expr_dt DATE," +
            "vld_frm_dt " +
            "TIMESTAMP NOT NULL," +
            "vld_to_dt TIMESTAMP," +
            "src_sys_ref_id LONG VARCHAR NOT NULL," +
            "src_sys_rec_id LONG VARCHAR," +
            "PRIMARY KEY (client_id,org_id)" +
            ")" +
            "using row options (partition_by 'org_id')" +
            "")
    */
    snc.sql("Create table ODS.ORGANIZATIONS(" +
        "org_id bigint GENERATED BY DEFAULT AS IDENTITY  NOT NULL," +
        "client_id bigint NOT NULL," +
        "descr LONG VARCHAR," +
        "PRIMARY KEY (client_id,org_id)" +
        ") " +
        "using row options (partition_by 'org_id')" +
        "")

    snc.sql("create index ods.idx_org on ODS.ORGANIZATIONS (CLIENT_ID, DESCR)")

    snc.sql("insert into ods.organizations(client_id, descr) values(8006, 'EL')")
    snc.sql("update ods.organizations set descr = 'EL                                            " +
        "                                                                      " +
        "  ' where client_id = 8006")
    snc.sql("select * from ods.organizations").show()
    snc.sql("select client_id, descr from ods.organizations where client_id = 8006").show()
  }

  test("tpch queries") {
    // scalastyle:off println
    val qryProvider = new TPCH with SnappyAdapter

    val queries = Array("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
      "12", "13", "14", "15", "16", "17", "18", "19",
      "20", "21", "22")

    TPCHUtils.createAndLoadTables(snc, true)

    val existing = snc.getConf(io.snappydata.Property.EnableExperimentalFeatures.name)
    snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name, "true")

    for ((q, i) <- queries.zipWithIndex) {
      val qNum = i + 1
      val (expectedAnswer, _) = qryProvider.execute(qNum, str => {
        snc.sql(str)
      })
      val (newAnswer, df) = TPCH_Snappy.queryExecution(q, snc, false, false)
      val isSorted = df.logicalPlan.collect { case s: Sort => s }.nonEmpty
      QueryTest.sameRows(expectedAnswer, newAnswer, isSorted).map { results =>
        s"""
           |Results do not match for query: $qNum
           |Timezone: ${TimeZone.getDefault}
           |Timezone Env: ${sys.env.getOrElse("TZ", "")}
           |
           |${df.queryExecution}
           |== Results ==
           |$results
       """.stripMargin
      }
      println(s"Done $qNum")
    }
    snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name, existing)

  }

  ignore("Benchmark tpch") {

    try {
      val queries = Array("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
        "12", "13", "14", "15", "16", "17", "18", "19",
        "20", "21", "22")

      sc(c => c.set("spark.local.dir", "/data/temp"))

      TPCHUtils.createAndLoadTables(snc, true)

      snc.sql(
        s"""CREATE INDEX idx_orders_cust ON orders(o_custkey)
             options (COLOCATE_WITH 'customer')
          """)

      snc.sql(
        s"""CREATE INDEX idx_lineitem_part ON lineitem(l_partkey)
             options (COLOCATE_WITH 'part')
          """)

      val tables = Seq("nation", "region", "supplier", "customer", "orders", "lineitem", "part",
        "partsupp")

      val tableSizes = tables.map { tableName =>
        (tableName, snc.table(tableName).count())
      }.toMap

      tableSizes.foreach(println)
      runBenchmark("select o_orderkey from orders where o_orderkey = 1", tableSizes, 2)
      runBenchmark("select o_orderkey from orders where o_orderkey = 32", tableSizes)
      runBenchmark("select o_orderkey from orders where o_orderkey = 801", tableSizes)
      runBenchmark("select o_orderkey from orders where o_orderkey = 1409", tableSizes)
      // queries.foreach(q => benchmark(q, tableSizes))

    } finally {
      snc.sql(s"DROP INDEX if exists idx_orders_cust")
      snc.sql(s"DROP INDEX if exists idx_lineitem_part")
    }
  }

  private def togglePruning(onOff: Boolean) =
    System.setProperty("DISABLE_PARTITION_PRUNING", onOff.toString)

  def runBenchmark(queryString: String, tableSizes: Map[String, Long], numSecs: Int = 0): Unit = {

    // This is an indirect hack to estimate the size of each query's input by traversing the
    // logical plan and adding up the sizes of all tables that appear in the plan. Note that this
    // currently doesn't take WITH subqueries into account which might lead to fairly inaccurate
    // per-row processing time for those cases.
    val queryRelations = scala.collection.mutable.HashSet[String]()
    snc.sql(queryString).queryExecution.logical.map {
      case ur@UnresolvedRelation(t: TableIdentifier, _) =>
        queryRelations.add(t.table.toLowerCase)
      case lp: LogicalPlan =>
        lp.expressions.foreach {
          _ foreach {
            case subquery: SubqueryExpression =>
              subquery.plan.foreach {
                case ur@UnresolvedRelation(t: TableIdentifier, _) =>
                  queryRelations.add(t.table.toLowerCase)
                case _ =>
              }
            case _ =>
          }
        }
      case _ =>
    }
    val size = queryRelations.map(tableSizes.getOrElse(_, 0L)).sum

    import scala.concurrent.duration._
    val b = new Benchmark(s"JoinOrder optimization", size,
      warmupTime = numSecs.seconds)
    b.addCase("WithOut Partition Pruning",
      prepare = () => togglePruning(true))(_ => snc.sql(queryString).collect().foreach(_ => ()))
    b.addCase("With Partition Pruning",
      prepare = () => togglePruning(false))(_ => snc.sql(queryString).collect().foreach(_ => ()))
    b.run()
  }

  def benchmark(qNum: String, tableSizes: Map[String, Long]): Unit = {

    val qryProvider = new TPCH with SnappyAdapter
    val query = qNum.toInt

    def executor(str: String) = snc.sql(str)

    val size = qryProvider.estimateSizes(query, tableSizes, executor)
    println(s"$qNum size $size")
    val b = new Benchmark(s"JoinOrder optimization", size, minNumIters = 10)

    def case1(): Unit = snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name,
      "false")

    def case2(): Unit = snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name,
      "true")

    def case3(): Unit = {
      snc.setConf(io.snappydata.Property.EnableExperimentalFeatures.name,
        "true")
    }

    def evalSnappyMods(genPlan: Boolean) = TPCH_Snappy.queryExecution(qNum, snc, useIndex = false,
      genPlan = genPlan)._1.foreach(_ => ())

    def evalBaseTPCH = qryProvider.execute(query, executor)._1.foreach(_ => ())


    //    b.addCase(s"$qNum baseTPCH index = F", prepare = case1)(i => evalBaseTPCH)
    //    b.addCase(s"$qNum baseTPCH joinOrder = T", prepare = case2)(i => evalBaseTPCH)
    b.addCase(s"$qNum without PartitionPruning",
      prepare = () => togglePruning(true))(_ => evalSnappyMods(false))
    b.addCase(s"$qNum with PartitionPruning",
      prepare = () => togglePruning(false))(_ => evalSnappyMods(false))
    /*
        b.addCase(s"$qNum snappyMods joinOrder = T", prepare = case2)(i => evalSnappyMods(false))
        b.addCase(s"$qNum baseTPCH index = T", prepare = case3)(i => evalBaseTPCH)
    */
    b.run()

  }

  test("northwind queries") {
    println("")
    //    val sctx = sc(c => c.set("spark.sql.inMemoryColumnarStorage.batchSize", "40000"))
    //    val snc = getOrCreate(sctx)
    //    NorthWindDUnitTest.createAndLoadColumnTables(snc)
    //    val s = "select distinct shipcountry from orders"
    //    snc.sql(s).show()
    //    NWQueries.assertJoin(snc, NWQueries.Q42, "Q42", 22, 1, classOf[LocalJoin])
    /*
        Thread.sleep(1000 * 60 * 60)
        NWQueries.assertJoin(snc, NWQueries.Q42, "Q42", 22, 1, classOf[LocalJoin])
    */
  }

}
