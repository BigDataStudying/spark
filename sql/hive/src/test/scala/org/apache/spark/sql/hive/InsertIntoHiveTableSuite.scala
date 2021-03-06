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

package org.apache.spark.sql.hive

import java.io.File

import org.apache.hadoop.hive.conf.HiveConf
import org.scalatest.BeforeAndAfter

import org.apache.spark.SparkException
import org.apache.spark.sql.{QueryTest, _}
import org.apache.spark.sql.catalyst.plans.logical.InsertIntoTable
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

case class TestData(key: Int, value: String)

case class ThreeCloumntable(key: Int, value: String, key1: String)

class InsertIntoHiveTableSuite extends QueryTest with TestHiveSingleton with BeforeAndAfter
    with SQLTestUtils {
  import spark.implicits._

  override lazy val testData = spark.sparkContext.parallelize(
    (1 to 100).map(i => TestData(i, i.toString))).toDF()

  before {
    // Since every we are doing tests for DDL statements,
    // it is better to reset before every test.
    hiveContext.reset()
    // Creates a temporary view with testData, which will be used in all tests.
    testData.createOrReplaceTempView("testData")
  }

  test("insertInto() HiveTable") {
    sql("CREATE TABLE createAndInsertTest (key int, value string)")

    // Add some data.
    testData.write.mode(SaveMode.Append).insertInto("createAndInsertTest")

    // Make sure the table has also been updated.
    checkAnswer(
      sql("SELECT * FROM createAndInsertTest"),
      testData.collect().toSeq
    )

    // Add more data.
    testData.write.mode(SaveMode.Append).insertInto("createAndInsertTest")

    // Make sure the table has been updated.
    checkAnswer(
      sql("SELECT * FROM createAndInsertTest"),
      testData.toDF().collect().toSeq ++ testData.toDF().collect().toSeq
    )

    // Now overwrite.
    testData.write.mode(SaveMode.Overwrite).insertInto("createAndInsertTest")

    // Make sure the registered table has also been updated.
    checkAnswer(
      sql("SELECT * FROM createAndInsertTest"),
      testData.collect().toSeq
    )
  }

  test("Double create fails when allowExisting = false") {
    sql("CREATE TABLE doubleCreateAndInsertTest (key int, value string)")

    intercept[AnalysisException] {
      sql("CREATE TABLE doubleCreateAndInsertTest (key int, value string)")
    }
  }

  test("Double create does not fail when allowExisting = true") {
    sql("CREATE TABLE doubleCreateAndInsertTest (key int, value string)")
    sql("CREATE TABLE IF NOT EXISTS doubleCreateAndInsertTest (key int, value string)")
  }

  test("SPARK-4052: scala.collection.Map as value type of MapType") {
    val schema = StructType(StructField("m", MapType(StringType, StringType), true) :: Nil)
    val rowRDD = spark.sparkContext.parallelize(
      (1 to 100).map(i => Row(scala.collection.mutable.HashMap(s"key$i" -> s"value$i"))))
    val df = spark.createDataFrame(rowRDD, schema)
    df.createOrReplaceTempView("tableWithMapValue")
    sql("CREATE TABLE hiveTableWithMapValue(m MAP <STRING, STRING>)")
    sql("INSERT OVERWRITE TABLE hiveTableWithMapValue SELECT m FROM tableWithMapValue")

    checkAnswer(
      sql("SELECT * FROM hiveTableWithMapValue"),
      rowRDD.collect().toSeq
    )

    sql("DROP TABLE hiveTableWithMapValue")
  }

  test("SPARK-4203:random partition directory order") {
    sql("CREATE TABLE tmp_table (key int, value string)")
    val tmpDir = Utils.createTempDir()
    // The default value of hive.exec.stagingdir.
    val stagingDir = ".hive-staging"

    sql(
      s"""
         |CREATE TABLE table_with_partition(c1 string)
         |PARTITIONED by (p1 string,p2 string,p3 string,p4 string,p5 string)
         |location '${tmpDir.toURI.toString}'
        """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (p1='a',p2='b',p3='c',p4='c',p5='1')
        |SELECT 'blarr' FROM tmp_table
      """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (p1='a',p2='b',p3='c',p4='c',p5='2')
        |SELECT 'blarr' FROM tmp_table
      """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (p1='a',p2='b',p3='c',p4='c',p5='3')
        |SELECT 'blarr' FROM tmp_table
      """.stripMargin)
    sql(
      """
        |INSERT OVERWRITE TABLE table_with_partition
        |partition (p1='a',p2='b',p3='c',p4='c',p5='4')
        |SELECT 'blarr' FROM tmp_table
      """.stripMargin)
    def listFolders(path: File, acc: List[String]): List[List[String]] = {
      val dir = path.listFiles()
      val folders = dir.filter { e => e.isDirectory && !e.getName().startsWith(stagingDir) }.toList
      if (folders.isEmpty) {
        List(acc.reverse)
      } else {
        folders.flatMap(x => listFolders(x, x.getName :: acc))
      }
    }
    val expected = List(
      "p1=a"::"p2=b"::"p3=c"::"p4=c"::"p5=2"::Nil,
      "p1=a"::"p2=b"::"p3=c"::"p4=c"::"p5=3"::Nil,
      "p1=a"::"p2=b"::"p3=c"::"p4=c"::"p5=1"::Nil,
      "p1=a"::"p2=b"::"p3=c"::"p4=c"::"p5=4"::Nil
    )
    assert(listFolders(tmpDir, List()).sortBy(_.toString()) === expected.sortBy(_.toString))
    sql("DROP TABLE table_with_partition")
    sql("DROP TABLE tmp_table")
  }

  test("INSERT OVERWRITE - partition IF NOT EXISTS") {
    withTempDir { tmpDir =>
      val table = "table_with_partition"
      withTable(table) {
        val selQuery = s"select c1, p1, p2 from $table"
        sql(
          s"""
             |CREATE TABLE $table(c1 string)
             |PARTITIONED by (p1 string,p2 string)
             |location '${tmpDir.toURI.toString}'
           """.stripMargin)
        sql(
          s"""
             |INSERT OVERWRITE TABLE $table
             |partition (p1='a',p2='b')
             |SELECT 'blarr'
           """.stripMargin)
        checkAnswer(
          sql(selQuery),
          Row("blarr", "a", "b"))

        sql(
          s"""
             |INSERT OVERWRITE TABLE $table
             |partition (p1='a',p2='b')
             |SELECT 'blarr2'
           """.stripMargin)
        checkAnswer(
          sql(selQuery),
          Row("blarr2", "a", "b"))

        var e = intercept[AnalysisException] {
          sql(
            s"""
               |INSERT OVERWRITE TABLE $table
               |partition (p1='a',p2) IF NOT EXISTS
               |SELECT 'blarr3', 'newPartition'
             """.stripMargin)
        }
        assert(e.getMessage.contains(
          "Dynamic partitions do not support IF NOT EXISTS. Specified partitions with value: [p2]"))

        e = intercept[AnalysisException] {
          sql(
            s"""
               |INSERT OVERWRITE TABLE $table
               |partition (p1='a',p2) IF NOT EXISTS
               |SELECT 'blarr3', 'b'
             """.stripMargin)
        }
        assert(e.getMessage.contains(
          "Dynamic partitions do not support IF NOT EXISTS. Specified partitions with value: [p2]"))

        // If the partition already exists, the insert will overwrite the data
        // unless users specify IF NOT EXISTS
        sql(
          s"""
             |INSERT OVERWRITE TABLE $table
             |partition (p1='a',p2='b') IF NOT EXISTS
             |SELECT 'blarr3'
           """.stripMargin)
        checkAnswer(
          sql(selQuery),
          Row("blarr2", "a", "b"))
      }
    }
  }

  test("Insert ArrayType.containsNull == false") {
    val schema = StructType(Seq(
      StructField("a", ArrayType(StringType, containsNull = false))))
    val rowRDD = spark.sparkContext.parallelize((1 to 100).map(i => Row(Seq(s"value$i"))))
    val df = spark.createDataFrame(rowRDD, schema)
    df.createOrReplaceTempView("tableWithArrayValue")
    sql("CREATE TABLE hiveTableWithArrayValue(a Array <STRING>)")
    sql("INSERT OVERWRITE TABLE hiveTableWithArrayValue SELECT a FROM tableWithArrayValue")

    checkAnswer(
      sql("SELECT * FROM hiveTableWithArrayValue"),
      rowRDD.collect().toSeq)

    sql("DROP TABLE hiveTableWithArrayValue")
  }

  test("Insert MapType.valueContainsNull == false") {
    val schema = StructType(Seq(
      StructField("m", MapType(StringType, StringType, valueContainsNull = false))))
    val rowRDD = spark.sparkContext.parallelize(
      (1 to 100).map(i => Row(Map(s"key$i" -> s"value$i"))))
    val df = spark.createDataFrame(rowRDD, schema)
    df.createOrReplaceTempView("tableWithMapValue")
    sql("CREATE TABLE hiveTableWithMapValue(m Map <STRING, STRING>)")
    sql("INSERT OVERWRITE TABLE hiveTableWithMapValue SELECT m FROM tableWithMapValue")

    checkAnswer(
      sql("SELECT * FROM hiveTableWithMapValue"),
      rowRDD.collect().toSeq)

    sql("DROP TABLE hiveTableWithMapValue")
  }

  test("Insert StructType.fields.exists(_.nullable == false)") {
    val schema = StructType(Seq(
      StructField("s", StructType(Seq(StructField("f", StringType, nullable = false))))))
    val rowRDD = spark.sparkContext.parallelize(
      (1 to 100).map(i => Row(Row(s"value$i"))))
    val df = spark.createDataFrame(rowRDD, schema)
    df.createOrReplaceTempView("tableWithStructValue")
    sql("CREATE TABLE hiveTableWithStructValue(s Struct <f: STRING>)")
    sql("INSERT OVERWRITE TABLE hiveTableWithStructValue SELECT s FROM tableWithStructValue")

    checkAnswer(
      sql("SELECT * FROM hiveTableWithStructValue"),
      rowRDD.collect().toSeq)

    sql("DROP TABLE hiveTableWithStructValue")
  }

  test("Reject partitioning that does not match table") {
    withSQLConf(("hive.exec.dynamic.partition.mode", "nonstrict")) {
      sql("CREATE TABLE partitioned (id bigint, data string) PARTITIONED BY (part string)")
      val data = (1 to 10).map(i => (i, s"data-$i", if ((i % 2) == 0) "even" else "odd"))
          .toDF("id", "data", "part")

      intercept[AnalysisException] {
        // cannot partition by 2 fields when there is only one in the table definition
        data.write.partitionBy("part", "data").insertInto("partitioned")
      }
    }
  }

  test("Test partition mode = strict") {
    withSQLConf(("hive.exec.dynamic.partition.mode", "strict")) {
      sql("CREATE TABLE partitioned (id bigint, data string) PARTITIONED BY (part string)")
      val data = (1 to 10).map(i => (i, s"data-$i", if ((i % 2) == 0) "even" else "odd"))
          .toDF("id", "data", "part")

      intercept[SparkException] {
        data.write.insertInto("partitioned")
      }
    }
  }

  test("Detect table partitioning") {
    withSQLConf(("hive.exec.dynamic.partition.mode", "nonstrict")) {
      sql("CREATE TABLE source (id bigint, data string, part string)")
      val data = (1 to 10).map(i => (i, s"data-$i", if ((i % 2) == 0) "even" else "odd")).toDF()

      data.write.insertInto("source")
      checkAnswer(sql("SELECT * FROM source"), data.collect().toSeq)

      sql("CREATE TABLE partitioned (id bigint, data string) PARTITIONED BY (part string)")
      // this will pick up the output partitioning from the table definition
      spark.table("source").write.insertInto("partitioned")

      checkAnswer(sql("SELECT * FROM partitioned"), data.collect().toSeq)
    }
  }

  private def testPartitionedHiveSerDeTable(testName: String)(f: String => Unit): Unit = {
    test(s"Hive SerDe table - $testName") {
      val hiveTable = "hive_table"

      withTable(hiveTable) {
        withSQLConf("hive.exec.dynamic.partition.mode" -> "nonstrict") {
          sql(s"CREATE TABLE $hiveTable (a INT) PARTITIONED BY (b INT, c INT) STORED AS TEXTFILE")
          f(hiveTable)
        }
      }
    }
  }

  private def testPartitionedDataSourceTable(testName: String)(f: String => Unit): Unit = {
    test(s"Data source table - $testName") {
      val dsTable = "ds_table"

      withTable(dsTable) {
        sql(s"CREATE TABLE $dsTable (a INT, b INT, c INT) USING PARQUET PARTITIONED BY (b, c)")
        f(dsTable)
      }
    }
  }

  private def testPartitionedTable(testName: String)(f: String => Unit): Unit = {
    testPartitionedHiveSerDeTable(testName)(f)
    testPartitionedDataSourceTable(testName)(f)
  }

  testPartitionedTable("partitionBy() can't be used together with insertInto()") { tableName =>
    val cause = intercept[AnalysisException] {
      Seq((1, 2, 3)).toDF("a", "b", "c").write.partitionBy("b", "c").insertInto(tableName)
    }

    assert(cause.getMessage.contains("insertInto() can't be used together with partitionBy()."))
  }

  test("InsertIntoTable#resolved should include dynamic partitions") {
    withSQLConf(("hive.exec.dynamic.partition.mode", "nonstrict")) {
      sql("CREATE TABLE partitioned (id bigint, data string) PARTITIONED BY (part string)")
      val data = (1 to 10).map(i => (i.toLong, s"data-$i")).toDF("id", "data")

      val logical = InsertIntoTable(spark.table("partitioned").logicalPlan,
        Map("part" -> None), data.logicalPlan, overwrite = false, ifNotExists = false)
      assert(!logical.resolved, "Should not resolve: missing partition data")
    }
  }
}
