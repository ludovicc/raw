package raw
package calculus

import scala.collection.immutable.Seq

class SemanticAnalyzerTest extends FunTest {

  import raw.calculus.Calculus.{IdnDef, IdnUse}

  def go(query: String, world: World) = {
    val ast = parse(query)
    val t = new Calculus.Calculus(ast)
    logger.debug(s"AST: ${t.root}")
    logger.debug(s"Parsed tree: ${CalculusPrettyPrinter(t.root)}")

    val analyzer = new SemanticAnalyzer(t, world, Some(query))
    analyzer.errors.foreach(err => logger.error(ErrorsPrettyPrinter(err)))
    analyzer.printTypedTree()
    analyzer
  }

  def success(query: String, world: World, expectedType: Type) = {
    val analyzer = go(query, world)
    val inferredType = analyzer.tipe(analyzer.tree.root)
    assert(analyzer.errors.isEmpty)
    analyzer.printTypedTree()
    logger.debug(s"Actual type: ${FriendlierPrettyPrinter(inferredType)}")
    logger.debug(s"Expected type: ${FriendlierPrettyPrinter(expectedType)}")
    compare(inferredType.toString, expectedType.toString)
    compare(FriendlierPrettyPrinter(inferredType), FriendlierPrettyPrinter(expectedType))
  }

  def failure(query: String, world: World, error: Error) = {
    val analyzer = go(query, world)
    assert(analyzer.errors.nonEmpty)
    logger.debug(analyzer.errors.toString)
    error match {

      //
      // Test case helpers
      //

      // Incompatible types can be t1,t2 or t2, t1
      case IncompatibleTypes(t1, t2, _, _) =>
        assert(analyzer.errors.exists {
          case IncompatibleTypes(`t1`, `t2`, _, _) => true
          case IncompatibleTypes(`t2`, `t1`, _, _) => true
          case _ => false
        }, s"Error '${ErrorsPrettyPrinter(error)}' not contained in errors")

      // Ignore text description in expected types, even if defined
      case UnexpectedType(t, expected, _, _) =>
        assert(analyzer.errors.exists {
          case UnexpectedType(t, expected, _, _) => true
          case _ => false
        }, s"Error '${ErrorsPrettyPrinter(error)}' not contained in errors")

      case _ =>
        assert(analyzer.errors.exists {
          case `error` => true
          case _ => false
        }, s"Error '${ErrorsPrettyPrinter(error)}' not contained in errors")
    }
  }

  test("for (e <- Events) yield list e") {
    success("for (e <- Events) yield list e", TestWorlds.cern, TestWorlds.cern.sources("Events"))
  }

//  test("for (e <- Events) yield set e") {
//    success("for (e <- Events) yield set e", TestWorlds.cern, CollectionType(SetMonoid(),TestWorlds.cern.sources("Events").asInstanceOf[Type].innerType))
//  }

  test("for (e <- Events; m <- e.muons) yield set m") {
    success("for (e <- Events; m <- e.muons) yield set m", TestWorlds.cern, CollectionType(SetMonoid(),RecordType(List(AttrType("pt", FloatType()), AttrType("eta", FloatType())), None)))
  }

  test("for (e <- Events; e.RunNumber > 100; m <- e.muons) yield set (muon: m)") {
    success("for (e <- Events; e.RunNumber > 100; m <- e.muons) yield set (muon: m)", TestWorlds.cern, CollectionType(SetMonoid(),RecordType(List(AttrType("muon", RecordType(List(AttrType("pt", FloatType()), AttrType("eta", FloatType())), None))), None)))
  }

  test("for (i <- Items) yield set i") {
    success("for (i <- Items) yield set i", TestWorlds.linkedList, CollectionType(SetMonoid(),UserType(Symbol("Item"))))
  }

  test("for (i <- Items) yield set i.next") {
    success("for (i <- Items) yield set i.next", TestWorlds.linkedList, CollectionType(SetMonoid(),UserType(Symbol("Item"))))
  }

  test("for (i <- Items; x := i; x.value = 10; x.next != x) yield set x") {
    success("for (i <- Items; x := i; x.value = 10; x.next != x) yield set x", TestWorlds.linkedList, CollectionType(SetMonoid(),UserType(Symbol("Item"))))
  }

  test("departments - from Fegaras's paper") {
    success(
      """for ( el <- for ( d <- Departments; d.name = "CSE") yield set d.instructors; e <- el; for (c <- e.teaches) yield or c.name = "cse5331") yield set (name: e.name, address: e.address)""", TestWorlds.departments,
      CollectionType(SetMonoid(),RecordType(List(AttrType("name", StringType()), AttrType("address", RecordType(List(AttrType("street", StringType()), AttrType("zipcode", StringType())), None))), None)))
  }

  test("for (d <- Departments) yield set d") {
    success( """for (d <- Departments) yield set d""", TestWorlds.departments, CollectionType(SetMonoid(),UserType(Symbol("Department"))))
  }

  test( """for ( d <- Departments; d.name = "CSE") yield set d""") {
    success( """for ( d <- Departments; d.name = "CSE") yield set d""", TestWorlds.departments, CollectionType(SetMonoid(),UserType(Symbol("Department"))))
  }

  test( """for ( d <- Departments; d.name = "CSE") yield set { name := d.name; (deptName: name) }""") {
    success( """for ( d <- Departments; d.name = "CSE") yield set { name := d.name; (deptName: name) }""", TestWorlds.departments, CollectionType(SetMonoid(),RecordType(List(AttrType("deptName", StringType())), None)))
  }

  test("employees - from Fegaras's paper") {
    success(
      "for (e <- Employees) yield set (E: e, M: for (c <- e.children; for (d <- e.manager.children) yield and c.age > d.age) yield sum 1)", TestWorlds.employees,
      CollectionType(SetMonoid(),RecordType(List(AttrType("E", RecordType(List(AttrType("dno", IntType()), AttrType("children", CollectionType(ListMonoid(),RecordType(List(AttrType("age", IntType())), None))), AttrType("manager", RecordType(List(AttrType("name", StringType()), AttrType("children", CollectionType(ListMonoid(),RecordType(List(AttrType("age", IntType())), None)))), None))), None)), AttrType("M", IntType())), None)))
  }

  test("for (r <- integers) yield max r") {
    success("for (r <- integers) yield max r", TestWorlds.simple, IntType())
  }

  test("for (r <- integers) yield set r + 1") {
    success("for (r <- integers) yield set r + 1", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("for (r <- unknown) yield set r + 1") {
    success("for (r <- unknown) yield set r + 1", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("for (r <- unknown) yield set r + 1.0") {
    success("for (r <- unknown) yield set r + 1.0", TestWorlds.simple, CollectionType(SetMonoid(),FloatType()))
  }

  test("for (r <- unknown; x <- integers; r = x) yield set r") {
    success("for (r <- unknown; x <- integers; r = x) yield set r", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("for (r <- unknown; x <- integers; r + x = 2*x) yield set r") {
    success("for (r <- unknown; x <- integers; r + x = 2*x) yield set r", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("for (r <- unknown; x <- floats; r + x = x) yield set r") {
    success("for (r <- unknown; x <- floats; r + x = x) yield set r", TestWorlds.simple, CollectionType(SetMonoid(),FloatType()))
  }

  test("for (r <- unknown; x <- booleans; r and x) yield set r") {
    success("for (r <- unknown; x <- booleans; r and x) yield set r", TestWorlds.simple, CollectionType(SetMonoid(),BoolType()))
  }

  test("for (r <- unknown; x <- strings; r = x) yield set r") {
    success("for (r <- unknown; x <- strings; r = x) yield set r", TestWorlds.simple, CollectionType(SetMonoid(),StringType()))
  }

  test("for (r <- unknown) yield max (r + (for (i <- integers) yield max i))") {
    success("for (r <- unknown) yield max (r + (for (i <- integers) yield max i))", TestWorlds.simple, IntType())
  }

  test("for (r <- unknown; (for (x <- integers) yield and r > x) = true) yield set r") {
    success("for (r <- unknown; (for (x <- integers) yield and r > x) = true) yield set r", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test( """for (r <- unknown; f := (\v -> v + 2)) yield set f(r)""") {
    success( """for (r <- unknown; f := (\v -> v + 2)) yield set f(r)""", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("for (r <- unknown; v := r) yield set (r + 0)") {
    success("for (r <- unknown; v := r) yield set (r + 0)", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("bug") {
    success("for (s <- unknown; a := s + 2) yield set s", TestWorlds.unknown, CollectionType(SetMonoid(), IntType()))
  }

  test("{ a:= -unknownvalue; unknownvalue}") {
    success("{ a:= -unknownvalue; unknownvalue}", TestWorlds.unknown, NumberType())
  }

  test("{ a:= not unknownvalue; unknownvalue}") {
    success("{ a:= not unknownvalue; unknownvalue}", TestWorlds.unknown, BoolType())
  }

  test("for (r <- integers; (a,b) := (1, 2)) yield set (a+b)") {
    success("for (r <- integers; (a,b) := (1, 2)) yield set (a+b)", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("{ (a,b) := (1, 2); a+b }") {
    success("{ (a,b) := (1, 2); a+b }", TestWorlds.simple, IntType())
  }

  test("{ (a,b) := (1, 2.2); a }") {
    success("{ (a,b) := (1, 2.2); a }", TestWorlds.simple, IntType())
  }

  test("{ (a,b) := (1, 2.2); b }") {
    success("{ (a,b) := (1, 2.2); b }", TestWorlds.simple, FloatType())
  }

  test("{ ((a,b),c) := ((1, 2.2), 3); a }") {
    success("{ ((a,b),c) := ((1, 2.2), 3); a }", TestWorlds.simple, IntType())
  }

  test("{ ((a,b),c) := ((1, 2.2), 3); b }") {
    success("{ ((a,b),c) := ((1, 2.2), 3); b }", TestWorlds.simple, FloatType())
  }

  test("{ ((a,b),c) := ((1, 2.2), 3); c }") {
    success("{ ((a,b),c) := ((1, 2.2), 3); c }", TestWorlds.simple, IntType())
  }

  test("{ ((a,b),c) := ((1, 2.2), 3); a+c }") {
    success("{ ((a,b),c) := ((1, 2.2), 3); a+c }", TestWorlds.simple, IntType())
  }

  test("{ (a,(b,c)) := (1, (2.2, 3)); a }") {
    success("{ (a,(b,c)) := (1, (2.2, 3)); a }", TestWorlds.simple, IntType())
  }

  test("{ (a,(b,c)) := (1, (2.2, 3)); b }") {
    success("{ (a,(b,c)) := (1, (2.2, 3)); b }", TestWorlds.simple, FloatType())
  }

  test("{ (a,(b,c)) := (1, (2.2, 3)); c }") {
    success("{ (a,(b,c)) := (1, (2.2, 3)); c }", TestWorlds.simple, IntType())
  }

  test("{ (a,(b,c)) := (1, (2.2, 3)); a+c }") {
    success("{ (a,(b,c)) := (1, (2.2, 3)); a+c }", TestWorlds.simple, IntType())
  }

  test("{ x := for (i <- integers) yield set i; x }") {
    success("{ x := for (i <- integers) yield set i; x }", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("{ x := for (i <- integers) yield set i; for (y <- x) yield set y }") {
    success("{ x := for (i <- integers) yield set i; for (y <- x) yield set y }", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("{ z := 42; x := for (i <- integers) yield set i; for (y <- x) yield set y }") {
    success("{ z := 42; x := for (i <- integers) yield set i; for (y <- x) yield set y }", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("{ z := 42; x := for (i <- integers; i = z) yield set i; for (y <- x) yield set y }") {
    success("{ z := 42; x := for (i <- integers; i = z) yield set i; for (y <- x) yield set y }", TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  ignore("for (r <- unknown; ((r.age + r.birth) > 2015) = r.alive) yield set r") {
    success("for (r <- unknown; ((r.age + r.birth) > 2015) = r.alive) yield set r", TestWorlds.unknown, CollectionType(SetMonoid(),ConstraintRecordType(Set(AttrType("age", IntType()), AttrType("birth", IntType()), AttrType("alive", BoolType())))))
  }

  test("for (r <- unknownrecords) yield set r.dead or r.alive") {
    success("for (r <- unknownrecords) yield set r.dead or r.alive", TestWorlds.unknown, CollectionType(SetMonoid(),BoolType()))
  }

  test("for (r <- unknownrecords; r.dead or r.alive) yield set r") {
    success("for (r <- unknownrecords; r.dead or r.alive) yield set r", TestWorlds.unknown, CollectionType(SetMonoid(),RecordType(List(AttrType("dead", BoolType()), AttrType("alive", BoolType())), None)))
  }

  test("expression block with multiple comprehensions") {
    success(
      """
    {
      z := 42;
      desc := "c.description";
      x := for (i <- integers; i = z) yield set i;
      for (y <- x) yield set 1
    }
    """, TestWorlds.simple, CollectionType(SetMonoid(),IntType()))
  }

  test("""\a -> a + 2""") {
    success( """\a -> a + 2""", TestWorlds.empty, FunType(IntType(), IntType()))
  }

  test( """\a -> a + a + 2""") {
    success( """\a -> a + a + 2""", TestWorlds.empty, FunType(IntType(), IntType()))
  }

  test( """\(a, b) -> a + b + 2""") {
    success( """\(a, b) -> a + b + 2""", TestWorlds.empty, FunType(RecordType(List(AttrType("_1", IntType()), AttrType("_2", IntType())), None), IntType()))
  }

  test( """\a -> a""") {
    val a = TypeVariable()
    success( """\a -> a""", TestWorlds.empty, FunType(a, a))
  }

  test( """\x -> x.age + 2""") {
    success( """\x -> x.age + 2""", TestWorlds.empty, FunType(ConstraintRecordType(Set(AttrType("age", IntType()))), IntType()))
  }

  test( """\(x, y) -> x + y""") {
    val n = NumberType()
    success( """\(x, y) -> x + y""", TestWorlds.empty, FunType(RecordType(List(AttrType("_1", n), AttrType("_2", n)), None), n))
  }

  test("""{ recursive := \(f, arg) -> f(arg); recursive } """) {
    var arg = TypeVariable()
    val out = TypeVariable()
    val f = FunType(arg, out)
    success( """{ recursive := \(f, arg) -> f(arg); recursive } """, TestWorlds.empty, FunType(RecordType(List(AttrType("_1", f), AttrType("_2", arg)), None), out))
  }

//     TODO: If I do yield bag, I think I also constrain on what the input's commutativity and associativity can be!...
//    success("""\x -> for (y <- x) yield bag (y.age * 2, y.name)""", world,
//      FunType(
//        ConstraintCollectionType(ConstraintRecordType(Set(AttrType("age", IntType()), AttrType("name", TypeVariable()))), None, None),
//        CollectionType(BagMonoid(),RecordType(List(AttrType("_1", IntType()), AttrType("_2", TypeVariable())), None))))

  test("for ((a, b) <- list((1, 2.2))) yield set (a, b)") {
    success("""for ((a, b) <- list((1, 2.2))) yield set (a, b)""", TestWorlds.empty, CollectionType(SetMonoid(),RecordType(List(AttrType("_1", IntType()), AttrType("_2", FloatType())), None)))
  }

  test("1 + 1.") {
    failure("1 + 1.", TestWorlds.empty, IncompatibleTypes(IntType(), FloatType()))
  }

  test("1 - 1.") {
    failure("1 - 1.", TestWorlds.empty, IncompatibleTypes(IntType(), FloatType()))
  }

  test("1 + true") {
    failure("1 + true", TestWorlds.empty, IncompatibleTypes(IntType(), BoolType()))
  }

  test("1 - true") {
    failure("1 - true", TestWorlds.empty, IncompatibleTypes(IntType(), BoolType()))
  }

  test("1 + things") {
    failure("1 + things", TestWorlds.things, IncompatibleTypes(IntType(), TestWorlds.things.sources("things")))
  }

  test("1 - things") {
    failure("1 - things", TestWorlds.things, IncompatibleTypes(IntType(), TestWorlds.things.sources("things")))
  }

  test("for (t <- things; t.a > 10.23) yield and true") {
    failure("for (t <- things; t.a > 10.23) yield and true", TestWorlds.things, IncompatibleTypes(IntType(), FloatType()))
  }

  test("for (t <- things; t.a + 1.0 > t.b ) yield set t.a") {
    failure("for (t <- things; t.a + 1.0 > t.b ) yield set t.a", TestWorlds.things, IncompatibleTypes(IntType(), FloatType()))
  }

  test("a + 1") {
    failure("a + 1", TestWorlds.empty, UnknownDecl(IdnUse("a")))
  }

  test("{ a := 1; a := 2; a }") {
    failure("{ a := 1; a := 2; a }", TestWorlds.empty, MultipleDecl(IdnDef("a")))
  }

  test("for (a <- blah) yield set a") {
    failure("for (a <- blah) yield set a", TestWorlds.empty, UnknownDecl(IdnUse("blah")))
  }

  test("for (a <- things) yield set b") {
    failure("for (a <- things) yield set b", TestWorlds.things, UnknownDecl(IdnUse("b")))
  }

  test("for (a <- things; a <- things) yield set a") {
    failure("for (a <- things; a <- things) yield set a", TestWorlds.things, MultipleDecl(IdnDef("a")))
  }

  test("if 1 then 1 else 0") {
    failure("if 1 then 1 else 0", TestWorlds.empty, UnexpectedType(IntType(), BoolType(), None))
  }

  test("if true then 1 else 1.") {
    failure("if true then 1 else 1.", TestWorlds.empty, IncompatibleTypes(IntType(), FloatType()))
  }

  test("{ a := 1; b := 1; c := 2.; (a + b) + c }") {
    failure("{ a := 1; b := 1; c := 2.; (a + b) + c }", TestWorlds.empty, IncompatibleTypes(IntType(), FloatType()))
  }

  test("{ a := 1; b := 1.; c := 2; d := 2.; (a + b) + (c + d) }") {
    failure("{ a := 1; b := 1.; c := 2; d := 2.; (a + b) + (c + d) }", TestWorlds.empty, IncompatibleTypes(IntType(), FloatType()))
  }

  test("for (t <- things) yield sum 1") {
    failure("for (t <- things) yield sum 1", TestWorlds.things, IncompatibleMonoids(SumMonoid(), TestWorlds.things.sources("things")))
  }

  test("for (t <- things) yield and true") {
    success("for (t <- things) yield and true", TestWorlds.things, BoolType())
  }

  test("for (t <- things) yield bag t") {
    failure("for (t <- things) yield bag t", TestWorlds.things, IncompatibleMonoids(BagMonoid(), TestWorlds.things.sources("things")))
  }

  test("for (t <- things) yield list t") {
    failure("for (t <- things) yield list t", TestWorlds.things, IncompatibleMonoids(ListMonoid(), TestWorlds.things.sources("things")))
  }

  test("for (s <- students) yield list s") {
    success("for (s <- students) yield list s", TestWorlds.professors_students, CollectionType(ListMonoid(),UserType(Symbol("student"))))
  }

  test("for (s <- students) yield list (a: 1, b: s)") {
    success("for (s <- students) yield list (a: 1, b: s)", TestWorlds.professors_students, CollectionType(ListMonoid(),RecordType(List(AttrType("a", IntType()), AttrType("b", UserType(Symbol("student")))), None)))
  }

  test("for (s <- students; p <- professors; s = p) yield list s") {
    failure("for (s <- students; p <- professors; s = p) yield list s", TestWorlds.professors_students, IncompatibleTypes(UserType(Symbol("professor")), UserType(Symbol("student"))))
  }

  test("for (s <- students; p <- professors) yield list (name: s.name, age: p.age)") {
    success("for (s <- students; p <- professors) yield list (name: s.name, age: p.age)", TestWorlds.professors_students, CollectionType(ListMonoid(),RecordType(List(AttrType("name", StringType()), AttrType("age", IntType())), None)))
  }

  test("for (s <- students; p <- professors) yield list (a: 1, b: s, c: p)") {
    success("for (s <- students; p <- professors) yield list (a: 1, b: s, c: p)", TestWorlds.professors_students, CollectionType(ListMonoid(),RecordType(List(AttrType("a", IntType()), AttrType("b", UserType(Symbol("student"))), AttrType("c", UserType(Symbol("professor")))), None)))
  }

  test("""\(x, y) -> x + y + 10""") {
    success("""\(x, y) -> x + y + 10""", TestWorlds.empty, FunType(RecordType(List(AttrType("_1", IntType()), AttrType("_2", IntType())), None), IntType()))
  }

  test("""\(x, y) -> x + y + 10.2""") {
    success("""\(x, y) -> x + y + 10.2""", TestWorlds.empty, FunType(RecordType(List(AttrType("_1", FloatType()), AttrType("_2", FloatType())), None), FloatType()))
  }

  test("""\(x, y) -> { z := x; y + z }""") {
    val n = NumberType()
    success("""\(x, y) -> { z := x; y + z }""", TestWorlds.empty, FunType(RecordType(List(AttrType("_1", n), AttrType("_2", n)), None), n))
  }

  test("""{ x := { y := 1; z := y; z }; x }""") {
    success("""{ x := { y := 1; z := y; z }; x }""", TestWorlds.empty, IntType())
  }

  test("""let polymorphism - not binding into functions""") {
    val m = MonoidVariable(idempotent=Some(false))
    val z = TypeVariable()
    val n = NumberType()
    success(
      """
        {
        sum1 := (\(x,y) -> for (z <- x) yield sum (y(z)));
        age := (students, \x -> x.age);
        v := sum1(age);
        sum1
        }

      """, TestWorlds.professors_students,
      FunType(RecordType(List(AttrType("_1", CollectionType(m, z)), AttrType("_2", FunType(z, n))), None), n))
  }

  test("""home-made count applied to wrong type""") {
    val m =
    failure(
      """
        {
        count1 := \x -> for (z <- x) yield sum 1
        count1(1)
        }

      """, TestWorlds.empty,
      UnexpectedType(FunType(CollectionType(MonoidVariable(idempotent=Some(false)), TypeVariable()), IntType()), FunType(IntType(), TypeVariable()), None)
    )
  }

  test("""let-polymorphism #1""") {
    success(
      """
        {
          f := \x -> x;
          (f(1), f(true))
        }
      """, TestWorlds.empty, RecordType(List(AttrType("_1", IntType()), AttrType("_2", BoolType())), None))
  }

  test("""let-polymorphism #2""") {
    success(
      """
        {
          f := \x -> (x, 12);
          (f(1), f(true))
        }
      """, TestWorlds.empty,
      RecordType(List(
        AttrType("_1", RecordType(List(AttrType("_1",  IntType()), AttrType("_2", IntType())), None)),
        AttrType("_2", RecordType(List(AttrType("_1",  BoolType()), AttrType("_2", IntType())), None))),
        None))
  }

  test("""let-polymorphism #3""") {
    val i = TypeVariable()
    success(
    """ {
      f := \x -> x;
      g := \y -> y;
      h := if (true) then f else g;
      h
}
    """, TestWorlds.empty, FunType(i, i))
  }

  test("""let polymorphism #4""") {
    val z = TypeVariable()
    val n = NumberType()
    success(
      """
        {
        x := 1;
        f := \v -> v = x;
        v := f(10);
        f
        }

      """, TestWorlds.empty,
      FunType(IntType(), BoolType()))
  }

  test("""let-polymorphism #5""") {
    val x = TypeVariable()
    val age = TypeVariable()
    val rec = ConstraintRecordType(Set(AttrType("age", age)))
    val rec2 = ConstraintRecordType(Set(AttrType("age", BoolType())))
    success(
      """
        {
        f := \x -> x = x;
        g := \x -> x.age;
        (f, g, if true then f else g)
        }
      """, TestWorlds.empty, RecordType(Seq(
      AttrType("_1", FunType(x, BoolType())),
      AttrType("_2", FunType(rec, age)),
      AttrType("_3", FunType(rec2, BoolType()))), None))
  }

  test("""let-polymorphism #6""") {
    val z = TypeVariable()
    val n = NumberType()
    success(
      """
        {
        x := set(1);
        f := \v -> for (z <- x; z = v) yield set z;

        v := f(10);
        f
        }

      """, TestWorlds.empty,
      FunType(IntType(), CollectionType(SetMonoid(),IntType())))
  }

  test("""let-polymorphism #7""") {
    val n1 = NumberType()
    val n2 = NumberType()
    success(
      """
      {
        f := \x -> {
          f1 := (\x1 -> (x1 = (x + x)));
          f2 := (\x2 -> (x2 != (x + x)));
          \z -> ((if true then f1 else f2)(z))
        };
        (f, f(1))
      }
      """, TestWorlds.empty,
      RecordType(List(AttrType("_1", FunType(n1, FunType(n1, BoolType()))), AttrType("_2", FunType(n2, BoolType()))), None))
  }

  test("map") {
    success(
      """
        {
        map := \(col, f) -> for (el <- col) yield list f(el);
        col1 := list(1);
        col2 := list(1.0);
        (map(col1, \x -> x + 1), map(col2, \x -> x + 1.1))
        }
      """, TestWorlds.empty, RecordType(List(AttrType("_1", CollectionType(ListMonoid(),IntType())), AttrType("_2", CollectionType(ListMonoid(),FloatType()))), None))
  }

  test("""\(x, y) -> x.age = y""") {
    val y = TypeVariable()
    success("""\(x, y) -> x.age = y""", TestWorlds.empty, FunType(RecordType(List(AttrType("_1", ConstraintRecordType(Set(AttrType("age", y)))), AttrType("_2", y)), None), BoolType()))
  }

  test("""\(x, y) -> (x, y)""") {
    val x = TypeVariable()
    val y = TypeVariable()
    val rec = RecordType(List(AttrType("_1", x), AttrType("_2", y)), None)
    success("""\(x, y) -> (x, y)""", TestWorlds.empty, FunType(rec, rec))
  }

  test("""\(x,y) -> for (z <- x) yield sum y(z)""") {
    val m = MonoidVariable(commutative = None, idempotent = Some(false))
    val z = TypeVariable()
    val yz = NumberType()
    success("""\(x,y) -> for (z <- x) yield sum y(z)""", TestWorlds.empty,
      FunType(
        RecordType(List(AttrType("_1", CollectionType(m, z)), AttrType("_2", FunType(z, yz))), None),
        yz))
  }

  test("""\(x,y) -> for (z <- x) yield max y(z)""") {
    val m = MonoidVariable(commutative = None, idempotent = None)
    val z = TypeVariable()
    val yz = NumberType()
    success("""\(x,y) -> for (z <- x) yield max y(z)""", TestWorlds.empty,
      FunType(
        RecordType(List(AttrType("_1", CollectionType(m, z)), AttrType("_2", FunType(z, yz))), None),
        yz))
  }

  test("""(\x -> x + 1)(1)""") {
    success( """(\x -> x + 1)(1)""", TestWorlds.empty, IntType())
  }

  test("""(\y -> (\x -> x + 1)(y))(1)""") {
    success("""(\y -> (\x -> x + 1)(y))(1)""", TestWorlds.empty, IntType())
  }

  test("recursive lambda #1") {
    success(
      """
         {
         fact1 := \(f, n1) -> if (n1 = 0) then 1 else n1 * (f(f, n1 - 1));
         fact := \n -> fact1(fact1, n);
         fact
        }
      """, TestWorlds.empty, FunType(IntType(), IntType()))
  }

  test("recursive lambda #2") {
    success(
      """
      {
        F := \f -> (\x -> f(f,x));
        fact1 := \(f, n1) -> if (n1 = 0) then 1 else n1 * (f(f, n1 - 1));
        fact := F(fact1);
        fact
      }
      """, TestWorlds.empty, FunType(IntType(), IntType())
    )

  }

  test("option#1") {
    val oint = IntType()
    oint.nullable = true
    val ob = BoolType()
    ob.nullable = true
    success("LI", TestWorlds.options, CollectionType(ListMonoid(), IntType()))
    success("LOI", TestWorlds.options, CollectionType(ListMonoid(), oint))
    success("for (i <- LI) yield set i", TestWorlds.options, CollectionType(SetMonoid(), IntType()))
    success("for (i <- LI) yield set 1+i", TestWorlds.options, CollectionType(SetMonoid(), IntType()))
    success("for (i <- LOI) yield set i", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("for (i <- LOI) yield set 1+i", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("for (i <-LI; oi <- LOI) yield set oi+i", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("for (i <- LOI) yield set i", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("(for (i <- LI) yield set i) union (for (i <- LI) yield set i)", TestWorlds.options, CollectionType(SetMonoid(), IntType()))
    success("(for (i <- LOI) yield set i) union (for (i <- LOI) yield set i)", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("(for (i <- LOI) yield set i) union (for (i <- LI) yield set i)", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("(for (i <- LI) yield set i) union (for (i <- LOI) yield set i)", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("{ m := for (i <- LOI) yield max i; for (i <- LI; i < m) yield set i }", TestWorlds.options, CollectionType(SetMonoid(), IntType()))
    success("for (i <- LI) yield max i", TestWorlds.options, IntType())
    success("for (i <- LOI) yield max i", TestWorlds.options, IntType())
    success("for (i <- OLI) yield max i", TestWorlds.options, oint)
    success("for (i <- OLOI) yield max i", TestWorlds.options, oint)
    success("for (i <- LI) yield list i", TestWorlds.options, CollectionType(ListMonoid(), IntType()))
    success("for (i <- LOI) yield list i", TestWorlds.options, CollectionType(ListMonoid(), oint))
    success("for (i <- OLI) yield list i", TestWorlds.options, { val ot = CollectionType(ListMonoid(), IntType()); ot.nullable = true; ot })
    success("for (i <- OLOI) yield list i", TestWorlds.options, { val ot = CollectionType(ListMonoid(), oint); ot.nullable = true; ot })
    success("{ m := for (i <- LOI) yield max i; for (i <- LI; i < m) yield set i }", TestWorlds.options, CollectionType(SetMonoid(), IntType()))
    success("for (i <- LOI; i < 1) yield set i", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("{ m := for (i <- LOI) yield max i; for (i <- LOI; i < m) yield set i }", TestWorlds.options, CollectionType(SetMonoid(), oint))
    success("for (r <- records) yield set (r.OI > 10)", TestWorlds.options, CollectionType(SetMonoid(), ob))
    success("for (r <- records) yield set (r.I > 10)", TestWorlds.options, CollectionType(SetMonoid(), BoolType()))
    val oset = CollectionType(SetMonoid(), BoolType())
    oset.nullable = true
    success("for (i <- OLI) yield set (i > 10)", TestWorlds.options, oset)

    success("""{ f := \x -> x > 10 ; for (i <- LI) yield set f(i) } """, TestWorlds.options, CollectionType(SetMonoid(), BoolType()))
    success("""{ f := \x -> x > 10 ; for (i <- LOI) yield set f(i) } """, TestWorlds.options, CollectionType(SetMonoid(), ob))
    success("""{ f := \x -> x > 10 ; for (i <- OLI) yield set f(i) } """, TestWorlds.options, { val ot = CollectionType(SetMonoid(), BoolType()); ot.nullable = true; ot })
    success("""{ f := \x -> x > 10 ; for (i <- OLOI) yield set f(i) } """, TestWorlds.options, { val ot = CollectionType(SetMonoid(), ob); ot.nullable = true; ot })
    success("""for (i <- LI) yield set (\x -> x < i)""", TestWorlds.options, CollectionType(SetMonoid(), FunType(IntType(), BoolType())))
    success("""for (i <- LOI) yield set (\x -> x < i)""", TestWorlds.options, CollectionType(SetMonoid(), FunType(IntType(), ob)))
    success("""for (i <- OLI) yield set (\x -> x < i)""", TestWorlds.options, { val ot = CollectionType(SetMonoid(), FunType(IntType(), BoolType())); ot.nullable = true; ot })
    success("""for (i <- OLOI) yield set (\x -> x < i)""", TestWorlds.options, { val ot = CollectionType(SetMonoid(), FunType(IntType(), ob)); ot.nullable = true; ot })
    success("""for (i <- OLOI) yield set (\(x, (y, z)) -> x < i and (y+z) > i)""", TestWorlds.options, {
      val ot = CollectionType(SetMonoid(), FunType(RecordType(List(AttrType("_1", IntType()),
                                                                   AttrType("_2", RecordType(List(
                                                                                        AttrType("_1", IntType()), AttrType("_2", IntType())),
                                                                     None))),
        None), ob)); ot.nullable = true; ot })
  }

  ignore("options in select") {
    success("select s from s in LI", TestWorlds.options, CollectionType(BagMonoid(), IntType()))
    success("select partition from s in LI group by s", TestWorlds.options, CollectionType(BagMonoid(), CollectionType(BagMonoid(), IntType())))

    {
      // we check the type of "partition" when it comes from an option bag of integers. Itself should be an option bag of integers, wrapped into
      // an option bag because the select itself inherits the option of its sources.
      val partitionType = CollectionType(BagMonoid(), IntType());
      partitionType.nullable = true // because OLI is an option list
      val selectType = CollectionType(BagMonoid(), partitionType);
      selectType.nullable = true // because OLI is an option list
      success("select partition from s in OLI group by s", TestWorlds.options, selectType)
    }
  }

  ignore("fancy select with options") {
      // more fancy. We join a bag of option(int) with an option bag(int).
      // partition should be an option bag of record with an option int and a non-option int.
      val optionInt = IntType()
      optionInt.nullable = true
      val partitionType = CollectionType(BagMonoid(), RecordType(Seq(AttrType("_1", optionInt), AttrType("_2", IntType())), None))
      partitionType.nullable = true // because OLI is an option list
      // select should be an option bag of ...
      val selectType = CollectionType(BagMonoid(), RecordType(Seq(AttrType("_1", optionInt), AttrType("_2", partitionType)), None));
      selectType.nullable = true // because OLI is an option list
      success("select s+r, partition from s in OLI, r in LOI where s = r group by s+r", TestWorlds.options, selectType)
    }

  test("select") {
    success("select s from s in students", TestWorlds.professors_students, CollectionType(BagMonoid(), UserType(Symbol("student"))))
    success("select distinct s from s in students", TestWorlds.professors_students, CollectionType(SetMonoid(), UserType(Symbol("student"))))
    success("select distinct s.age from s in students", TestWorlds.professors_students, CollectionType(SetMonoid(), IntType()))
    success("select s.age from s in students order by s.age", TestWorlds.professors_students, CollectionType(ListMonoid(), IntType()))
    success("""select s.lastname from s in (select s.name as lastname from s in students)""", TestWorlds.professors_students, CollectionType(BagMonoid(), StringType()))
  }

  ignore("wrong field name") {
    failure("""select s.astname from s in (select s.name as lastname from s in students)""", TestWorlds.professors_students, ???)
  }

  test("partition") {
    failure("partition", TestWorlds.empty, UnknownPartition(Calculus.Partition()))
  }

  test("select partition from students s") {
    failure("select partition from students s", TestWorlds.empty, UnknownPartition(Calculus.Partition()))
  }

  test("select s.age, partition from students s group by s.age") {
    success("select s.age, partition from students s group by s.age", TestWorlds.professors_students, CollectionType(BagMonoid(), RecordType(Seq(AttrType("_1", IntType()), AttrType("_2", CollectionType(BagMonoid(), UserType(Symbol("student"))))), None)))
  }

  test("select s.age, (select p.name from partition p) from students s group by s.age") {
    success("select s.age, (select p.name from partition p) as names from students s group by s.age", TestWorlds.professors_students, CollectionType(BagMonoid(), RecordType(Seq(AttrType("_1", IntType()), AttrType("names", CollectionType(BagMonoid(), StringType()))), None)))
  }

  test("select s.dept, count(partition) as n from students s group by s.dept") {
    success("select s.department, count(partition) as n from students s group by s.department", TestWorlds.school, CollectionType(BagMonoid(),RecordType(List(AttrType("_1", StringType()), AttrType("n", IntType())), None)))
  }

  ignore("select dpt, count(partition) as n from students s group by dpt: s.dept") {
    success("select dpt, count(partition) as n from students s group by dpt: s.dept", TestWorlds.professors_students, ???)
  }

  test("select s.age/10 as decade, (select s.name from partition s) as names from students s group by s.age/10") {
    success("select s.age/10 as decade, (select s.name from partition s) as names from students s group by s.age/10", TestWorlds.professors_students, CollectionType(BagMonoid(),RecordType(List(AttrType("decade",IntType()), AttrType("names",CollectionType(BagMonoid(),StringType()))),None)))
  }

  test("select s.age, (select s.name, partition from partition s group by s.name) as names from students s group by s.age") {
    success("select s.age, (select s.name, partition from partition s group by s.name) as names from students s group by s.age", TestWorlds.professors_students, CollectionType(BagMonoid(),RecordType(List(AttrType("_1",IntType()), AttrType("names",CollectionType(BagMonoid(),RecordType(List(AttrType("_1",StringType()), AttrType("_2",CollectionType(BagMonoid(),UserType(Symbol("student"))))),None)))),None)))
  }

  test("sum(list(1))") {
    success("sum(list(1))", TestWorlds.empty, IntType())
  }

  test("sum(list(1.1))") {
    success("sum(list(1.1))", TestWorlds.empty, FloatType())
  }

  test("sum(1)") {
    failure("sum(1)", TestWorlds.empty, UnexpectedType(IntType(), CollectionType(MonoidVariable(), NumberType())))
  }

  test("max(list(1))") {
    success("max(list(1))", TestWorlds.empty, IntType())
  }

  test("max(list(1.1))") {
    success("max(list(1.1))", TestWorlds.empty, FloatType())
  }

  test("max(1)") {
    failure("max(1)", TestWorlds.empty, UnexpectedType(IntType(), CollectionType(MonoidVariable(), NumberType())))
  }

  test("min(list(1))") {
    success("min(list(1))", TestWorlds.empty, IntType())
  }

  test("min(list(1.1))") {
    success("min(list(1.1))", TestWorlds.empty, FloatType())
  }

  test("min(1)") {
    failure("min(1)", TestWorlds.empty, UnexpectedType(IntType(), CollectionType(MonoidVariable(), NumberType())))
  }

  test("avg(list(1))") {
    success("avg(list(1))", TestWorlds.empty, IntType())
  }

  test("avg(list(1.1))") {
    success("avg(list(1.1))", TestWorlds.empty, FloatType())
  }

  test("avg(1)") {
    failure("avg(1)", TestWorlds.empty, UnexpectedType(IntType(), CollectionType(MonoidVariable(), NumberType())))
  }

  test("count(list(1))") {
    success("count(list(1))", TestWorlds.empty, IntType())
  }

  test("count(list(1.1))") {
    success("count(list(1.1))", TestWorlds.empty, IntType())
  }

  test("""count(list("foo"))""") {
    success("""count(list("foo"))""", TestWorlds.empty, IntType())
  }

  test("""count(1)""") {
    failure("count(1)", TestWorlds.empty, UnexpectedType(IntType(), CollectionType(MonoidVariable(), TypeVariable())))
  }

  test("to_bag(list(1))") {
    success("""to_bag(list(1))""", TestWorlds.empty, CollectionType(BagMonoid(), IntType()))
  }

  test("to_set(list(1))") {
    success("""to_set(list(1))""", TestWorlds.empty, CollectionType(SetMonoid(), IntType()))
  }

  test("to_list(set(1))") {
    success("""to_list(set(1))""", TestWorlds.empty, CollectionType(ListMonoid(), IntType()))
  }
}
