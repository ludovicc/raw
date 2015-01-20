package raw

/** Base class for calculus/canonical calculus pretty printers.
  */
class PrettyPrinter extends org.kiama.output.PrettyPrinter {

  def unaryOp(op: UnaryOperator) = op match {
    case _: Not      => "not"
    case _: Neg      => "-"
    case _: ToBool   => "to_bool"
    case _: ToInt    => "to_int"
    case _: ToFloat  => "to_float"
    case _: ToString => "to_string"
  }

  def binaryOp(op: BinaryOperator): Doc = op match {
    case _: Eq  => "="
    case _: Neq => "<>"
    case _: Ge  => ">="
    case _: Gt  => ">"
    case _: Le  => "<="
    case _: Lt  => "<"
    case _: Sub => "-"
    case _: Div => "/"
  }

  def merge(m: Monoid): Doc = m match {
    case _: MaxMonoid      => "max"
    case _: MultiplyMonoid => "*"
    case _: SumMonoid      => "+"
    case _: AndMonoid      => "and"
    case _: OrMonoid       => "or"
    case _: BagMonoid      => "bag_union"
    case _: SetMonoid      => "union"
    case _: ListMonoid     => "append"
  }

  def monoid(m: Monoid): Doc = m match {
    case _: MaxMonoid      => "max"
    case _: MultiplyMonoid => "multiply"
    case _: SumMonoid      => "sum"
    case _: AndMonoid      => "and"
    case _: OrMonoid       => "or"
    case _: BagMonoid      => "bag"
    case _: SetMonoid      => "set"
    case _: ListMonoid     => "list"
  }

  def collection(m: CollectionMonoid, d: Doc): Doc = m match {
    case _: BagMonoid  => "bag{" <+> d <+> "}"
    case _: SetMonoid  => "{" <+> d <+> "}"
    case _: ListMonoid => "[" <+> d <+> "]"
  }

  def tipe(t: Type): Doc = t match {
    case _: BoolType                  => "bool"
    case _: StringType                => "string"
    case _: FloatType                 => "float"
    case _: IntType                   => "int"
    case RecordType(atts)             => "record" <> list(atts.toList, prefix = "", elemToDoc = (att: AttrType) => att.idn <+> "=" <+> tipe(att.tipe))
    case CollectionType(m, innerType) => monoid(m) <> parens(tipe(innerType))
    case ClassType(idn)               => idn
    case FunType(t1, t2)              => tipe(t1) <+> "->" <+> tipe(t2)
    case _: NoType                    => "none"
    case _: UnknownType               => "unknown"
  }

}