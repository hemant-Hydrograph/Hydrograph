/*
 * Copyright 2014 Databricks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hydrograph.engine.spark.datasource.xml

import java.io.IOException

import hydrograph.engine.spark.datasource.xml.parsers.StaxXmlParser
import hydrograph.engine.spark.datasource.xml.util.{InferSchema, XmlFile}
import org.apache.hadoop.fs.Path
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.sources.{BaseRelation, InsertableRelation, PrunedScan, TableScan}
import org.apache.spark.sql.types._
import org.slf4j.LoggerFactory

/**
  * The Class XmlRelation.
  *
  * @author Bitwise
  *
  */
case class XmlRelation protected[spark] (
    baseRDD: () => RDD[String],
    location: Option[String],
    parameters: Map[String, String],
    userSchema: StructType = null)(@transient val sqlContext: SQLContext)
  extends BaseRelation
  with InsertableRelation
  with TableScan
  with PrunedScan {

  private val logger = LoggerFactory.getLogger(XmlRelation.getClass)

  private val options = XmlOptions(parameters)

  override val schema: StructType = {
    Option(userSchema).getOrElse {
      InferSchema.infer(
        baseRDD(),
        options)
    }
  }

  override def buildScan(): RDD[Row] = {
    StaxXmlParser.parse(
      baseRDD(),
      schema,
      options)
  }

  override def buildScan(requiredColumns: Array[String]): RDD[Row] = {
    val requiredFields = requiredColumns.map(schema(_))
    val schemaFields = schema.fields
    if (schemaFields.deep == requiredFields.deep) {
      buildScan()
    } else {
      val requestedSchema = StructType(requiredFields)
      StaxXmlParser.parse(
        baseRDD(),
        requestedSchema,
        options)
    }
  }

  // The function below was borrowed from JSONRelation
  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    val filesystemPath = location match {
      case Some(p) => new Path(p)
      case None =>
        throw new IOException(s"Cannot INSERT into table with no path defined")
    }

    val fs = filesystemPath.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)

    if (overwrite) {
      try {
        fs.delete(filesystemPath, true)
      } catch {
        case e: IOException =>
          throw new IOException(
            s"Unable to clear output directory ${filesystemPath.toString} prior"
              + s" to INSERT OVERWRITE a XML table:\n${e.toString}")
      }
      // Write the data. We assume that schema isn't changed, and we won't update it.
      XmlFile.saveAsXmlFile(data, filesystemPath.toString, parameters)
    } else {
      sys.error("XML tables only support INSERT OVERWRITE for now.")
    }
  }
}
