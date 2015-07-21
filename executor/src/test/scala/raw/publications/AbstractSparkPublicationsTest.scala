package raw.publications

import com.typesafe.scalalogging.StrictLogging
import org.apache.spark.rdd.RDD
import org.scalatest.{BeforeAndAfterAll, FunSuite}
import raw.SharedSparkContext
import raw.datasets.publications.{Author, Publication, Publications}
import raw.datasets.{AccessPath, Dataset}
import raw.executionserver._

import scala.reflect._

abstract class AbstractSparkPublicationsTest
  extends FunSuite
  with StrictLogging
  with BeforeAndAfterAll
  with SharedSparkContext
  with ResultConverter {

  var pubsDS: List[Dataset[_]] = _
  var accessPaths: List[AccessPath[_]] = _
  var authorsRDD: RDD[Author] = _
  var publicationsRDD: RDD[Publication] = _

  override def beforeAll() {
    super.beforeAll()
    try {
      pubsDS = Publications.loadPublications(sc)
      authorsRDD = pubsDS.filter(ds => ds.accessPath.tag == classTag[Author]).head.rdd.asInstanceOf[RDD[Author]]
      publicationsRDD = pubsDS.filter(ds => ds.accessPath.tag == classTag[Publication]).head.rdd.asInstanceOf[RDD[Publication]]
      accessPaths = pubsDS.map(p => p.accessPath)
    } catch {
      case ex: Exception =>
        super.afterAll()
        throw ex
    }
  }
}
