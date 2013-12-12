/** Calculus generated by the Canonical.
 *  It is a subset of the Normalizer Calculus with qualifiers of comprehensions aggregated into
 *  single predicates and transformed in CNF form.
 */
package raw.calculus.canonical

import scala.util.parsing.input.Positional

import raw._

/** Expressions for Calculus
 */

sealed abstract class Expression extends Positional

sealed abstract class TypedExpression(val monoidType: MonoidType) extends Expression

sealed abstract class UntypedExpression extends Expression

/** Null
 */

case class Null extends TypedExpression(VariableType())

/** Constant
 */

sealed abstract class Constant(t: MonoidType) extends TypedExpression(t)
case class BoolConst(v: Boolean) extends Constant(BoolType)
case class IntConst(v: Long) extends Constant(IntType)
case class FloatConst(v: Double) extends Constant(FloatType)
case class StringConst(v: String) extends Constant(StringType)

/** Variable
 */

case class Variable(v: calculus.normalizer.Variable) extends TypedExpression(v.monoidType)

/** RecordProjection
 */

case class RecordProjection(t: MonoidType, e: TypedExpression, name: String) extends TypedExpression(t)

/** RecordConstruction
 */

case class AttributeConstruction(name: String, e: TypedExpression) extends Positional
case class RecordConstruction(t: MonoidType, atts: List[AttributeConstruction]) extends TypedExpression(t)

/** IfThenElse
 */

case class IfThenElse(t: MonoidType, e1: TypedExpression, e2: TypedExpression, e3: TypedExpression) extends TypedExpression(t)

/** BinaryOperation
 */

case class BinaryOperation(t: MonoidType, op: BinaryOperator, e1: TypedExpression, e2: TypedExpression) extends TypedExpression(t)

/** Zeroes for Collection Monoids
 */

case class EmptySet extends TypedExpression(VariableType())
case class EmptyBag extends TypedExpression(VariableType())
case class EmptyList extends TypedExpression(VariableType())

/** ConsCollectionMonoid
 */

case class ConsCollectionMonoid(t: MonoidType, m: CollectionMonoid, e: TypedExpression) extends TypedExpression(t)

/** MergeMonoid
 */

case class MergeMonoid(t: MonoidType, m: Monoid, e1: TypedExpression, e2: TypedExpression) extends TypedExpression(t)

/** Comprehension
 */

case class Comprehension(t: MonoidType, m: Monoid, e: TypedExpression, gs: List[Generator], pred: TypedExpression) extends TypedExpression(t)

/** Path (Generator)
 */

abstract class Path(val monoidType: MonoidType)
case class VariablePath(v: Variable) extends Path(v.monoidType)
case class InnerPath(p: Path, name: String) extends Path(p.monoidType match { 
  case RecordType(atts) => {
    atts.find(_.name == name) match {
       case Some(att) => att.monoidType
       case _ => throw RawInternalException("badly formed inner path")
    }
  }
  case _ => throw RawInternalException("unexpected type in inner path")
})

/** Generator
 */

case class Generator(v: Variable, p: Path) extends UntypedExpression

/** Unary Functions
 * 
 * TODO: Why aren't unary functions included in [1] (Fig. 2, page 12)?
 */

case class Not(e: TypedExpression) extends TypedExpression(BoolType)

/** PathPrettyPrinter
 */

object PathPrettyPrinter {
  def apply(p: Path): String = p match {
    case VariablePath(v) => CalculusPrettyPrinter(v)
    case InnerPath(p, name) => PathPrettyPrinter(p) + "." + name
  }
}

/** CalculusPrettyPrinter
 */

object CalculusPrettyPrinter { 
  def apply(e: Expression, pre: String = ""): String = pre + (e match {
    case Null() => "null"
    case BoolConst(v) => if (v) "true" else "false"
    case IntConst(v) => v.toString()
    case FloatConst(v) => v.toString()
    case StringConst(v) => "\"" + v.toString() + "\""
    case Variable(v) => calculus.normalizer.CalculusPrettyPrinter(v)
    case RecordProjection(_, e, name) => CalculusPrettyPrinter(e) + "." + name
    case RecordConstruction(_, atts) => "( " + atts.map(att => att.name + " := " + CalculusPrettyPrinter(att.e)).mkString(", ") + " )"
    case IfThenElse(_, e1, e2, e3) => "if " + CalculusPrettyPrinter(e1) + " then " + CalculusPrettyPrinter(e2) + " else " + CalculusPrettyPrinter(e3)
    case BinaryOperation(_, op, e1, e2) => "( " + CalculusPrettyPrinter(e1) + " " + BinaryOperatorPrettyPrinter(op) + " " + CalculusPrettyPrinter(e2) + " )"
    case EmptySet() => "{}"
    case EmptyBag() => "bag{}"
    case EmptyList() => "[]"
    case ConsCollectionMonoid(_, SetMonoid(), e) => "{ " + CalculusPrettyPrinter(e) + " }"
    case ConsCollectionMonoid(_, BagMonoid(), e) => "bag{ " + CalculusPrettyPrinter(e) + " }"
    case ConsCollectionMonoid(_, ListMonoid(), e) => "[ " + CalculusPrettyPrinter(e) + " ]"
    case MergeMonoid(_, m, e1, e2) => "( " + CalculusPrettyPrinter(e1) + " " + MonoidPrettyPrinter(m) + " " + CalculusPrettyPrinter(e2) + " )"
    case Comprehension(_, m, e, qs, pred) => 
      "for ( " + (if (!qs.isEmpty) qs.map(CalculusPrettyPrinter(_)).mkString(", ") + ", ") + CalculusPrettyPrinter(pred) + " ) yield " + MonoidPrettyPrinter(m) + " " + CalculusPrettyPrinter(e)
    case Generator(v, p) => CalculusPrettyPrinter(v) + " <- " + PathPrettyPrinter(p)
    case Not(e) => "not(" + CalculusPrettyPrinter(e) + ")"
  })
}