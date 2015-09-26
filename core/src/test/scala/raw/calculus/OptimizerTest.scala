package raw
package calculus

class OptimizerTest extends FunTest {

  def process(w: World, q: String) = {
    val ast = parse(q)
    val t = new Calculus.Calculus(ast)
    val analyzer = new calculus.SemanticAnalyzer(t, w)
    logger.debug(calculus.CalculusPrettyPrinter(ast))
    val t_q = analyzer.tipe(t.root)
    analyzer.errors.foreach(err => logger.error(err.toString))
    assert(analyzer.errors.length === 0)
    logger.debug(s"t_q $t_q")

    val algebra = Optimizer(t, w)
    logger.debug(s"algebra\n${CalculusPrettyPrinter(algebra.root)}")
    val analyzer2 = new calculus.SemanticAnalyzer(algebra, w)
    analyzer2.errors.foreach(err => logger.error(err.toString))
    assert(analyzer2.errors.length === 0)
    val t_alg = analyzer2.tipe(algebra.root)
    compare(TypesPrettyPrinter(t_alg), TypesPrettyPrinter(t_q))
    algebra
  }

  def check(query: String, world: World, algebra: String) = compare(CalculusPrettyPrinter(process(world, query).root), algebra)

  test("exp block is created because of to_bag and to_set found in the tree") {
    check("select A.title from A in (select distinct A from A in authors)", TestWorlds.publications,
      """{ $32 := to_bag(to_set(authors)); reduce(bag, $14 <- $32, $14.title) }""")
  }

  test("publications: select A from A in authors") {
    check("select A from A in authors", TestWorlds.publications, """authors""")
  }

  test("publications: select A from A in authors where A.year = 1990") {
    check("select A from A in authors where A.year = 1990", TestWorlds.publications, """filter($0 <- authors, $0.year = 1990)""")
  }

  test("publications: should do an efficient group by #0") {
    check("select distinct partition from A in authors where A.year = 1992 group by A.title", TestWorlds.publications,
      """to_set(reduce(bag, ($25, $92) <- nest(bag, $25 <- filter($25 <- authors, $25.year = 1992), $25.title, true, $25), $92))""")
  }

  test("publications: should do an efficient group by #1") {
    check("select distinct count(partition) from A in authors where A.year = 1992 group by A.title", TestWorlds.publications,
      """to_set(reduce(bag, ($40, $151) <- nest(sum, $40 <- filter($40 <- authors, $40.year = 1992), $40.title, true, 1), $151))""")
  }

  test("publications: should do an efficient group by #2") {
    check("select distinct (select x.name from partition x) from A in authors where A.year = 1992 group by A.title", TestWorlds.publications,
      """to_set(reduce(
                  bag,
                  ($39, $137) <- nest(bag, $39 <- filter($39 <- authors, $39.year = 1992), $39.title, true, $39.name),
                  $137))""")
  }

  test("publications: should do an efficient group by #3") {
    // here the project of $38.title is rewritten into $38 since we nest on that now
    // TODO could get rid of the reduce! See test below
    check("select distinct A.title, (select x.year from partition x) from A in authors group by A.title", TestWorlds.publications,
      """to_set(reduce(bag, ($38, $122) <- nest(bag, $38 <- authors, $38.title, true, $38.year), (_1: $38, _2: $122)))""")
  }

  test("publications: should do an efficient group by #3++") {
    // here the project of $38.title is rewritten into $38 since we nest on that now
    // and TODO reduce is removed (see test above)
    check("select distinct A.title, (select x.year from partition x) from A in authors group by A.title", TestWorlds.publications,
      """to_set(nest(bag, $38 <- authors, $38.title, true, $38.year))""")
  }

  test("publications: should do an efficient group by") {
    check("select distinct A.title, select x.year from x in partition from A in authors group by A.title", TestWorlds.publications,
      """to_set(reduce(bag, ($38, $122) <- nest(bag, $38 <- authors, $38.title, true, $38.year), (_1: $38, _2: $122)))""")
  }

  ignore("publications: count of authors grouped by title and then year") {
    // TODO our rule is not yet handling this case
    check("select distinct title: A.title, stats: (select year: A.year, N: count(partition) from A in partition group by A.year) from A in authors group by A.title",
          TestWorlds.publications, """
          """)
  }

}