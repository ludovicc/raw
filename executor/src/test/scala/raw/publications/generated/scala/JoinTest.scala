package raw.publications.generated.scala

import raw._
import raw.datasets.publications.Publications

class JoinTest extends AbstractScalaTest(Publications.Scala.publicationsSmall) {

  test("Join0") {
    val oql = """
      select distinct a.year, a.name as n1, b.name as n2
from authors a, authors b
where a.year = b.year and a.name != b.name and a.name < b.name
    """
    val result = queryCompiler.compileOQL(oql, accessPaths).computeResult
    assertJsonEqual("publicationsSmall", "Join0", result)
  }

  test("Join1") {
    val oql = """
      select distinct a.year, struct(name:a.name, title:a.title) as p1, struct(name:b.name, title:b.title) as p2
from authors a, authors b
where a.year = b.year and a.name != b.name and a.name < b.name
    """
    val result = queryCompiler.compileOQL(oql, accessPaths).computeResult
    assertJsonEqual("publicationsSmall", "Join1", result)
  }

  test("Join2") {
    val oql = """
      select P as publication,
      (select A
       from P.authors a, authors A
       where A.name = a and A.title = "professor") as profs
from publications P
    """
    val result = queryCompiler.compileOQL(oql, accessPaths).computeResult
    assertJsonEqual("publicationsSmall", "Join2", result)
  }

  test("Join3") {
    val oql = """
      select article: P,
       (select A
        from P.authors a, authors A
        where A.name = a
              and A.title = "professor") as profs
from publications P
where "particle detectors" in P.controlledterms
    """
    val result = queryCompiler.compileOQL(oql, accessPaths).computeResult
    assertJsonEqual("publicationsSmall", "Join3", result)
  }

}