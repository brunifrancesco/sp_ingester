package ingester

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import geotrellis.raster._
import geotrellis.raster.resample._
import geotrellis.proj4._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.file._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.index._
import geotrellis.spark.pyramid._
import geotrellis.spark.tiling._
import geotrellis.vector._
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.storage.StorageLevel

import scala.io.Source

object Main {
  def main(args: Array[String]): Unit = {
    val json = Source.fromFile(args(0))
    val mapper = new ObjectMapper() with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    val parsedJson = mapper.readValue[Map[String, Object]](json.reader())
    val masterUrl = parsedJson.getOrElse("spark.master", "local[*]").toString
    val appName = parsedJson.getOrElse("spark.appName", "Spark Ingester").toString
    val generalSettings =
      parsedJson.getOrElse("spark.generalSettings", Map[String, String]())
        .asInstanceOf[Map[String, String]]
    val ingesterSettings: Map[String, String] = parsedJson.getOrElse("ingestion", Map[String, String]())
      .asInstanceOf[Map[String, String]]

    val conf =
      new SparkConf()
        .setMaster(masterUrl)
        .setAppName(appName)

    generalSettings.foreach((setting) =>
      conf.set(setting._1, setting._2)
    )

    implicit val sc = new SparkContext(conf)
    try {

      val inputRdd: RDD[(ProjectedExtent, MultibandTile)] =
        sc.hadoopMultibandGeoTiffRDD(ingesterSettings.getOrElse("inputFolder", "/home/bruni/rasters")).mapValues(_.withNoData(Some(-999999)))

      println(s"Partitions: ${inputRdd.partitions.length}")

      val (_, rasterMetaData) =
        TileLayerMetadata.fromRdd(inputRdd, FloatingLayoutScheme(ingesterSettings.getOrElse("tileSize", 256).asInstanceOf[Int]))

      val tiled: RDD[(SpatialKey, MultibandTile)] =
        inputRdd
          .tileToLayout(rasterMetaData.cellType, rasterMetaData.layout, Bilinear)
          .repartition(inputRdd.partitions.length / 100)
          .persist(StorageLevel.MEMORY_AND_DISK_SER)

      val layoutScheme = ZoomedLayoutScheme(
        WebMercator,
        tileSize = ingesterSettings.getOrElse("tileSize", 256).asInstanceOf[Int],
        resolutionThreshold = ingesterSettings.getOrElse("resolutionTreshold", 0.1).asInstanceOf[Double]
      )

      // We need to reproject the tiles to WebMercator
      val (_, reprojected): (Int, RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]]) =
        MultibandTileLayerRDD(tiled, rasterMetaData)
          .reproject(WebMercator, layoutScheme, Bilinear)

      // Create the attributes store that will tell us information about our catalog.
      val attributeStore = FileAttributeStore(ingesterSettings.getOrElse("outputCatalog", "/home/bruni/catalog"))

      // Create the writer that we will use to store the tiles in the local catalog.
      val writer = FileLayerWriter(attributeStore)

      // Pyramiding up the zoom levels, write our tiles out to the local file system.
      Pyramid.upLevels(reprojected, layoutScheme, 22, Bilinear) { (rdd, z) =>
        val layerId = LayerId(ingesterSettings.getOrElse("layerName", "SP"), z)
        // If the layer exists already, delete it out before writing
        if (attributeStore.layerExists(layerId)) {
          new FileLayerManager(attributeStore).delete(layerId)
        }
        writer.write(layerId, rdd, ZCurveKeyIndexMethod)
      }

    } finally {
      sc.stop()
    }
  }


}
