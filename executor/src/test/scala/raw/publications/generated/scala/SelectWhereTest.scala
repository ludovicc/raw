package raw.publications.generated.scala

import raw._
import raw.datasets.publications.Publications

class SelectWhereTest extends AbstractScalaTest(Publications.Scala.publications) {

  test("SelectWhere0") {
    val oql = """
      select a from authors a where a.title = "PhD"
    """
    val result = queryCompiler.compileOQL(oql, accessPaths).computeResult
    assertJsonEqual("publications", "SelectWhere0", result)
  }

  test("SelectWhere1") {
    val oql = """
      select a from authors a
            where a.year = 1973 or a.year = 1975
    """
    val result = queryCompiler.compileOQL(oql, accessPaths).computeResult
    assertJsonEqual("publications", "SelectWhere1", result)
  }

  test("SelectWhere2") {
    val oql = """
      select P from publications P
            where "particle detectors" in P.controlledterms
            and "Hewlett-Packard Lab., Palo Alto, CA, USA" in P.affiliations
            and "Sarigiannidou, E." in P.authors
    """
    val result = queryCompiler.compileOQL(oql, accessPaths).computeResult
    assertJsonEqual("publications", "SelectWhere2", result)
  }

  test("SelectWhere3") {
    val oql = """
      select P from publications P
where "particle detectors" in P.controlledterms
and "elemental semiconductors" in P.controlledterms
and "magnetic levitation" in P.controlledterms
and "titanium" in P.controlledterms
and "torque" in P.controlledterms
    """
    val result = queryCompiler.compileOQL(oql, accessPaths).computeResult
    assertJsonEqual("publications", "SelectWhere3", result)
  }

}