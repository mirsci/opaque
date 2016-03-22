/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.sql.QED
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.IsNotNull
import org.apache.spark.sql.catalyst.expressions.PredicateHelper
import org.apache.spark.sql.catalyst.expressions.SortOrder

import org.apache.spark.sql.execution.metric.SQLMetrics

case class EncFilter(condition: Expression, child: SparkPlan)
  extends UnaryNode with PredicateHelper {

  // Split out all the IsNotNulls from condition.
  private val (notNullPreds, _) = splitConjunctivePredicates(condition).partition {
    case IsNotNull(a) if child.output.contains(a) => true
    case _ => false
  }

  // The columns that will filtered out by `IsNotNull` could be considered as not nullable.
  private val notNullAttributes = notNullPreds.flatMap(_.references)

  override def output: Seq[Attribute] = {
    child.output.map { a =>
      if (a.nullable && notNullAttributes.contains(a)) {
        a.withNullability(false)
      } else {
        a
      }
    }
  }

  @transient lazy val conditionEvaluator = newPredicate(condition, child.output)

  override def doExecute() = child.execute().mapPartitions { iter =>
    val predicateId = QED.enclaveRegisterPredicate(condition)
    val schemaTypes = schema.map(_.dataType)
    iter.filter(QED.enclaveEvalPredicate(predicateId, _, schemaTypes))
  }

  private[sql] override lazy val metrics = Map(
    "numOutputRows" -> SQLMetrics.createLongMetric(sparkContext, "number of output rows"))

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering
}