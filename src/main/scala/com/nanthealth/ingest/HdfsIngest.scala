package com.nanthealth.ingest

import java.io.InputStreamReader
import java.sql.{Connection, DriverManager}
import java.util.Properties

import org.apache.hadoop.fs._
import org.apache.log4j.LogManager
import org.apache.spark.sql.functions.callUDF
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.{DataFrame, SaveMode}
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable

object HdfsIngest {
  private val logger = LogManager.getLogger(getClass.getName)

  def main(args: Array[String]): Unit = {

    if (args.length < 2) {
      println("Config file and Database name parameters are required as parameters...")
      System.exit(1)
    }

    val prop = new Properties()
    val hive_database = args(1).toLowerCase()

    val filepath = args(0)
    val pt = new Path(filepath)
    val hdfsConf = new org.apache.hadoop.conf.Configuration()
    val filesystem = FileSystem.get(hdfsConf)
    val fis = new InputStreamReader(filesystem.open(pt))
    prop.load(fis)

    val (user, url, password, driver) =
      try {
        (
          prop.getProperty("user"),
          prop.getProperty("url") + hive_database,
          prop.getProperty("password"),
          prop.getProperty("driver")
        )
      } catch {
        case e: Exception => e.printStackTrace(); logger.error(e); sys.exit(1);
      }

    val conf = new SparkConf().setAppName("DataLake Data Ingestion")
    val sc = new SparkContext(conf)
    val hiveContext = new HiveContext(sc)

    var connection: Connection = null
    var options = mutable.Map[String, String]()
    options += ("url" -> url)
    options += ("user" -> user)
    options += ("password" -> password)
    options += ("driver" -> driver)

    hiveContext.udf.register("CleanseCol", cleanCol(_: String))
    try {
      Class.forName(driver)
      connection = DriverManager.getConnection(url, user, password)

      val statement = connection.createStatement()
      val resultSet = statement.executeQuery("SELECT name FROM sys.tables")
      while (resultSet.next()) {
        val table = resultSet.getString("name")

        options += ("dbtable" -> table)

        val df = hiveContext.read.format("jdbc").options(options).load

        val dft = transform(df)

        dft.write
          .mode(SaveMode.Overwrite)
          .saveAsTable(hive_database + "." + table.toLowerCase())
      }
    } catch {
      case e: Throwable => e.printStackTrace(); logger.error(e);
    }

    connection.close()

    sc.stop

  } //:~ main


  def cleanCol(content: String): String = {
    var col: String = null
    if (content != null) {
      col = content.replace("\n", " ").replace("\r", " ")
    }
    col
  }


  def transform(input: DataFrame): DataFrame = {
    var df = input
    for (tup <- df.dtypes) {
      if (tup._2.equalsIgnoreCase("StringType")) {
        val columnName = tup._1
        df = df.withColumn(columnName, callUDF("CleanseCol", df.col(columnName)))
      }
    }
    df.toDF
  } //:~ transform

}
