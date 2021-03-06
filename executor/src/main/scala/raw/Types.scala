package raw

import raw.calculus.SymbolTable

import scala.collection.immutable.{Seq, Set}

/** Types
  */
sealed abstract class Type extends RawNode

/** Primitive Types
  */
case class BoolType() extends Type

case class StringType() extends Type

case class IntType() extends Type

case class FloatType() extends Type

case class DateTimeType(tz: Boolean) extends Type

case class DateType() extends Type

case class TimeType(tz: Boolean) extends Type

sealed abstract class IntervalField extends RawNode
case class Year() extends IntervalField
case class Month() extends IntervalField
case class Day() extends IntervalField
case class Hour() extends IntervalField
case class Minute() extends IntervalField
case class Second() extends IntervalField

case class IntervalAttr(field: IntervalField, v: Int) extends RawNode

case class IntervalType(intervals: Seq[IntervalAttr]) extends Type

case class RegexType() extends Type

/** Option Type
  */
case class OptionType(t: Type) extends Type

/** Record Type
 *
 */

case class AttrType(idn: String, tipe: Type) extends RawNode

sealed abstract class RecordAttributes extends RawNode {
  def atts: Iterable[AttrType]

  def getType(idn: String): Option[Type]
}

sealed abstract class VariableAttributes extends RecordAttributes {
  def sym: calculus.Symbol
}

/** Attributes (fixed-size, well known)
 */

case class Attributes(atts: Seq[AttrType]) extends RecordAttributes {
  def getType(idn: String): Option[Type] = atts.filter { case att => att.idn == idn }.map(_.tipe).headOption
}

/** Attributes Variable (a set of some known attributes)
  */

case class AttributesVariable(atts: Set[AttrType], sym: calculus.Symbol = SymbolTable.next()) extends VariableAttributes {
  def getType(idn: String): Option[Type] = atts.filter { case att => att.idn == idn }.map(_.tipe).headOption
}

/** Concatenation of attributes.
 */

case class ConcatAttributes(sym: calculus.Symbol = SymbolTable.next()) extends VariableAttributes {
  // TODO: Fix hierarchy: remove atts and getType(idn: String)
  def getType(idn: String) = ???
  def atts = ???
}

//
//// TODO: Add pattern idn to each sequence of atts
//case class ConcatAttributes(atts: Seq[AttrType] = Seq(), sym: Symbol = SymbolTable.next()) extends RecordAttributes {
//  def getType(idn: String) = ???
//}
//
//case class ConcatAttrSeq(prefix: String, t: Type)
//
//case class ConcatAttributes(catts: Seq[ConcatAttrSeq], sym: Symbol = SymbolTable.next()) extends RecordAttributes {
//  // TODO: Fix hierarchy
//  def getType(idn: String) = ???
//  def atts = ???
//}
//
///** Variable and Concatenated attributes.
//  */
//
//case class VarConcatAttributes(varSets: Set[AttributesVariable], concatSets: Set[ConcatAttributes], sym: Symbol = SymbolTable.next()) extends RecordAttributes {
//  def getType(idn: String) = ???
//  def atts = ???
//}

// concat attributes contained in another
// suppose the inner one is resolved (to a fixed size thing)
// then the outer one may do some progress as well
// ok.
// that's where my other impl may help
// but what is the root then?
// well if concat attributes itself had nothing, could be a new concat attributes just for the sake of it
// ok, gotcha

/** Record Type
  */

case class RecordType(recAtts: RecordAttributes) extends Type {
  def getType(idn: String): Option[Type] = recAtts.getType(idn)
}

/** Collection Type
  */
case class CollectionType(m: CollectionMonoid, innerType: Type) extends Type

/** Function Type
  */
case class FunType(ins: Seq[Type], out: Type) extends Type

/** Any Type
  * The top type.
  */
case class AnyType() extends Type

/** Nothing Type
  * The bottom type.
  */
case class NothingType() extends Type

/** User Type.
  * User-defined data type.
  */
case class UserType(sym: calculus.Symbol) extends Type

/** Abstract class representing all types that vary: TypeVariable, ...
  */
// TODO: Remove VariableType and use TypeVariable

sealed abstract class VariableType extends Type {
  def sym: calculus.Symbol
}

/** Type Variable
  */
case class TypeVariable(sym: calculus.Symbol = SymbolTable.next()) extends VariableType

/** Type Scheme
  * TODO: Describe.
  */
case class FreeSymbols(typeSyms: Set[calculus.Symbol], monoidSyms: Set[calculus.Symbol], attSyms: Set[calculus.Symbol]) {
  def isEmpty = typeSyms.isEmpty && monoidSyms.isEmpty && attSyms.isEmpty
  def nonEmpty = !isEmpty
}

case class TypeScheme(t: Type, freeSyms: FreeSymbols) extends Type
