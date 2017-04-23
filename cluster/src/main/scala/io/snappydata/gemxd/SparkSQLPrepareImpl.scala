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
package io.snappydata.gemxd

import java.io.DataOutput

import scala.collection.mutable

import com.gemstone.gemfire.DataSerializer
import com.gemstone.gemfire.internal.shared.Version
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.message.LeadNodeExecutorMsg
import com.pivotal.gemfirexd.internal.engine.distributed.{GfxdHeapDataOutputStream, SnappyResultHolder}
import com.pivotal.gemfirexd.internal.iapi.types.{DataType => _, _}
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds
import com.pivotal.gemfirexd.internal.snappy.{LeadNodeExecutionContext, SparkSQLExecute}

import org.apache.spark.Logging
import org.apache.spark.sql.{Row, SnappySession}
import org.apache.spark.sql.catalyst.expressions.{BinaryComparison, Cast, Exists, Expression, Like, ListQuery, ParamLiteral, PredicateSubquery, ScalarSubquery, SubqueryExpression}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.internal.SnappySessionState
import org.apache.spark.sql.types._
import org.apache.spark.util.SnappyUtils


class SparkSQLPrepareImpl(val sql: String,
    val schema: String,
    val ctx: LeadNodeExecutionContext,
    senderVersion: Version) extends SparkSQLExecute with Logging {

  if (Thread.currentThread().getContextClassLoader != null) {
    val loader = SnappyUtils.getSnappyStoreContextLoader(
      SparkSQLExecuteImpl.getContextOrCurrentClassLoader)
    Thread.currentThread().setContextClassLoader(loader)
  }

  private[this] val session = SnappySessionPerConnection
      .getSnappySessionForConnection(ctx.getConnId)

  session.setSchema(schema)

  session.setPreparedQuery(true, null)

  private[this] val sessionState: SnappySessionState = {
    val field = classOf[SnappySession].getDeclaredField("sessionState")
    field.setAccessible(true)
    field.get(session).asInstanceOf[SnappySessionState]
  }

  private[this] val analyzedPlan: LogicalPlan = {
    val method = classOf[SnappySession].getDeclaredMethod("prepareSQL", classOf[String])
    method.setAccessible(true)
    method.invoke(session, sql).asInstanceOf[LogicalPlan]
  }

  private[this] val thresholdListener = Misc.getMemStore.thresholdListener()

  protected[this] val hdos = new GfxdHeapDataOutputStream(
    thresholdListener, sql, true, senderVersion)

  override def packRows(msg: LeadNodeExecutorMsg,
      srh: SnappyResultHolder): Unit = {
    hdos.clearForReuse()
    if (sessionState.questionMarkCounter > 0) {
      val paramLiterals0 = new mutable.HashSet[ParamLiteral]()
      val paramLiterals1 = allParamLiteralsPhase1(analyzedPlan, paramLiterals0)
      val paramLiterals2 = allParamLiteralsPhase2(analyzedPlan, paramLiterals1)
      val paramLiteralsAtPrepare = paramLiterals2.toArray.sortBy(_.pos)
      val paramCount = paramLiteralsAtPrepare.length
      if (paramCount != sessionState.questionMarkCounter) {
        throw new UnsupportedOperationException("This query is unsupported for prepared statement")
      }
      val types = new Array[Int](paramCount * 4 + 1)
      types(0) = paramCount
      (0 until paramCount) foreach (i => {
        assert(paramLiteralsAtPrepare(i).pos == i + 1)
        val index = i * 4 + 1
        val dType = paramLiteralsAtPrepare(i).dataType
        val sqlType = getSQLType(dType)
        types(index) = sqlType._1
        types(index + 1) = sqlType._2
        types(index + 2) = sqlType._3
        types(index + 3) = if (paramLiteralsAtPrepare(i).value.asInstanceOf[Boolean]) 1 else 0
      })
      DataSerializer.writeIntArray(types, hdos)
    } else {
      DataSerializer.writeIntArray(Array[Int](0), hdos)
    }

    if (msg.isLocallyExecuted) {
      SparkSQLExecuteImpl.handleLocalExecution(srh, hdos)
    }
    msg.lastResult(srh)
  }

  override def serializeRows(out: DataOutput, hasMetadata: Boolean): Unit =
    SparkSQLExecuteImpl.serializeRows(out, hasMetadata, hdos)

  // Also see SnappyResultHolder.getNewNullDVD(
  def getSQLType(dataType: DataType): (Int, Int, Int) = dataType match {
    case IntegerType => (StoredFormatIds.SQL_INTEGER_ID, -1, -1)
    case StringType => (StoredFormatIds.SQL_CLOB_ID, -1, -1)
    case LongType => (StoredFormatIds.SQL_LONGINT_ID, -1, -1)
    case TimestampType => (StoredFormatIds.SQL_TIMESTAMP_ID, -1, -1)
    case DateType => (StoredFormatIds.SQL_DATE_ID, -1, -1)
    case DoubleType => (StoredFormatIds.SQL_DOUBLE_ID, -1, -1)
    case t: DecimalType => (StoredFormatIds.SQL_DECIMAL_ID,
        t.precision, t.scale)
    case FloatType => (StoredFormatIds.SQL_REAL_ID, -1, -1)
    case BooleanType => (StoredFormatIds.SQL_BOOLEAN_ID, -1, -1)
    case ShortType => (StoredFormatIds.SQL_SMALLINT_ID, -1, -1)
    case ByteType => (StoredFormatIds.SQL_TINYINT_ID, -1, -1)
    case BinaryType => (StoredFormatIds.SQL_BLOB_ID, -1, -1)
    case _: ArrayType | _: MapType | _: StructType =>
      // indicates complex types serialized as json strings
      (StoredFormatIds.REF_TYPE_ID, -1, -1)

    // send across rest as objects that will be displayed as json strings
    case _ => (StoredFormatIds.REF_TYPE_ID, -1, -1)
  }

  def allParamLiteralsPhase1(plan: LogicalPlan,
      result: mutable.HashSet[ParamLiteral]): mutable.HashSet[ParamLiteral] = {

    def allParams(plan: LogicalPlan): LogicalPlan = plan transformAllExpressions {
      case bl@BinaryComparison(left: Expression, ParamLiteral(Row(pos: Int), _, _)) =>
        result += ParamLiteral(left.nullable, left.dataType, pos)
        bl
      case br@BinaryComparison(ParamLiteral(Row(pos: Int), _, _), right: Expression) =>
        result += ParamLiteral(right.nullable, right.dataType, pos)
        br
      case l@Like(left: Expression, ParamLiteral(Row(pos: Int), _, _)) =>
        result += ParamLiteral(left.nullable, left.dataType, pos)
        l
      case inlist@org.apache.spark.sql.catalyst.expressions.In(value: Expression,
      list: Seq[Expression]) => list.map {
        case ParamLiteral(Row(pos: Int), _, _) =>
          result += ParamLiteral(value.nullable, value.dataType, pos)
        case x => x
      }
        inlist
    }

    handleSubQuery(allParams(plan), allParams)
    result
  }

  def allParamLiteralsPhase2(plan: LogicalPlan,
      result: mutable.HashSet[ParamLiteral]): mutable.HashSet[ParamLiteral] = {

    def allParams(plan: LogicalPlan): LogicalPlan = plan transformAllExpressions {
      case c@Cast(ParamLiteral(Row(pos: Int), _, _), right: DataType) =>
        if (!result.exists(_.pos == pos)) {
          result += ParamLiteral(false, right, pos)
        }
        c
    }

    handleSubQuery(allParams(plan), allParams)
    result
  }

  def handleSubQuery(plan: LogicalPlan,
      f: (LogicalPlan) => LogicalPlan): LogicalPlan = plan transformAllExpressions {
    case sub: SubqueryExpression => sub match {
      case l@ListQuery(query, x) => l.copy(f(query), x)
      case e@Exists(query, x) => e.copy(f(query), x)
      case p@PredicateSubquery(query, x, y, z) => p.copy(f(query), x, y, z)
      case s@ScalarSubquery(query, x, y) => s.copy(f(query), x, y)
    }
  }
}
