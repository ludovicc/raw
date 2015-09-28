package raw.executor

import java.nio.file.Path

import com.typesafe.scalalogging.StrictLogging

class RawServer(storageDir: Path) extends StrictLogging {

  val storageManager = new StorageManager(storageDir)

  def registerSchema(schemaName: String, dataDir: String, user: String): Unit = {
    storageManager.registerSchema(schemaName, dataDir, user)
  }

  def doQuery(query: String, rawUser: String): String = {
    // If the query string is too big (threshold somewhere between 13K and 96K), the compilation will fail with
    // an IllegalArgumentException: null. The query plans received from the parsing server include large quantities
    // of whitespace which are used for indentation. We remove them as a workaround to the limit of the string size.
    // But this can still fail for large enough plans, so check if spliting the lines prevents this error.
    val cleanedQuery = query.trim.replaceAll("\\s+", " ")

    val schemas: Seq[String] = storageManager.listUserSchemas(rawUser)
    logger.info("Found schemas: " + schemas.mkString(", "))
    val scanners: Seq[RawScanner[_]] = schemas.map(name => storageManager.getScanner(rawUser, name))
    CodeGenerator.query(cleanedQuery, scanners)
  }
}