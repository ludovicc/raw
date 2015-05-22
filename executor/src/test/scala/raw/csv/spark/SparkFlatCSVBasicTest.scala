package raw.csv.spark

import org.apache.spark.rdd.RDD
import raw.csv.Professor
import raw.{RawQuery, rawQueryAnnotation}

@rawQueryAnnotation
class NumberOfProfessors(val profs:RDD[Professor]) extends RawQuery {
  val query = "for (d <- profs) yield sum 1"
}

class SparkFlatCSVBasicTest extends AbstractSparkFlatCSVTest {
  /* ======================================
   Primitive monoids
    ====================================== */
  test("number of professors") {
    assert(new NumberOfProfessors(testData.profs).computeResult === 3)
  }

  //  test("number of students") {
  //    assert(Raw.query("for (d <- students) yield sum 1", HList("students" -> testData.students)) === 7)
  //  }
  //
  //  test("number of departments") {
  //    assert(Raw.query("for (d <- departments) yield sum 1", HList("departments" -> testData.departments)) === 3)
  //  }
  //
  //  test("[sum] of the students birth year") {
  //    assert(Raw.query("for (s <- students) yield sum s.birthYear", HList("students" -> testData.students)) === 13928)
  //  }
  //
  //  test("[max] of the students birth year") {
  //    assert(Raw.query("for (s <- students) yield max s.birthYear", HList("students" -> testData.students)) === 1992)
  //  }
  //
  //  test("[multiply] product of the students birth year") {
  //    assert(Raw.query("for (s <- students; s.birthYear < 1989) yield multiply s.birthYear", HList("students" -> testData.students)) === 3950156)
  //  }
  //
  //  test("[and] not all students were born before 1990") {
  //    assert(Raw.query("for (s <- students) yield and s.birthYear<1990", HList("students" -> testData.students)) === false)
  //  }
  //
  //  test("[and] all students were born after 1980") {
  //    assert(Raw.query("for (s <- students) yield and s.birthYear>1980", HList("students" -> testData.students)) === true)
  //  }
  //
  //  test("[or] at least one student was born in 1990") {
  //    assert(Raw.query("for (s <- students) yield or s.birthYear=1990", HList("students" -> testData.students)) === true)
  //  }
  //
  //  test("[or] no student was born in 1991") {
  //    assert(Raw.query("for (s <- students) yield or s.birthYear=1991", HList("students" -> testData.students)) === false)
  //  }
  //
  //  /* ======================================
  //   Merge monoids: sum, max, multiply, and, or
  //    ====================================== */
  //  test("[merge and] Some students born between 1990 and 1995, exclusive") {
  //    assert(Raw.query("for (s <- students) yield or (s.birthYear>1990 and s.birthYear<1995)", HList("students" -> testData.students)) === true)
  //  }
  //  // TODO: Other merge monoids not yet implemented in the executor
  //
  //  /* ======================================
  //  Collection monoids: List, Set, Bag
  //  ====================================== */
  //  test("[list monoid] All student names") {
  //    val actual = Raw.query("for (s <- students) yield list s.birthYear", HList("students" -> testData.students)).asInstanceOf[List[Int]]
  //    val expected = List(1990, 1990, 1989, 1992, 1987, 1992, 1988)
  //    assert(actual === expected)
  //  }
  //
  //  test("[set monoid] All student names") {
  //    val actual = Raw.query("for (s <- students) yield set s.birthYear", HList("students" -> testData.students))
  //    val expected = Set(1990, 1989, 1987, 1992, 1988)
  //    assert(actual === expected)
  //  }
  //
  //  test("[bag monoid] All student names") {
  //    val actual = Raw.query("for (s <- students) yield bag s.birthYear", HList("students" -> testData.students))
  //    val expected = ImmutableMultiset.of(1990, 1990, 1989, 1992, 1987, 1992, 1988)
  //    assert(actual === expected)
  //  }
  //
  //  test("[set monoid] professors") {
  //    val actual = Raw.query("for (d <- profs) yield set d", HList("profs" -> testData.profs)).asInstanceOf[Set[Professor]]
  //    logger.info("Result: " + actual)
  //    assert(actual === ReferenceTestData.profs.toSet)
  //  }
  //
  //  test("[list monoid] professors as list") {
  //    val actual = Raw.query("for (d <- profs) yield list d", HList("profs" -> testData.profs)).asInstanceOf[List[Professor]]
  //    logger.info("Result: " + actual)
  //    assert(actual === ReferenceTestData.profs)
  //  }
  //
  //  /* ======================================
  //   Predicates
  //  ====================================== */
  //  test("set of students born in 1990") {
  //    assert(Raw.query( """for (d <- students; d.birthYear = 1990) yield set d.name""", HList("students" -> testData.students)) === Set("Student1", "Student2"))
  //  }
  //
  //  test("number of students born in 1992") {
  //    assert(Raw.query( """for (d <- students; d.birthYear = 1992) yield sum 1""", HList("students" -> testData.students)) === 2)
  //  }
  //
  //  test("number of students born before 1991 (included)") {
  //    assert(Raw.query( """for (d <- students; d.birthYear <= 1991) yield sum 1""", HList("students" -> testData.students)) === 5)
  //  }
  //
  //  test("set of students in BC123") {
  //    assert(Raw.query( """for (d <- students; d.office = "BC123") yield set d.name""", HList("students" -> testData.students)) === Set("Student1", "Student3", "Student5"))
  //  }
  //
  //  test("set of students in dep2") {
  //    assert(Raw.query( """for (d <- students; d.department = "dep2") yield set d.name""", HList("students" -> testData.students)) === Set("Student2", "Student4"))
  //  }
  //
  //  test("number of students in dep1") {
  //    assert(Raw.query( """for (d <- students; d.department = "dep1") yield sum 1""", HList("students" -> testData.students)) === 3)
  //  }
  //
  //  test("set of department (using only students table)") {
  //    assert(Raw.query( """for (s <- students) yield set s.department""", HList("students" -> testData.students)) === Set("dep1", "dep2", "dep3"))
  //  }
}