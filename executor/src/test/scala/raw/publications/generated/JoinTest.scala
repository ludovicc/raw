package raw.publications.generated
import org.apache.spark.rdd.RDD
import raw.{rawQueryAnnotation, RawQuery}
import raw.publications._
import com.google.common.collect.ImmutableMultiset
import scala.collection.JavaConversions


@rawQueryAnnotation
class Join0Query(val authors: RDD[Author], val publications: RDD[Publication]) extends RawQuery {
  val oql = """
    select distinct a.year, a.name as n1, b.name as n2
        from authors a, authors b
        where a.year = b.year and a.name != b.name and a.name < b.name and a.year > 1992
  """
}

@rawQueryAnnotation
class Join1Query(val authors: RDD[Author], val publications: RDD[Publication]) extends RawQuery {
  val oql = """
    select distinct a.year, struct(name:a.name, title:a.title) as p1, struct(name:b.name, title:b.title) as p2
        from authors a, authors b
        where a.year = b.year and a.name != b.name and a.name < b.name and a.year > 1992
  """
}

@rawQueryAnnotation
class Join2Query(val authors: RDD[Author], val publications: RDD[Publication]) extends RawQuery {
  val oql = """
    select distinct a.year, set(struct(name:a.name, title:a.title), struct(name:b.name, title:b.title))
        from authors a, authors b
        where a.year = b.year and a.name != b.name and a.year > 1992
  """
}

@rawQueryAnnotation
class Join3Query(val authors: RDD[Author], val publications: RDD[Publication]) extends RawQuery {
  val oql = """
    select * from (
        select article: P,
               (select A
                from P.authors a,
                     authors A
                where A.name = a
                      and A.title = "professor") as profs
        from publications P
        where "particle detectors" in P.controlledterms
              and "Stricker, D.A." in P.authors
        ) T having count(T.profs) > 2
  """
}


class JoinTest extends AbstractSparkPublicationsTest {

  test("Join0") {
    val result = new Join0Query(authorsRDD, publicationsRDD).computeResult
    val actual = convertToString(result)
    
    val expected = convertExpected("""
    [n1: Johnson, R.T., n2: Martoff, C.J., year: 1994]
    [n1: Johnson, R.T., n2: Nakagawa, H., year: 1994]
    [n1: Martoff, C.J., n2: Nakagawa, H., year: 1994]
    [n1: Wang, Hairong, n2: Zhuangde Jiang, year: 1993]
    """)
    assert(actual === expected, s"\nActual: $actual\nExpected: $expected")
  }

  test("Join1") {
    val result = new Join1Query(authorsRDD, publicationsRDD).computeResult
    val actual = convertToString(result)
    
    val expected = convertExpected("""
    [p1: [name: Johnson, R.T., title: professor], p2: [name: Martoff, C.J., title: assistant professor], year: 1994]
    [p1: [name: Johnson, R.T., title: professor], p2: [name: Nakagawa, H., title: assistant professor], year: 1994]
    [p1: [name: Martoff, C.J., title: assistant professor], p2: [name: Nakagawa, H., title: assistant professor], year: 1994]
    [p1: [name: Wang, Hairong, title: professor], p2: [name: Zhuangde Jiang, title: professor], year: 1993]
    """)
    assert(actual === expected, s"\nActual: $actual\nExpected: $expected")
  }

  test("Join2") {
    val result = new Join2Query(authorsRDD, publicationsRDD).computeResult
    val actual = convertToString(result)
    val expected = convertExpected("""
    [_X0: [[name: Johnson, R.T., title: professor], [name: Martoff, C.J., title: assistant professor]], year: 1994]
    [_X0: [[name: Johnson, R.T., title: professor], [name: Nakagawa, H., title: assistant professor]], year: 1994]
    [_X0: [[name: Martoff, C.J., title: assistant professor], [name: Nakagawa, H., title: assistant professor]], year: 1994]
    [_X0: [[name: Wang, Hairong, title: professor], [name: Zhuangde Jiang, title: professor]], year: 1993]
    """)
    assert(actual === expected, s"\nActual: $actual\nExpected: $expected")
  }

  test("Join3") {
    val result = new Join3Query(authorsRDD, publicationsRDD).computeResult
    val actual = convertToString(result)
    val expected = convertExpected("""
    [article: [affiliations: [11911 Parklawn Drive, Rockville, M.D, USA, Dept. of Phys., Stanford Univ., CA, USA], authors: [Bland, R.W., Johnson, R.T., Katase, A., Stricker, D.A., Sun, Guoliang], controlledterms: [magnetic levitation, neutrino detection and measurement, particle detectors, superconducting thin films], title: Influence of poling on far-infrared response of lead zirconate titanate ceramics], profs: [[name: Bland, R.W., title: professor, year: 1984], [name: Johnson, R.T., title: professor, year: 1994], [name: Sun, Guoliang, title: professor, year: 1987]]]
    [article: [affiliations: [Dept. of Phys. & Astron., San Francisco State Univ., CA, USA, Dept. of Phys., Stanford Univ., CA, USA], authors: [Anderson, C.C., Ertan, H.B., Neuhauser, B., Stricker, D.A., Sun, Guoliang, Zhuangde Jiang], controlledterms: [particle detectors, reluctance motors, stepping motors, superconducting junction devices, superconducting thin films, superconductive tunnelling], title: Numerical Analysis and Optimization of Lobe-Type Magnetic Shielding in a 334 MVA Single-Phase Auto-Transformer], profs: [[name: Neuhauser, B., title: professor, year: 1973], [name: Sun, Guoliang, title: professor, year: 1987], [name: Zhuangde Jiang, title: professor, year: 1993]]]
    [article: [affiliations: [Dept. of Phys. & Astron., San Francisco State Univ., CA, USA, Dept. of Phys., Stanford Univ., CA, USA], authors: [Gallion, P., Kokorin, V.V., Sarigiannidou, E., Stricker, D.A., Tozoni, O.V., Zhuangde Jiang], controlledterms: [grain size, particle detectors, scanning electron microscope examination of materials, superconducting thin films, titanium], title: High accuracy Raman measurements using the Stokes and anti-Stokes lines], profs: [[name: Kokorin, V.V., title: professor, year: 1965], [name: Tozoni, O.V., title: professor, year: 1976], [name: Zhuangde Jiang, title: professor, year: 1993]]]
    [article: [affiliations: [Hewlett-Packard Lab., Palo Alto, CA, USA], authors: [Dickson, S.C., Kokorin, V.V., Monroy, E., Sarigiannidou, E., Stricker, D.A., Tickle, R.], controlledterms: [X-ray detection and measurement, magnetic levitation, particle detectors, scanning electron microscope examination of materials, stepping motors, superconducting junction devices, superconductive tunnelling, torque], title: Improvement of the stability of high-voltage generators for perturbations within a frequency bandwidth of 0.03--1000 Hz], profs: [[name: Dickson, S.C., title: professor, year: 1971], [name: Kokorin, V.V., title: professor, year: 1965], [name: Tickle, R., title: professor, year: 1972]]]
    """)
    assert(actual === expected, s"\nActual: $actual\nExpected: $expected")
  }

}
