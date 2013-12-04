package raw.algebra

import raw._

/** Expression for Algebra
 */

sealed abstract class Expression(val expressionType: ExpressionType)

/** Constant
 */

sealed abstract class Constant(t: PrimitiveType) extends Expression(Primitive(t))
case class BoolConst(v: Boolean) extends Constant(BoolType)
case class IntConst(v: Long) extends Constant(IntType)
case class FloatConst(v: Double) extends Constant(FloatType)
case class StringConst(v: String) extends Constant(StringType)

/** Variable
 */

case class Variable(v: calculus.canonical.Variable) extends Expression(v.monoidType match {
  case p : PrimitiveType => Primitive(p)
  case r : RecordType => Record(r)})

/** RecordProjection
 */

case class RecordProjection(t: PrimitiveType, e: Expression, name: String) extends Expression(Primitive(t))

/** RecordConstruction
 */

case class AttributeConstruction(name: String, e: Expression)
case class RecordConstruction(t: RecordType, atts: List[AttributeConstruction]) extends Expression(Record(t))

/** IfThenElse
 */

case class IfThenElse(t: PrimitiveType, e1: Expression, e2: Expression, e3: Expression) extends Expression(Primitive(t))

/** BinaryOperation
 */

case class BinaryOperation(t: PrimitiveType, op: BinaryOperator, e1: Expression, e2: Expression) extends Expression(Primitive(t))

/** MergeMonoid
 */

case class MergeMonoid(t: PrimitiveType, m: PrimitiveMonoid, e1: Expression, e2: Expression) extends Expression(Primitive(t))

/** Unary Functions
 * 
 * TODO: Why aren't unary functions included in [1] (Fig. 2, page 12)?
 */

case class Not(e: Expression) extends Expression(Primitive(BoolType))

/** ExpressionPrettyPrinter
 */

object ExpressionPrettyPrinter { 
  def apply(e: Expression, pre: String = ""): String = pre + (e match {
    case BoolConst(v) => if (v) "true" else "false"
    case IntConst(v) => v.toString()
    case FloatConst(v) => v.toString()
    case StringConst(v) => "\"" + v.toString() + "\""
    case Variable(v) => calculus.canonical.CalculusPrettyPrinter(v)
    case RecordProjection(_, e, name) => ExpressionPrettyPrinter(e) + "." + name
    case RecordConstruction(_, atts) => "( " + atts.map(att => att.name + " := " + ExpressionPrettyPrinter(att.e)).mkString(", ") + " )"
    case IfThenElse(_, e1, e2, e3) => "if " + ExpressionPrettyPrinter(e1) + " then " + ExpressionPrettyPrinter(e2) + " else " + ExpressionPrettyPrinter(e3)
    case BinaryOperation(_, op, e1, e2) => "( " + ExpressionPrettyPrinter(e1) + " " + BinaryOperatorPrettyPrinter(op) + " " + ExpressionPrettyPrinter(e2) + " )"
    case MergeMonoid(_, m, e1, e2) => "( " + ExpressionPrettyPrinter(e1) + " " + MonoidPrettyPrinter(m) + " " + ExpressionPrettyPrinter(e2) + " )"
    case Not(e) => "not(" + ExpressionPrettyPrinter(e) + ")"
  })
}