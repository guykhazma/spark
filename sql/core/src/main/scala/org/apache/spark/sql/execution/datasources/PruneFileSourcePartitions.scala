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

package org.apache.spark.sql.execution.datasources

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.CatalogStatistics
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.{Filter, LeafNode, LogicalPlan, Project}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.datasources.v2.{DataSourceV2ScanRelation, FileScan, FileTable}
import org.apache.spark.sql.types.StructType

private[sql] object PruneFileSourcePartitions extends Rule[LogicalPlan] {

  private def getPartitionKeyFilters(
      sparkSession: SparkSession,
      relation: LeafNode,
      partitionSchema: StructType,
      normalizedFilters: Seq[Expression]): ExpressionSet = {
    val partitionColumns =
      relation.resolve(partitionSchema, sparkSession.sessionState.analyzer.resolver)
    val partitionSet = AttributeSet(partitionColumns)
    ExpressionSet(normalizedFilters.filter { f =>
      f.references.subsetOf(partitionSet)
    })
  }

  private def rebuildPhysicalOperation(
      projects: Seq[NamedExpression],
      filters: Seq[Expression],
      relation: LeafNode): Project = {
    // Keep partition-pruning predicates so that they are visible in physical planning
    val filterExpression = filters.reduceLeft(And)
    val filter = Filter(filterExpression, relation)
    Project(projects, filter)
  }

  override def apply(plan: LogicalPlan): LogicalPlan = plan transformDown {
    case op @ PhysicalOperation(projects, filters,
        logicalRelation @
          LogicalRelation(fsRelation @
            HadoopFsRelation(
              catalogFileIndex: CatalogFileIndex,
              partitionSchema,
              _,
              _,
              _,
              _),
            _,
            _,
            _))
        if filters.nonEmpty && fsRelation.partitionSchemaOption.isDefined =>
      val normalizedFilters = DataSourceStrategy.normalizeExprs(
        filters.filterNot(SubqueryExpression.hasSubquery), logicalRelation.output)
      val partitionKeyFilters = getPartitionKeyFilters(
        fsRelation.sparkSession, logicalRelation, partitionSchema, normalizedFilters)
      if (partitionKeyFilters.nonEmpty) {
        val prunedFileIndex = catalogFileIndex.filterPartitions(partitionKeyFilters.toSeq)
        val prunedFsRelation =
          fsRelation.copy(location = prunedFileIndex)(fsRelation.sparkSession)
        // Change table stats based on the sizeInBytes of pruned files
        val withStats = logicalRelation.catalogTable.map(_.copy(
          stats = Some(CatalogStatistics(sizeInBytes = BigInt(prunedFileIndex.sizeInBytes)))))
        val prunedLogicalRelation = logicalRelation.copy(
          relation = prunedFsRelation, catalogTable = withStats)
        rebuildPhysicalOperation(projects, filters, prunedLogicalRelation)
      } else {
        op
      }

    case op @ PhysicalOperation(projects, filters,
        v2Relation @ DataSourceV2ScanRelation(_, scan: FileScan, output))
        if filters.nonEmpty  && scan.readDataSchema.nonEmpty =>
      val normalizedFilters = DataSourceStrategy.normalizeExprs(
        filters.filter(_.deterministic), output)
      val partitionKeyFilters = getPartitionKeyFilters(scan.sparkSession,
        v2Relation, scan.readPartitionSchema, normalizedFilters)
      if (partitionKeyFilters.nonEmpty) {
        val prunedV2Relation =
          v2Relation.copy(scan = scan.withPartitionFilters(partitionKeyFilters.toSeq))
        val afterScanFilters =
          ExpressionSet(filters) -- partitionKeyFilters.filter(_.references.nonEmpty)
        rebuildPhysicalOperation(projects, afterScanFilters.toSeq, prunedV2Relation)
      } else {
        op
      }
  }
}
