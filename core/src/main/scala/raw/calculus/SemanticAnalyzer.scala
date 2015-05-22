package raw
package calculus

import com.typesafe.scalalogging.LazyLogging
import org.kiama.attribution.Attribution

/** Analyzes the semantics of an AST.
  * This includes the type checker and monoid composition.
  *
  * The semantic analyzer reads the user types and the catalog of user-defined class entities from the World object.
  * User types are the type definitions available to the user.
  * Class entities are the data sources available to the user.
  *   e.g., in expression `e <- Events` the class entity is `Events`.
  */
class SemanticAnalyzer(tree: Calculus.Calculus, world: World) extends Attribution with LazyLogging {

  import org.kiama.==>
  import org.kiama.attribution.Decorators
  import org.kiama.rewriting.Rewriter._
  import org.kiama.util.{Entity, MultipleEntity, UnknownEntity}
  import org.kiama.util.Messaging.{check, collectmessages, Messages, message, noMessages}
  import Calculus._
  import SymbolTable._

  /** Decorators on the tree.
    */
  private lazy val decorators = new Decorators(tree)

  import decorators.{chain, Chain}

  /** The semantic errors for the tree.
    */
  lazy val errors: Messages =
    collectmessages(tree) {
      case n => check(n) {

        // Variable declared more than once in the same comprehension
        case d @ IdnDef(i, _) if entity(d) == MultipleEntity() =>
          message(d, s"$i is declared more than once")

        // Identifier used without being declared
        case u @ IdnUse(i) if entity(u) == UnknownEntity() =>
          message(u, s"$i is not declared")

        // Variable declared has no inferred type
        case d @ IdnDef(i, _) if entityType(entity(d)) == NothingType() =>
          message(d, s"$i has no type")

        // Identifier used has no inferred type
        case u @ IdnUse(i) if entityType(entity(u)) == NothingType() =>
          message(u, s"$i has no type")

        // Check whether pattern structure matches expression type
        case PatternBind(p, e) => patternCompatible(p, tipe(e), e)
        case PatternGen(p, e)  => tipe(e) match {
          case t: CollectionType => patternCompatible(p, t.innerType, e)
          case _                 => noMessages // Initial error is already signaled elsewhere
        }

        case e: Exp =>
          // Mismatch between type expected and actual type
          message(e, s"expected ${expectedType(e).map{ case p => PrettyPrinter(p) }.mkString(" or ")} got ${PrettyPrinter(tipe(e))}",
            !typesCompatible(e)) ++
            check(e) {

              // Semantic error in monoid composition
              case Comp(m, qs, _) =>
                qs.flatMap {
                  case Gen(_, g)        => monoidsCompatible(m, g)
                  case PatternGen(_, g) => monoidsCompatible(m, g)
                  case _                => noMessages
                }.toVector
            }
      }
    }

  // Return error messages if the pattern is not compatible with the type.
  // `e` is only used as a marker to position the error message
  private def patternCompatible(p: Pattern, t: Type, e: Exp): Messages = (p, t) match {
    case (PatternProd(ps), RecordType(atts, _)) =>
      if (ps.length != atts.length)
        message(e, s"expected record with ${ps.length} attributes but got record with ${atts.length} attributes")
      else
        ps.zip(atts).flatMap { case (p1, att) => patternCompatible(p1, att.tipe, e) }.toVector
    case (p1: PatternProd, t1) =>
      message(e, s"expected ${PrettyPrinter(patternType(p1))} but got ${PrettyPrinter(t1)}")
    case (_: PatternIdn, _) => noMessages
  }

  // Return error message if the monoid is not compatible with the generator expression type.
  private def monoidsCompatible(m: Monoid, g: Exp) = tipe(g) match {
    case _: SetType =>
      if (!m.commutative && !m.idempotent)
        message(m, "expected a commutative and idempotent monoid")
      else if (!m.commutative)
        message(m, "expected a commutative monoid")
      else if (!m.idempotent)
        message(m, "expected an idempotent monoid")
      else
        noMessages
    case _: BagType =>
      if (!m.commutative)
        message(m, "expected a commutative monoid")
      else
        noMessages
    case _: ListType =>
      noMessages
    case t =>
      message(g, s"expected collection but got ${PrettyPrinter(t)}")
  }

  // Looks up the identifier in the World. If present, return a a new entity instance.
  private def lookupDataSource(idn: String): Entity =
    if (world.sources.contains(idn))
      DataSourceEntity(idn)
    else
      UnknownEntity()

  lazy val entity: IdnNode => Entity = attr {
    case n @ IdnDef(idn, t) => {
      if (isDefinedInScope(env.in(n), idn))
        MultipleEntity()
      else n match {
        case tree.parent(p) => {
          val nt = t.getOrElse(TypeVariable(new Variable()))

          // Walk up pattern recursively while building a record projection that projects the expression out of the
          // pattern.
          def walk(n: Pattern): (Seq[Int], CalculusNode) = n match {
            case tree.parent(b: PatternBind)      => (Nil, b)
            case tree.parent(g: PatternGen)       => (Nil, g)
            case tree.parent(f: PatternFunAbs)    => (Nil, f)
            case tree.parent(p @ PatternProd(ps)) => val (w, e) = walk(p); (w :+ ps.indexOf(n), e)
          }

          p match {
            case p: Pattern => val (idxs, n1) = walk(p); n1 match {
              case PatternBind(_, e) => PatternBindEntity(nt, e, idxs)
              case PatternGen(_, e)  => PatternGenEntity(nt, e, idxs)
              case _: PatternFunAbs  => FunArgEntity(nt)
            }
            case Bind(_, e)       => BindEntity(nt, e)
            case Gen(_, e)        => GenEntity(nt, e)
            case _: FunAbs        => FunArgEntity(nt)
          }
        }
      }
    }
    case n @ IdnUse(idn) => lookup(env.in(n), idn, lookupDataSource(idn))
  }

  private lazy val env: Chain[Environment] =
    chain(envin, envout)

  private def envin(in: RawNode => Environment): RawNode ==> Environment = {
    case n if tree.isRoot(n) => rootenv()

    // Entering new scopes
    case c: Comp     => enter(in(c))
    case b: ExpBlock => enter(in(b))

    // If we are in a function abstraction, we must open a new scope for the variable argument. But if the parent is a
    // bind, then the `in` environment of the function abstraction must be the same as the `in` environment of the
    // bind.
    case tree.parent.pair(_: FunAbs, b: Bind)               => enter(env.in(b))
    case tree.parent.pair(_: FunAbs, b: PatternBind)        => enter(env.in(b))
    case tree.parent.pair(_: PatternFunAbs, b: Bind)        => enter(env.in(b))
    case tree.parent.pair(_: PatternFunAbs, b: PatternBind) => enter(env.in(b))
    case f: FunAbs                                          => enter(in(f))
    case f: PatternFunAbs                                   => enter(in(f))

    // If we are in an expression and the parent is a bind or a generator, then the `in` environment of the expression
    // is the same as that of the parent: the environment does not include the left hand side of the assignment.
    case tree.parent.pair(_: Exp, b: Bind)        => env.in(b)
    case tree.parent.pair(_: Exp, b: PatternBind) => env.in(b)
    case tree.parent.pair(_: Exp, g: Gen)         => env.in(g)
    case tree.parent.pair(_: Exp, g: PatternGen)  => env.in(g)
  }

  private def envout(out: RawNode => Environment): RawNode ==> Environment = {
    // Leaving a scope
    case c: Comp     => leave(out(c))
    case b: ExpBlock => leave(out(b))

    // The `out` environment of a function abstraction must remove the scope that was inserted.
    case f: FunAbs        => leave(out(f))
    case f: PatternFunAbs => leave(out(f))

    // A new variable was defined in the current scope.
    case n @ IdnDef(i, _) => define(out(n), i, entity(n))

    // The `out` environment of a bind or generator is the environment after the assignment.
    case Bind(idn, _)      => env(idn)
    case PatternBind(p, _) => env(p)
    case Gen(idn, _)       => env(idn)
    case PatternGen(p, _)  => env(p)

    // Expressions cannot define new variables, so their `out` environment is always the same as their `in`
    // environment. The chain does not need to go "inside" the expression to finding any bindings.
    case e: Exp => env.in(e)
  }

  // The expected type of an expression.
  private lazy val expectedType: Exp => Set[Type] = attr {

    case tree.parent.pair(e, p) => p match {
      // Record projection must be over a record type that contains the identifier to project
      case RecordProj(_, idn) => Set(RecordType(List(AttrType(idn, AnyType())), None))

      // If condition must be a boolean
      case IfThenElse(e1, _, _) if e eq e1 => Set(BoolType())

      // Arithmetic operation must be over Int or Float
      case BinaryExp(_: ArithmeticOperator, e1, _) if e eq e1 => Set(IntType(), FloatType())

      // The type of the right-hand-side of a binary expression must match the type of the left-hand-side
      case BinaryExp(_, e1, e2) if e eq e2 => Set(tipe(e1))

      // Function application must be on a function type
      case FunApp(f, _) if e eq f => Set(FunType(AnyType(), AnyType()))

      // Mismatch in function application
      case FunApp(f, e1) if e eq e1 => tipe(f) match {
        case FunType(t1, _) => Set(t1)
        case _              => Set(NothingType())
      }

      // Merging number monoids requires a number type
      case MergeMonoid(_: NumberMonoid, e1, _) if e eq e1 => Set(IntType(), FloatType())

      // Merging boolean monoids requires a bool type
      case MergeMonoid(_: BoolMonoid, e1, _)   if e eq e1 => Set(BoolType())

      // Merge of collections must be with same monoid collection types
      case MergeMonoid(_: BagMonoid, e1, _)  if e eq e1 => Set(BagType(AnyType()))
      case MergeMonoid(_: ListMonoid, e1, _) if e eq e1 => Set(ListType(AnyType()))
      case MergeMonoid(_: SetMonoid, e1, _)  if e eq e1 => Set(SetType(AnyType()))

      // The type of the right-hand-side of a merge expression must match the type of the left-hand-side
      case MergeMonoid(_, e1, e2) if e eq e2 => Set(tipe(e1))

      // Comprehension with a primitive monoid must have compatible projection type
      case Comp(_: NumberMonoid, _, e1) if e eq e1 => Set(IntType(), FloatType())
      case Comp(_: BoolMonoid, _, e1)   if e eq e1 => Set(BoolType())

      // Qualifiers that are expressions must be predicates
      case Comp(_, qs, _) if qs.contains(e) => Set(BoolType())

      // Expected types of unary expressions
      case UnaryExp(_: Neg, _)      => Set(IntType(), FloatType())
      case UnaryExp(_: Not, _)      => Set(BoolType())
      case UnaryExp(_: ToBool, _)   => Set(IntType(), FloatType())
      case UnaryExp(_: ToInt, _)    => Set(BoolType(), FloatType())
      case UnaryExp(_: ToFloat, _)  => Set(BoolType(), IntType())
      case UnaryExp(_: ToString, _) => Set(BoolType(), IntType(), FloatType())

      case _ => Set(AnyType())
    }
    case _ => Set(AnyType()) // There is no parent, i.e. the root node.
  }

  /** Checks for type compatibility between expected and actual types of an expression.
    * Uses type unification but overrides it to handle the special case of record projections.
    */
  private def typesCompatible(e: Exp): Boolean = {
    val actual = tipe(e)
    // Type checker keeps quiet on nothing types because the actual error will be signalled in one of its children
    // through the `expectedType` comparison.
    if (actual == NothingType())
      return true
    for (expected <- expectedType(e)) {
      // Type checker keeps quiet on nothing types because the actual error will be signalled in one of its children
      // through the `expectedType` comparison.
      if (expected == NothingType())
        return true

      (expected, actual) match {
        case (RecordType(atts1, _), RecordType(atts2, _)) =>
          // Handle the special case of an expected type being a record type containing a given identifier.
          val idn = atts1.head.idn
          if (atts2.collect { case AttrType(`idn`, _) => true }.nonEmpty) return true
        case _ => if (unify(expected, actual) != NothingType()) return true
      }
    }
    false
  }

  // TODO: Move it into a separate class to isolate its behavior with an interface!
  private var variableMap = scala.collection.mutable.Map[Variable, Type]()

  private def getVariable(v: Variable) = {
    if (!variableMap.contains(v))
      variableMap.put(v, AnyType())
    variableMap(v)
  }

  lazy val tipe: Exp => Type = {
    case e => {
      // Run `pass1` from the root for its side-effect, which is to type the nodes and build the `variableMap`.
      // Subsequent runs are harmless since they hit the cached value.
      pass1(tree.root)

      def walk(t: Type): Type = t match {
        case TypeVariable(v)        => walk(getVariable(v))
        case RecordType(atts, name) => RecordType(atts.map { case AttrType(iAtt, tAtt) => AttrType(iAtt, walk(tAtt)) }, name)
        case ListType(innerType)    => ListType(walk(innerType))
        case SetType(innerType)     => SetType(walk(innerType))
        case BagType(innerType)     => BagType(walk(innerType))
        case FunType(aType, eType)  => FunType(walk(aType), walk(eType))
        case _                      => t
      }

      walk(pass1(e))
    }
  }

  // Build a new expression with the records projected.
  private def projectPattern(e: Exp, idxs: Seq[Int]): Exp = idxs match {
    case idx :: rest => projectPattern(RecordProj(e, s"_${idx + 1}"), rest)
    case Nil         => e
  }

  // Return the type corresponding to a given list of pattern indexes.
  private def indexesType(t: Type, idxs: Seq[Int]): Type = idxs match {
    case idx :: rest => t match {
      case RecordType(atts, _) if atts.length > idx => indexesType(atts(idx).tipe, rest)
      case _                                        => NothingType()
    }
    case Nil         => t
  }

  private lazy val entityType: Entity => Type = attr {
    case BindEntity(t, e)              => unify(t, pass1(e))
    case PatternBindEntity(t, e, idxs) => unify(t, pass1(projectPattern(e, idxs)))

    case GenEntity(t, e)              => pass1(e) match {
      case c: CollectionType => unify(t, c.innerType)
      case _ => NothingType()
    }
    case PatternGenEntity(t, e, idxs) => pass1(e) match {
      case c: CollectionType => unify(t, indexesType(c.innerType, idxs))
      case _ => NothingType()
    }

    case FunArgEntity(t)              => t

    case DataSourceEntity(name) => world.sources(name)

    case _: UnknownEntity => NothingType()
  }

  private def idnType(idn: IdnNode): Type = entityType(entity(idn))

  private def pass1(e: Exp): Type = realPass1(e) match {
    case UserType(name) => world.userTypes.get(name) match {
      case Some(t) => t
      case _       => NothingType()
    }
    case t => t
  }

  private lazy val realPass1: Exp => Type = attr {

    // Rule 1
    case _: BoolConst   => BoolType()
    case _: IntConst    => IntType()
    case _: FloatConst  => FloatType()
    case _: StringConst => StringType()

    // Rule 2
    case _: Null => TypeVariable(new Variable())

    // Rule 3
    case IdnExp(idn) => idnType(idn)

    // Rule 4
    case RecordProj(e, idn) => pass1(e) match {
      case RecordType(atts, _) => atts.find(_.idn == idn) match {
        case Some(att: AttrType) => att.tipe
        case _                   => NothingType()
      }
      case _                   => AnyType()
    }

    // Rule 5
    case RecordCons(atts) => RecordType(atts.map(att => AttrType(att.idn, pass1(att.e))), None)

    // Rule 6
    case IfThenElse(_, e2, e3) => unify(pass1(e2), pass1(e3))

    // Rule 7
    case FunAbs(idn, e)      => FunType(idnType(idn), pass1(e))
    case PatternFunAbs(p, e) => FunType(patternType(p), pass1(e))

    // Rule 8
    case FunApp(f, _) => pass1(f) match {
      case FunType(_, t2) => t2
      case _ => AnyType()
    }

    // Rule 9
    case ZeroCollectionMonoid(_: BagMonoid)  => BagType(TypeVariable(new Variable()))
    case ZeroCollectionMonoid(_: ListMonoid) => ListType(TypeVariable(new Variable()))
    case ZeroCollectionMonoid(_: SetMonoid)  => SetType(TypeVariable(new Variable()))

    // Rule 10
    case ConsCollectionMonoid(_: BagMonoid, e)  => BagType(pass1(e))
    case ConsCollectionMonoid(_: ListMonoid, e) => ListType(pass1(e))
    case ConsCollectionMonoid(_: SetMonoid, e)  => SetType(pass1(e))

    // Rule 11
    case MergeMonoid(_: BoolMonoid, e1, e2) =>
      unify(pass1(e1), pass1(e2))
      BoolType()
    case MergeMonoid(_: PrimitiveMonoid, e1, e2) =>
      unify(pass1(e1), pass1(e2))

    // Rule 12
    case MergeMonoid(_: CollectionMonoid, e1, e2) => unify(pass1(e1), pass1(e2))

    // Rule 13
    case Comp(m: PrimitiveMonoid, Nil, e) => pass1(e)

    // Rule 14
    case Comp(_: BagMonoid, Nil, e)  => BagType(pass1(e))
    case Comp(_: ListMonoid, Nil, e) => ListType(pass1(e))
    case Comp(_: SetMonoid, Nil, e)  => SetType(pass1(e))

    // Rule 15
    case Comp(m, (_: Gen) :: r, e1) => pass1(Comp(m, r, e1))
    case Comp(m, (_: PatternGen) :: r, e1) => pass1(Comp(m, r, e1))

    // Rule 16
    case Comp(m, (_: Exp) :: r, e1) => pass1(Comp(m, r, e1))

    // Skip Binds
    case Comp(m, (_: Bind) :: r, e1) => pass1(Comp(m, r, e1))
    case Comp(m, (_: PatternBind) :: r, e1) => pass1(Comp(m, r, e1))

    // Binary Expression type
    case BinaryExp(_: EqualityOperator, e1, e2) =>
      unify(pass1(e1), pass1(e2))
      BoolType()

    case BinaryExp(_: ComparisonOperator, e1, e2) =>
      unify(pass1(e1), pass1(e2))
      BoolType()

    case BinaryExp(_: ArithmeticOperator, e1, e2) =>
      unify(pass1(e1), pass1(e2))

    // Unary Expression type
    case UnaryExp(_: Not, _)      => BoolType()
    case UnaryExp(_: Neg, e)      => pass1(e)
    case UnaryExp(_: ToBool, _)   => BoolType()
    case UnaryExp(_: ToInt, _)    => IntType()
    case UnaryExp(_: ToFloat, _)  => FloatType()
    case UnaryExp(_: ToString, _) => StringType()

    // Expression block type
    case ExpBlock(_, e) => pass1(e)

    case _ => NothingType()
  }

  // Return the type corresponding to a given pattern
  private def patternType(p: Pattern): Type = p match {
    case PatternIdn(idn) => idnType(idn)
    case PatternProd(ps) => RecordType(ps.zipWithIndex.map{ case (p1, idx) => AttrType(s"_${idx + 1}", patternType(p1))}, None)
  }

  /** Hindley-Milner unification algorithm.
    */
  private def unify(t1: Type, t2: Type): Type = (t1, t2) match {
    case (n: NothingType, _) => n
    case (_, n: NothingType) => n
    case (_: AnyType, t) => t
    case (t, _: AnyType) => t
    case (a: PrimitiveType, b: PrimitiveType) if a == b => a
    case (BagType(a), BagType(b)) => BagType(unify(a, b))
    case (ListType(a), ListType(b)) => ListType(unify(a, b))
    case (SetType(a), SetType(b)) => SetType(unify(a, b))
    case (FunType(a1, a2), FunType(b1, b2)) => FunType(unify(a1, b1), unify(a2, b2))
    case (RecordType(atts1, Some(name1)), RecordType(atts2, Some(name2))) if atts1.map(_.idn) == atts2.map(_.idn) && name1 == name2 =>
      // Create a record type with same name
      RecordType(atts1.zip(atts2).map { case (att1, att2) => AttrType(att1.idn, unify(att1.tipe, att2.tipe)) }, Some(name1))
    case (RecordType(atts1, _), RecordType(atts2, _)) if atts1.map(_.idn) == atts2.map(_.idn) =>
      // Create an 'anonymous' record type
      RecordType(atts1.zip(atts2).map { case (att1, att2) => AttrType(att1.idn, unify(att1.tipe, att2.tipe)) }, None)
    case (t1@TypeVariable(a), t2@TypeVariable(b)) =>
      if (t1 == t2)
        t1
      else {
        val ta = getVariable(a)
        val tb = getVariable(b)
        val nt = unify(ta, tb)
        variableMap.update(a, nt)
        variableMap.update(b, t1)
        t1
      }
    case (TypeVariable(a), b) =>
      val ta = getVariable(a)
      val nt = unify(ta, b)
      variableMap.update(a, nt)
      nt
    case (a, b: TypeVariable) =>
      unify(b, a)
    case _ => NothingType()
  }

  def debugTreeTypes =
    everywherebu(query[Exp] {
      case n => logger.error(CalculusPrettyPrinter(tree.root, debug = Some({ case `n` => s"[${PrettyPrinter(tipe(n))}] " })))
    })(tree.root)

}