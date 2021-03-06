package raw.storage

import java.io.InputStream
import java.nio.file.{Files, Path}
import java.util
import java.util.concurrent.TimeUnit

import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._
import com.amazonaws.util.IOUtils
import com.google.common.base.Stopwatch
import com.typesafe.scalalogging.StrictLogging
import raw.executor.RawServer
import raw.mdcatalog._
import raw.utils.{FileTypes, RawUtils}

import scala.collection.JavaConversions

class RawS3Object(val key: String, s3: AmazonS3Client) extends RawResource {
  override def openInputStream(): InputStream = {
    val obj: S3Object = s3.getObject(S3StorageManager.bucket, key)
    obj.getObjectContent
  }

  val fileName: String = {
    val idx = key.lastIndexOf("/")
    key.substring(idx + 1)
  }

  override def toString = s"RawS3Object[${key}]"
}

object S3StorageManager {
  final val bucket = "raw-labs-executor"

  /** The ObjectListing instance returned by the Java S3 API uses Java collections, which are cumbersome to use from 
    * Scala. This class provides a thin adapter exposing Scala collections. Additionally, it also exposes a hierarchical
    * view of the keyspace, that is, it extracts from the keynames the parts corresponding to the "directory" and the "file".
    * Recall that S3 does not support hierarchies, so these have to be emulated with keys in the form 
    * "root/dirA/dirB/file.json".
    *
    * @param delegate The object returned by the S3 API call.
    * @param summaries Description of each subelement of this directory
    * @param path The path of this directory.
    * @param commonPrefixes The full path of the subdirectories.
    * @param subdirectories The subdirectories within this directory. Only the last path component, so the relative name.
    * @param files The files contained in this directory.
    */
  case class ScalaObjectListing(delegate: ObjectListing, summaries: List[S3ObjectSummary], path: String, commonPrefixes: List[String], subdirectories: List[String], files: List[String])

  private[this] def toScala[T](col: util.Collection[T]): List[T] = {
    JavaConversions.collectionAsScalaIterable(col).toList
  }

  object ScalaObjectListing {
    def apply(delegate: ObjectListing) = {
      val summaries: List[S3ObjectSummary] = toScala(delegate.getObjectSummaries)
      val path: String = if (delegate.getPrefix == null) "" else delegate.getPrefix
      val commonPrefixes: List[String] = toScala(delegate.getCommonPrefixes)
      val directories: List[String] = commonPrefixes.map(s => s.substring(path.length, s.length - 1))
      val files: List[String] = summaries.map(s => s.getKey.substring(delegate.getPrefix.length))
      new ScalaObjectListing(delegate, summaries, path, commonPrefixes, directories, files)
    }
  }

  val s3 = new AmazonS3Client()
  s3.setRegion(Region.getRegion(Regions.EU_WEST_1))
}

/* TODO: Currently we are caching in memory the schemas read from S3 at startup. This will not work if there are
 * multiple RawServer instances, so we need to either disabled caching completely and always access the schema information
 * from S3 or implement some mechanism to refresh the cache (S3 notifications?)
 */
class S3StorageManager(val stageDirectory: Path) extends StorageManager with StrictLogging {

  import S3StorageManager._

  logger.info(s"Staging directory: $stageDirectory")
  RawUtils.createDirectory(stageDirectory)

  override def registerSchema(schemaName: String, stagingDirectory: Path, rawUser: String): Unit = {
    logger.info(s"Registering schema: $schemaName, stageDir: $stagingDirectory, user: $rawUser")

    assert(Files.isDirectory(stagingDirectory))
    val files = JavaConversions.asScalaIterator(Files.list(stagingDirectory).iterator()).toList
    val prefixWithDirName = rawUser + "/" + schemaName
    // TODO: check if it already exists
    files.foreach(f => uploadFile(bucket, prefixWithDirName, f))
    // TODO: Load schema and properties from local files instead of hitting S3, avoids a few additional GETs
    val schema = loadSchemaFromStorage(rawUser, schemaName)
    MDCatalog.register(rawUser, schemaName, schema)
  }

  override def listUserSchemasFromStorage(user: String): List[String] = {
    listContents(user + "/").subdirectories
  }

  override def listUsersFromStorage(): List[String] = {
    listContents("").subdirectories
  }


  override def loadSchemaFromStorage(user: String, schemaName: String): DataSource = {
    val schemaDir = s"$user/$schemaName/"
    logger.info(s"Loading schema: $schemaName, key: $schemaDir")
    val contents: ScalaObjectListing = listContents(schemaDir)
    val summaries = contents.summaries.map(s => s"${s.getKey} (${s.getSize})").mkString(", ")
    logger.info(s"Found files: $summaries")

    val list: List[String] = contents.files.filter(s => s.startsWith(schemaName + "."))
    assert(list.size == 1, s"Expected one data file for schema: $schemaName in key: $schemaDir. Found: $list.")

    val schemaFileName = list.head
    val dataFileKey = schemaDir + schemaFileName
    val propertiesFileKey = schemaDir + "properties.json"
    // Alternatively, download contents here instead of passing the reference. Only problematic if schemas become large.
    val schemaFileKey = schemaDir + "schema.xml"
    val propertiesString = getObjectAsString(propertiesFileKey)

    val properties: util.Map[String, Object] = jsonMapper.readValue(propertiesString, classOf[java.util.Map[String, Object]])
    val fileType = FileTypes.inferFileType(schemaFileName)
    val format = fileType match {
      case FileTypes.Json => JSON()
      case FileTypes.Csv => {
        val schemaProperties = DataSource.newCsvDataSourceProperties(properties)
        CSV(schemaProperties)
      }
      case FileTypes.Text => Text()
    }

    val attributeOrder = format match {
      case CSV(schemaProperties) => schemaProperties.fieldNames
      case _ => None
    }

    // Get the schema file
    val schemaS3File = s3.getObject(S3StorageManager.bucket, schemaFileKey)
    val tipe = SchemaParser(schemaS3File.getObjectContent, attributeOrder)

    // Get the schema file information
    val location = S3File(dataFileKey)
    val size: Option[Long] = {
      // tODO: Or contentLength?
      val dataS3Object = s3.getObjectMetadata(S3StorageManager.bucket, schemaFileKey)
      val l = dataS3Object.getInstanceLength
      logger.info(s"File: ${schemaFileKey}, Instance length: $l")
      if (l == 0) None else Some(l)
    }
    val accessPaths: Set[AccessPath] = Set(SequentialAccessPath(location, format, size))
    DataSource(schemaName, tipe, accessPaths)
  }

  private[this] def listContents(prefix: String): ScalaObjectListing = {
    val req = new ListObjectsRequest()
      .withBucketName(bucket)
      .withPrefix(prefix)
      .withDelimiter("/")
    logger.info(s"Listing contents of: $prefix")
    val objs: ScalaObjectListing = ScalaObjectListing(s3.listObjects(req))
    objs
  }

  private[this] def getObjectAsString(key: String): String = {
    logger.info(s"Retrieving: $key")
    val obj: S3Object = s3.getObject(bucket, key)
    val is: S3ObjectInputStream = obj.getObjectContent
    try {
      IOUtils.toString(is)
    } finally {
      obj.close()
    }
  }

  private[this] def putDirectory(prefix: String, directory: Path) = {
    assert(Files.isDirectory(directory))
    val files = JavaConversions.asScalaIterator(Files.list(directory).iterator()).toList
    val prefixWithDirName = prefix + "/" + directory.getFileName
    files.foreach(f => uploadFile(bucket, prefixWithDirName, f))
  }

  private[this] def uploadFile(bucket: String, prefix: String, p: Path) = {
    val keyName = prefix + "/" + p.getFileName
    logger.info(s"Uploading $p to $keyName. ${p.toFile.length()} bytes")
    val start = Stopwatch.createStarted()
    s3.putObject(new PutObjectRequest(bucket, keyName, p.toFile))
    logger.info(s"Uploaded file in: ${start.elapsed(TimeUnit.MILLISECONDS)}ms")
  }
}
