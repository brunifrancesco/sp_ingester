package ingester

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

object Main extends App {
  val conf =
    new SparkConf()
      .setMaster("local[1]")
      .set("spark.executor.instances", "1")
      .set("spark.executor.cores", "1")
      .setAppName("Spark Tiler")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", "geotrellis.spark.io.kryo.KryoRegistrator")

  implicit val sc = new SparkContext(conf)
  try {
    println("Starting...")
    val inputRdd: RDD[(ProjectedExtent, MultibandTile)] =
      sc.hadoopMultibandGeoTiffRDD("/Users/boss/pk/shapefiles/retiled")

    println(s"Partitions: ${inputRdd.partitions.length}")

    val (_, rasterMetaData) =
      TileLayerMetadata.fromRdd(inputRdd, FloatingLayoutScheme(512))

    val tiled: RDD[(SpatialKey, MultibandTile)] =
      inputRdd
        .tileToLayout(rasterMetaData.cellType, rasterMetaData.layout, Bilinear)
        .repartition(200)
        .persist(StorageLevel.MEMORY_AND_DISK_SER)

    val layoutScheme = ZoomedLayoutScheme(WebMercator, tileSize = 256, resolutionThreshold = 0.00001)

    // We need to reproject the tiles to WebMercator
    val (_, reprojected): (Int, RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]]) =
          MultibandTileLayerRDD(tiled, rasterMetaData)
       .reproject(WebMercator, layoutScheme, Bilinear)
    reprojected.persist(StorageLevel.MEMORY_AND_DISK_SER)

    // Create the attributes store that will tell us information about our catalog.
    val attributeStore = FileAttributeStore("/tmp/catalog")

    // Create the writer that we will use to store the tiles in the local catalog.
    val writer = FileLayerWriter(attributeStore)

    // Pyramiding up the zoom levels, write our tiles out to the local file system.
    Pyramid.upLevels(reprojected, layoutScheme, 10, Bilinear) { (rdd, z) =>
      println(s"Processing ${z}")
      val layerId = LayerId("spinua", z)
      // If the layer exists already, delete it out before writing
      if(attributeStore.layerExists(layerId)) {
        new FileLayerManager(attributeStore).delete(layerId)
      }
      writer.write(layerId, rdd, ZCurveKeyIndexMethod)
    }

  } finally {
    sc.stop()
  }
}
