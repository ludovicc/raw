package raw.publications.generated.qrawl.spark

import raw._

class SelectTest extends AbstractSparkTest {

  test("Select0") {
    val queryLanguage = QueryLanguages("qrawl")
    val query = """
      select distinct a.name AS name, a.title As title, a.year aS year from authors a where a.year = 1973
    """
    val result = queryCompiler.compile(queryLanguage, query, scanners).computeResult
    assertJsonEqual("publications", "Select0", result)
  }

  test("Select1") {
    val queryLanguage = QueryLanguages("qrawl")
    val query = """
      select distinct a.name as nom, a.title as titre, a.year as annee from authors a where a.year = 1973
    """
    val result = queryCompiler.compile(queryLanguage, query, scanners).computeResult
    assertJsonEqual("publications", "Select1", result)
  }

  test("Select2") {
    val queryLanguage = QueryLanguages("qrawl")
    val query = """
      select a.title from authors a where a.year = 1959
    """
    val result = queryCompiler.compile(queryLanguage, query, scanners).computeResult
    assertJsonEqual("publications", "Select2", result)
  }

  test("Select3") {
    val queryLanguage = QueryLanguages("qrawl")
    val query = """
      select distinct a.title from authors a where a.year = 1959
    """
    val result = queryCompiler.compile(queryLanguage, query, scanners).computeResult
    assertJsonEqual("publications", "Select3", result)
  }

}
