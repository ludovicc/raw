package raw
package calculus

import com.typesafe.scalalogging.LazyLogging
import org.kiama.attribution.Attribution
import raw.World._

import scala.util.parsing.input.Position

case class BaseAnalyzerException(err: String) extends RawException(err)

/** Analyzes the semantics of an AST.
  * This includes the type checker, type inference and semantic checks (e.g. whether monoids compose correctly).
  *
  * The semantic analyzer reads the user types and the catalog of user-defined class entities from the World object.
  * User types are the type definitions available to the user.
  * Class entities are the data sources available to the user.
  * e.g., in expression `e <- Events` the class entity is `Events`.
  *
  * The original user query is passed optionally for debugging purposes.
  */
class BaseAnalyzer(val tree: Calculus.Calculus, val world: World, val queryString: String) extends Attribution with Analyzer with MonoidsGraph with NodePosition with RegexAnalyzer with DateTimeFormatAnalyzer with LazyLogging {

  // TODO: Add a check to the semantic analyzer that the monoids are no longer monoid variables; they have been sorted out

  import scala.collection.immutable.Seq
  import org.kiama.==>
  import org.kiama.attribution.Decorators
  import org.kiama.util.{Entity, MultipleEntity, UnknownEntity}
  import org.kiama.rewriting.Rewriter._
  import Calculus._
  import SymbolTable._
//  import Constraint._
  import World.TypesVarMap

  /** Decorators on the tree.
    */
  private lazy val decorators = new Decorators(tree)

  import decorators.{chain, Chain}

  private val recAttsVarMap = new RecordAttributesVarMap()

  /** The map of type variables.
    * Updated during unification.
    */
  // TODO: Make it private and hide its use under an API
  protected val typesVarMap = new TypesVarMap()

  /** Add user types to the map.
    */
  for ((sym, t) <- world.tipes) {
    typesVarMap.union(UserType(sym), t)
  }

  /** Add type variables from user sources.
    */
  for ((_, t) <- world.sources) {
    for (tv <- getVariableTypes(t)) {
      typesVarMap.union(tv, tv)
    }
  }

  /** Stores unification errors.
    * Updated during unification.
    */
  private val tipeErrors =
    scala.collection.mutable.MutableList[CalculusError]()

  /** Type the root of the program.
    */
  lazy val solution = solve(tree.root)

  /** Return the base type of an expression, i.e. without the nullable flag.
    */
  lazy val baseType: Exp => Type = attr {
    e => {
      solution
      walk(expType(e))
    }
  }

  /** Return the type after resolving user types.
    */
  def resolvedType(t: Type, occursCheck: Set[Type] = Set()): Type =
    if (occursCheck.contains(t))
      throw new BaseAnalyzerException("Cycle in user type definition")
    else t match {
      case UserType(sym) => resolvedType(world.tipes(sym), occursCheck + t)
      case _ => t
    }

  /** Extractor that resolves user types.
    */
  object ResolvedType {
    def unapply(t: Type): Option[Type] = Some(resolvedType(t))
  }

  def cloneType(t: Type): Type = {
    val nt = t match {
      case _: BoolType => BoolType()
      case _: IntType => IntType()
      case _: FloatType => FloatType()
      case _: StringType => StringType()
      case _: DateTimeType => DateTimeType()
      case _: IntervalType => IntervalType()
      case _: RegexType => RegexType()
      case FunType(t1, t2) => FunType(cloneType(t1), cloneType(t2))
      case RecordType(Attributes(atts)) => RecordType(Attributes(atts.map { case AttrType(idn, t1) => AttrType(idn, cloneType(t1))}))
      case RecordType(AttributesVariable(atts, sym)) => RecordType(AttributesVariable(atts.map { case AttrType(idn, t1) => AttrType(idn, cloneType(t1))}, sym))
      case RecordType(ConcatAttributes(sym)) => RecordType(ConcatAttributes(sym))
      case PatternType(atts) => PatternType(atts.map { case PatternAttrType(t1) => PatternAttrType(cloneType(t1))})
      case CollectionType(m, inner) => CollectionType(m, cloneType(inner))
      case NumberType(sym) => NumberType(sym)
      case PrimitiveType(sym) => PrimitiveType(sym)
      case TypeVariable(sym) => TypeVariable(sym)
      case UserType(sym) => UserType(sym)
      case TypeScheme(t1, typeSyms, monoidSyms, attSyms) => TypeScheme(cloneType(t1), typeSyms, monoidSyms, attSyms)
      case _: AnyType => AnyType()
      case _: NothingType => NothingType()
    }
    nt.nullable = t.nullable
    nt
  }

  /** Type checker errors.
    */
  // TODO: Add check that the *root* type (and only the root type) does not contain ANY type variables, or we can't generate code for it
  // TODO: And certainly no NothingType as well...
  lazy val errors: Seq[RawError] = {
    solution // Must attempt to type the entire program to collect all errors
    dateTimeFormatErrors ++ regexErrors ++ badEntities ++ tipeErrors
  }

  private lazy val collectBadEntities =
    collect[List, CalculusError] {
      // Identifier declared more than once in the same scope
      case i: IdnDef if entity(i) == MultipleEntity() =>
        MultipleDecl(i, Some(parserPosition(i)))
    }

  private lazy val badEntities = collectBadEntities(tree.root)

  /** Looks up the identifier in the World and returns a new entity instance.
    */
  private def lookupDataSource(idn: String): Entity =
    if (world.sources.contains(idn))
      DataSourceEntity(Symbol(idn))
    else
      UnknownEntity()

  /** The entity of an identifier.
    */
  lazy val entity: IdnNode => Entity = attr {
    case n @ IdnDef(idn) =>
      if (isDefinedInScope(env.in(n), idn))
        MultipleEntity()
      else
        VariableEntity(n, TypeVariable())
    case n @ IdnUse(idn) =>
      lookup(env.in(n), idn, lookupDataSource(idn))
  }

  /** Chain for looking up identifiers.
    */

  private lazy val env: Chain[Environment] =
    chain(envin, envout)

  private def envin(in: RawNode => Environment): RawNode ==> Environment = {
    case n if tree.isRoot(n) => rootenv()

    // Entering new scopes
    case c: Comp => enter(in(c))
    case b: ExpBlock => enter(in(b))

    // TODO: Refactor if Algebra node, open scope
    case r: Reduce    => enter(in(r))
    case f: Filter    => enter(in(f))
    case j: Join      => enter(in(j))
    case o: OuterJoin => enter(in(o))
    case o: OuterUnnest => enter(in(o))
    case n: Nest => enter(in(n))
    case n: Nest2 => enter(in(n))
    case s: Select => enter(in(s))

    // If we are in a function abstraction, we must open a new scope for the variable argument. But if the parent is a
    // bind, then the `in` environment of the function abstraction must be the same as the `in` environment of the
    // bind.
    case tree.parent.pair(_: FunAbs, b: Bind) => enter(env.in(b))
    case f: FunAbs => enter(in(f))

    // If we are in an expression and the parent is a bind or a generator, then the `in` environment of the expression
    // is the same as that of the parent: the environment does not include the left hand side of the assignment.
    case tree.parent.pair(_: Exp, b: Bind) => env.in(b)
    case tree.parent.pair(_: Exp, g: Gen) => env.in(g)
  }

  private def envout(out: RawNode => Environment): RawNode ==> Environment = {
    // Leaving a scope
    case c: Comp => leave(out(c))
    case b: ExpBlock => leave(out(b))

    // TODO: Refactor if Algebra node, open scope
    case s: Select    => leave(out(s))
    case r: Reduce    => leave(out(r))
    case f: Filter    => leave(out(f))
    case j: Join      => leave(out(j))
    case o: OuterJoin => leave(out(o))
    case o: OuterUnnest => leave(out(o))
    case n: Nest  => leave(out(n))
    case n: Nest2 => leave(out(n))

    // The `out` environment of a function abstraction must remove the scope that was inserted.
    case f: FunAbs => leave(out(f))

    // A new variable was defined in the current scope.
    case n @ IdnDef(i) => define(out(n), i, entity(n))

    // The `out` environment of a bind or generator is the environment after the assignment.
    case Bind(p, _) => env(p)
    case g @ Gen(None, _) => env.in(g)
    case Gen(Some(p), _) => env(p)

    // Expressions cannot define new variables, so their `out` environment is always the same as their `in`
    // environment. The chain does not need to go "inside" the expression to finding any bindings.
    case e: Exp => env.in(e)
  }

  /** Chain for looking up aliases, which are autmaticaly inferred from anonymous generators.
    */

  // TODO Check recordtypes are never empty (record with no fields).

  private lazy val aliasEnv: Chain[Environment] =
    chain(aliasEnvIn, aliasEnvOut)

  private def aliasEnvIn(in: RawNode => Environment): RawNode ==> Environment = {
    case n if tree.isRoot(n) => rootenv()
    case c: Comp             => enter(in(c))
    case b: ExpBlock         => enter(in(b))
    case s: Select           => enter(in(s))
    case f: FunAbs           => enter(in(f))
    case a: LogicalAlgebraNode => enter(in(a))

    // Into node puts all attributes of the lhs (which should be a record) into the scope of the rhs
    case tree.parent.pair(e: Exp, i @ Into(e1, e2)) if e eq e2 =>
      var nenv = enter(in(e))

      def attEntity(env: Environment, att: AttrType, idx: Int) = {
        if (isDefinedInScope(env, att.idn))
          MultipleEntity()
        else
          IntoAttributeEntity(att, i, idx)
      }

      val t = expType(e1)
      val nt = find(t)
      nt match {
        case ResolvedType(RecordType(Attributes(atts))) =>
          logger.debug("bang1")
          for ((att, idx) <- atts.zipWithIndex) {
            logger.debug(s"added idn ${att.idn}")
            nenv = define(nenv, att.idn, attEntity(nenv, att, idx))
          }
        case ResolvedType(RecordType(c: ConcatAttributes)) =>
          logger.debug("bang2")
          val props = getConcatProperties(c)
          for ((att, idx) <- props.atts.zipWithIndex) {
            nenv = define(nenv, att.idn, attEntity(nenv, att, idx))
          }
        case _ =>
      }
      nenv

  }

  private def aliasEnvOut(out: RawNode => Environment): RawNode ==> Environment = {
    case c: Comp     => leave(out(c))
    case b: ExpBlock => leave(out(b))
    case s: Select   => leave(out(s))
    case f: FunAbs   => leave(out(f))
    case a: LogicalAlgebraNode => leave(out(a))
    case tree.parent.pair(e: Exp, Into(_, e2)) if e eq e2 =>
      leave(out(e))
    case g @ Gen(None, e) =>
      def attEntity(env: Environment, att: AttrType, idx: Int) = {
        if (isDefinedInScope(env, att.idn))
          MultipleEntity()
        else
          GenAttributeEntity(att, g, idx)
      }

      val t = expType(e)
      val nt = find(t)
      nt match {
        case CollectionType(_, inner) =>
          val ninner = find(inner)
          ninner match {
            case ResolvedType(RecordType(Attributes(atts))) =>
              var nenv: Environment = out(g)
              for ((att, idx) <- atts.zipWithIndex) {
                nenv = define(nenv, att.idn, attEntity(nenv, att, idx))
              }
              nenv
            case ResolvedType(RecordType(c: ConcatAttributes)) =>
              var nenv: Environment = out(g)
              val props = getConcatProperties(c)
              for ((att, idx) <- props.atts.zipWithIndex) {
                nenv = define(nenv, att.idn, attEntity(nenv, att, idx))
              }
              nenv
            case _ =>
              aliasEnv.in(g)
          }
        case _ =>
          aliasEnv.in(g)
      }
    case n                => aliasEnv.in(n)
  }

  /** Chain for looking up the partition keyword.
    * The partition scope opens on the projection but only if the SELECT has a GROUP BY.
    */

  private lazy val partitionEnv: Chain[Environment] =
    chain(partitionEnvIn, partitionEnvOut)

  private def partitionEnvIn(in: RawNode => Environment): RawNode ==> Environment = {
    case n if tree.isRoot(n) => rootenv()
    case tree.parent.pair(e: Exp, s: Select) if (e eq s.proj) && s.group.isDefined =>
      val env = enter(in(e))
      define(env, "partition", PartitionEntity(s, TypeVariable()))
  }

  private def partitionEnvOut(out: RawNode => Environment): RawNode ==> Environment = {
    case tree.parent.pair(e: Exp, s: Select) if (e eq s.proj) && s.group.isDefined =>
      leave(out(e))
  }

  /** Chain for looking up the star keyword.
    * The star scope opens on the projection for SELECT or the yield for for-comprehensions.
    */

  private lazy val starEnv: Chain[Environment] =
    chain(starEnvIn, starEnvOut)

  private def starEnvIn(in: RawNode => Environment): RawNode ==> Environment = {
    case n if tree.isRoot(n) => rootenv()
    case tree.parent.pair(e: Exp, s: Select) if e eq s.proj =>
      val env = enter(in(e))
      define(env, "*", StarEntity(s, TypeVariable()))

    //    case tree.parent.pair(e: Exp, c: Comp) if e eq c.e =>
    //      // TODO: In the case of a Comp make sure there is at least a Gen or this doesn't make sense!!!! Same on envOut.
    //      val env = enter(in(e))
    //      define(env, "*", StarEntity(c, TypeVariable()))
  }

  private def starEnvOut(out: RawNode => Environment): RawNode ==> Environment = {
    case tree.parent.pair(e: Exp, s: Select) if e eq s.proj =>
      leave(out(e))
    case tree.parent.pair(e: Exp, c: Comp) if e eq c.e =>
      leave(out(e))
  }

  /** lookup up attribute entity.
    */
  lazy val lookupAttributeEntity: IdnExp => Entity = attr {
    // TODO: Why aliasEnv.in and not aliasEnv only ????
    idnExp => lookup(aliasEnv.in(idnExp), idnExp.idn.idn, UnknownEntity())
  }

  /** lookup up partition entity.
    */
  lazy val partitionEntity: Partition => Entity = attr {
    e => lookup(partitionEnv.in(e), "partition", UnknownEntity())
  }

  /** lookup up star entity.
    */
  lazy val starEntity: Star => Entity = attr {
    e => {
      lookup(starEnv.in(e), "*", UnknownEntity())
    }
  }

  /////


  //////

  // TODO: Move this to the Types.scala and have it used inside the TypeScheme definition for uniformity!
  case class FreeSymbols(typeSyms: Set[Symbol], monoidSyms: Set[Symbol], attSyms: Set[Symbol])

  /** Type the rhs of a Bind declaration.
    * If successful, returns a list of free type symbols and free monoid symbols (for polymorphism).
    */
  private lazy val tipeBind: Bind => Option[FreeSymbols] = attr {
    case Bind(p, e) =>
      def aux: Option[FreeSymbols] = {
        // TODO: If the unresolved TypeVariables come from UserType/Source, don't include them as free variables.
        //       Instead, leave them unresolved, unless we want a strategy that resolves them based on usage?

        // Add all pattern identifier types to the map before processing the rhs
        // This call is repeated multiple times in case of a PatternProd on the lhs of the Bind. This is harmless.
        //        patternIdnTypes(p).foreach { case pt => typesVarMap.union(pt, pt) }

        // Collect all the roots known in the TypesVarMap.
        // This will be used to detect "new variables" created within, and not yet in the TypesVarMap.
        val prevTypeRoots = typesVarMap.getRoots
        val prevMonoidRoots = monoidRoots() // monoidsVarMap.getRoots
        val prevRecAttRoots = recAttsVarMap.getRoots

        // Type the rhs body of the Bind
        solve(e)
        val t = expType(e)
        val expected = patternType(p)
        if (!unify(t, expected)) {
          tipeErrors += PatternMismatch(p, walk(t), Some(parserPosition(p)))
          return None
        }

        //        // find all parameter types of inner funabs
        //
        //        val lambda_ptypes = collect[Set, Set[Type]] {
        //          case FunAbs(p, _) => patternIdnTypes(p).toSet
        //        }
        //
        //        val to_walk: Set[Type] = lambda_ptypes(e).flatMap(identity)
        //        val typeVars = to_walk.flatMap(getVariableTypes(_))
        //        val monoidVars = to_walk.flatMap(getVariableMonoids(_))
        //        val attVars = to_walk.flatMap(getVariableAtts(_))
        //        logger.debug(s"${CalculusPrettyPrinter(e)} => types are ${typeVars.map(PrettyPrinter(_))}, eType is ${PrettyPrinter(walk(t))}")

        // we should clone all monoids that were recently created and were not in the map before
        // so indeed, i could walk the old map
        // and, extract all its roots.
        // actually, now whenever i create

        // so i could collect ALL - really, all - monoid (roots) (it's basically keys of monoidProperties)
        // i do the same after
        // and, well, regardless of them being in the type of not, i should clone it all (?)
        // i should clone the ones athat are independent of what was in the map before...

        // BTW, when I create a freshVar, ADD IT TO THE MAP.


        // Find all type variables used in the type
        val typeVars = getVariableTypes(t)
        //        logger.debug(s"typeVars = ")
        //        val monoidVars = getVariableMonoids(t)
        val attVars = getVariableAtts(t)

        // go to the old roots
        // extract its type variables <-- this is new
        // walk thm in the new map: those are the new roots
        //

        // i think the find should be cloning all thnigs internally


        //        so we must clone all things that are new and that are somehow related to this type
        //        but if they are new they are somehow related to this body
        //        so, basically, it's all things new, period.


        // For all the "previous roots", get their new roots
        val prevTypeRootsUpdated = prevTypeRoots.flatMap { case v => getVariableTypes(v) }.map { case v => find(v) }
        val prevMonoidRootsUpdated = prevMonoidRoots.map { case v => mFind(v) }
        val prevRecAttRootsUpdated = prevRecAttRoots.map { case v => aFind(v) }

        // TODO QUESTION TO BEN: why typeVars? why not walk ALL THE ROOTs? arent these things somehow needed/related?

        // Collect all symbols from variable types that were not in the maps before we started typing the body of the Bind.
        val freeTypeSyms = typeVars.filter { case vt => !prevTypeRootsUpdated.contains(vt) }.map(_.sym)
        val freeMonoidSyms = monoidRoots().collect { case v: MonoidVariable => v }.filter { case v => !prevMonoidRootsUpdated.contains(v) }.map(_.sym)
        val freeAttSyms = attVars.filter { case v => !prevRecAttRootsUpdated.contains(v) }.map(_.sym)

        Some(FreeSymbols(freeTypeSyms, freeMonoidSyms, freeAttSyms))
      }
      aux
  }

  /** Return the sequence of types in a pattern.
    */
  private lazy val patternIdnTypes: Pattern => Seq[Type] = attr {
    p => {
      def tipes(p: Pattern): Seq[Type] = p match {
        case PatternIdn(idn) => entity(idn) match {
          case VariableEntity(_, t) => Seq(t)
          case _ => Nil
        }
        case PatternProd(ps) => ps.flatMap(tipes)
      }
      tipes(p)
    }
  }

  private def getVariableTypes(t: Type, occursCheck: Set[Type] = Set()): Set[VariableType] = {
    if (occursCheck.contains(t))
      Set()
    else {
      t match {
        case _: NothingType               => Set()
        case _: AnyType                   => Set()
        case _: BoolType                  => Set()
        case _: IntType                   => Set()
        case _: FloatType                 => Set()
        case _: DateTimeType              => Set()
        case _: IntervalType              => Set()
        case _: RegexType                 => Set()
        case _: StringType                => Set()
        case _: UserType                  => Set()
        case RecordType(r)     =>
          aFind(r) match {
            case a: Attributes => a.atts.flatMap { case att => getVariableTypes(att.tipe, occursCheck + t) }.toSet
            case a: AttributesVariable => a.atts.flatMap { case att => getVariableTypes(att.tipe, occursCheck + t) }
            case c: ConcatAttributes =>
              val cdef = concatDefinition(c)
              cdef.atts.flatMap { case att => getVariableTypes(att.tipe, occursCheck + t) } ++
                cdef.slotsSet.flatMap { case slots => slots.flatMap { case ConcatSlot(_, t1) => getVariableTypes(t1, occursCheck + t)}}
          }

        //        case RecordType(recAtts)          => recAtts.atts.flatMap { case att => getVariableTypes(att.tipe, occursCheck + t) }.toSet
        case PatternType(atts)            => atts.flatMap { case att => getVariableTypes(att.tipe, occursCheck + t) }.toSet
        case CollectionType(_, innerType) => getVariableTypes(innerType, occursCheck + t)
        case FunType(p, e)                => getVariableTypes(p, occursCheck + t) ++ getVariableTypes(e, occursCheck + t)
        case t1: PrimitiveType            => if (typesVarMap.contains(t1) && typesVarMap(t1).root != t1) getVariableTypes(typesVarMap(t1).root, occursCheck + t) else Set(t1)
        case t1: NumberType               => if (typesVarMap.contains(t1) && typesVarMap(t1).root != t1) getVariableTypes(typesVarMap(t1).root, occursCheck + t) else Set(t1)
        case t1: TypeVariable             => if (typesVarMap.contains(t1) && typesVarMap(t1).root != t1) getVariableTypes(typesVarMap(t1).root, occursCheck + t) else Set(t1)
      }
    }
  }

  private def getVariableAtts(t: Type, occursCheck: Set[Type] = Set()): Set[VariableAttributes] = {
    if (occursCheck.contains(t))
      Set()
    else {
      t match {
        case _: NothingType    => Set()
        case _: AnyType        => Set()
        case _: BoolType       => Set()
        case _: IntType        => Set()
        case _: FloatType      => Set()
        case _: StringType     => Set()
        case _: DateTimeType              => Set()
        case _: IntervalType              => Set()
        case _: RegexType                 => Set()
        case _: UserType       => Set()
        case _: PrimitiveType  => Set()
        case _: NumberType     => Set()
        case t1: TypeVariable  =>
          if (typesVarMap.contains(t1) && typesVarMap(t1).root != t1)
            getVariableAtts(typesVarMap(t1).root, occursCheck + t)
          else
            Set()
        case RecordType(r)     =>
          aFind(r) match {
            case a: Attributes => a.atts.flatMap { case att => getVariableAtts(att.tipe, occursCheck + t) }.toSet
            case a: AttributesVariable => Set(a) ++ a.atts.flatMap { case att => getVariableAtts(att.tipe, occursCheck + t) }
            case c: ConcatAttributes =>
              val cdef = concatDefinition(c)
              Set(c) ++
                cdef.atts.flatMap { case att => getVariableAtts(att.tipe, occursCheck + t) } ++
                cdef.slotsSet.flatMap { case slots => slots.flatMap { case ConcatSlot(_, t1) => getVariableAtts(t1, occursCheck + t)}}
          }
        case PatternType(atts) => atts.flatMap { case att => getVariableAtts(att.tipe, occursCheck + t) }.toSet
        case CollectionType(_, innerType) => getVariableAtts(innerType, occursCheck + t)
        case FunType(p, e) => getVariableAtts(p, occursCheck + t) ++ getVariableAtts(e, occursCheck + t)
      }
    }
  }


  /** Return the declaration of an identifier definition.
    */
  protected lazy val decl: IdnDef => Option[Decl] = attr {
    case idn: IdnDef =>
      def getDecl(n: RawNode): Option[Decl] = n match {
        case b: Bind                 => Some(b)
        case g: Gen                  => Some(g)
        case f: FunAbs               => Some(f)
        case tree.parent.pair(_, n1) => getDecl(n1)
        case _                       => None
      }
      getDecl(idn)
  }

  // TODO: Refactor decl vs patDecl: there's repetition

  /** Return the declaration of a pattern.
    */
  protected lazy val patDecl: Pattern => Option[Decl] = attr {
    case p: Pattern =>
      def getDecl(n: RawNode): Option[Decl] = n match {
        case b: Bind                 => Some(b)
        case g: Gen                  => Some(g)
        case f: FunAbs               => Some(f)
        case tree.parent.pair(_, n1) => getDecl(n1)
        case _                       => None
      }
      getDecl(p)
  }

  /** Return the type of an entity.
    * Supports let-polymorphism.
    */
  private lazy val entityType: Entity => Type = attr {
    case VariableEntity(idn, t) =>
      decl(idn) match {
        case Some(b: Bind) => tipeBind(b) match {
          case Some(FreeSymbols(typeSyms, monoidSyms, attSyms)) =>
            TypeScheme(t, typeSyms, monoidSyms, attSyms)
          case None => NothingType()
        }
        case _             => t
      }
    case DataSourceEntity(sym)  => world.sources(sym.idn)
    case _: UnknownEntity       => NothingType()
    case _: MultipleEntity      => NothingType()
  }

  /** Instantiate a new type from a type scheme.
    * Used for let-polymorphism.
    * This method is only called if there are type variables, monoid variables or attribute variables.
    * In that case, a new type must be constructed.
    * Note that variable types/monoids whose symbols are not in typeSyms/monoidSyms are not reconstructed and the same
    * object is used to allow unification to proceed unaffected.
    */
  private def instantiateTypeScheme(t: Type, typeSyms: Set[Symbol], monoidSyms: Set[Symbol], attSyms: Set[Symbol]) = {
    val newSyms = scala.collection.mutable.HashMap[Symbol, Symbol]()

    def getNewSym(sym: Symbol): Symbol = {
      if (!newSyms.contains(sym))
        newSyms += (sym -> SymbolTable.next())
      newSyms(sym)
    }

    val newMonoidSyms = cloneMonoids(monoidSyms)

    def getMonoid(m: Monoid, moccursCheck: Set[Monoid] = Set()): Monoid =
      mFind(m) match {
        case MonoidVariable(sym) if newMonoidSyms.contains(sym) => MonoidVariable(newMonoidSyms(sym))
        case _ => m
      }


    // TODO: DO WE NEED occursCheck HERE?????
    // TODO: And do we even need it in the main method???
    def getAttributes(m: RecordAttributes, occursCheck: Set[Type]): RecordAttributes = {
      val ar = if (recAttsVarMap.contains(m)) recAttsVarMap(m).root else m
      ar match {
        case AttributesVariable(atts, sym) =>
          assert(attSyms.contains(sym))
          AttributesVariable(atts.map { case att => AttrType(att.idn, recurse(att.tipe, occursCheck)) }, getNewSym(sym))
        case c @ ConcatAttributes(sym) =>
          assert(attSyms.contains(sym))
          val cdef = concatDefinition(c)
          val natts = cdef.atts.map { case att => AttrType(att.idn, recurse(att.tipe, occursCheck)) }
          val nslotsSet = cdef.slotsSet.map { case slots => slots.map { case ConcatSlot(prefix, t1) => ConcatSlot(prefix, recurse(t1, occursCheck))}}
          val nc = ConcatAttributes()
          freshConcat(nc, natts, nslotsSet)
          nc
        case Attributes(atts) =>
          Attributes(atts.map { case att => AttrType(att.idn, recurse(att.tipe, occursCheck)) })
      }
    }

    def recurse(t: Type, occursCheck: Set[Type]): Type = {
      if (occursCheck.contains(t))
        t
      else {
        t match {
          case t1 @ TypeVariable(sym) if !typeSyms.contains(sym)  => if (typesVarMap.contains(t1) && typesVarMap(t1).root != t1) recurse(typesVarMap(t1).root, occursCheck + t) else t1
          case t1 @ NumberType(sym) if !typeSyms.contains(sym)    => if (typesVarMap.contains(t1) && typesVarMap(t1).root != t1) recurse(typesVarMap(t1).root, occursCheck + t) else t1
          case t1 @ PrimitiveType(sym) if !typeSyms.contains(sym) => if (typesVarMap.contains(t1) && typesVarMap(t1).root != t1) recurse(typesVarMap(t1).root, occursCheck + t) else t1
          case TypeVariable(sym)             => TypeVariable(getNewSym(sym))
          case NumberType(sym)               => NumberType(getNewSym(sym))
          case PrimitiveType(sym)            => PrimitiveType(getNewSym(sym))
          case _: NothingType                => t
          case _: AnyType                    => t
          case _: IntType                    => t
          case _: BoolType                   => t
          case _: FloatType                  => t
          case _: StringType                 => t
          case _: UserType                   => t
          case RecordType(recAtts)           => RecordType(getAttributes(recAtts, occursCheck + t))
          case PatternType(atts)             => PatternType(atts.map { case att => PatternAttrType(recurse(att.tipe, occursCheck + t)) })
          case c1 @ CollectionType(m, inner) =>
            val nm = getMonoid(m)
            CollectionType(nm.asInstanceOf[CollectionMonoid], recurse(inner, occursCheck + t))
          case FunType(p, e)                 =>
            FunType(recurse(p, occursCheck + t), recurse(e, occursCheck + t))
        }
      }
    }

    recurse(t, Set())
  }

  private def idnType(idn: IdnNode): Type = entityType(entity(idn))

  /** The type corresponding to a given pattern.
    */
  private lazy val patternType: Pattern => Type = attr {
    case PatternIdn(idn) => entity(idn) match {
      case VariableEntity(_, t) => t
      case _                    => NothingType()
    }
    case PatternProd(ps) => PatternType(ps.map { case p => PatternAttrType(patternType(p)) })
  }

  /** The type of an expression.
    * If the type cannot be immediately derived from the expression itself, then type variables are used for unification.
    */
  private lazy val expType: Exp => Type = attr {

    case p: Partition => partitionType(partitionEntity(p))

    case s: Star => starType(starEntity(s))

    // Rule 1
    case _: BoolConst  => BoolType()
    case _: IntConst   => IntType()
    case _: FloatConst => FloatType()
    case _: StringConst => StringType()
    case _: RegexConst => RegexType()

    // Rule 5
    case RecordCons(atts) => RecordType(Attributes(atts.map(att => AttrType(att.idn, expType(att.e)))))

    // Rule 9
    case ZeroCollectionMonoid(_: BagMonoid) => CollectionType(BagMonoid(), TypeVariable())
    case ZeroCollectionMonoid(_: ListMonoid) => CollectionType(ListMonoid(), TypeVariable())
    case ZeroCollectionMonoid(_: SetMonoid) => CollectionType(SetMonoid(), TypeVariable())

    // Rule 10
    case ConsCollectionMonoid(_: BagMonoid, e1) => CollectionType(BagMonoid(), expType(e1))
    case ConsCollectionMonoid(_: ListMonoid, e1) => CollectionType(ListMonoid(), expType(e1))
    case ConsCollectionMonoid(_: SetMonoid, e1) => CollectionType(SetMonoid(), expType(e1))

    // Unary expressions
    case UnaryExp(_: Not, _)     => BoolType()
    case UnaryExp(_: ToBool, _)  => BoolType()
    case UnaryExp(_: ToInt, _)   => IntType()
    case UnaryExp(_: ToFloat, _) => FloatType()
    case UnaryExp(_: ToString, _) => StringType()

    // Sugar expressions

    case MultiCons(_: BagMonoid, Nil)       => CollectionType(BagMonoid(), TypeVariable())
    case MultiCons(_: ListMonoid, Nil)      => CollectionType(ListMonoid(), TypeVariable())
    case MultiCons(_: SetMonoid, Nil)       => CollectionType(SetMonoid(), TypeVariable())
    case MultiCons(_: BagMonoid, es)     => CollectionType(BagMonoid(), expType(es.head))
    case MultiCons(_: ListMonoid, es)    => CollectionType(ListMonoid(), expType(es.head))
    case MultiCons(_: SetMonoid, es)     => CollectionType(SetMonoid(), expType(es.head))

    case _: Count => IntType()
    case _: Exists => BoolType()

    case Into(_, e2) => expType(e2)

      // TOOD: ParseAs
    case ParseAs(_, r, _) => regexType(r)
    case _: ToEpoch => IntType()

    case n => TypeVariable()
  }

  ////

  case class ConcatSlot(prefix: String, t: Type)

  type ConcatSlots = Seq[ConcatSlot]

  case class ConcatDefinition(atts: Set[AttrType], slotsSet: Set[ConcatSlots])

  private val concatDefinition = scala.collection.mutable.HashMap[ConcatAttributes, ConcatDefinition]()

  private def freshConcat(c: ConcatAttributes, atts: Set[AttrType] = Set(), slotsSet: Set[ConcatSlots] = Set()) = {
    assert(!concatDefinition.contains(c))
    concatDefinition.put(c, ConcatDefinition(atts, slotsSet))
  }

  private case class ConcatProperties(atts: Seq[AttrType], isComplete: Boolean)

  private def getConcatProperties(c: ConcatAttributes): ConcatProperties = {

    // maybe this guy should walk and narrow first
    // because then it may find that, hey, i'm actually complete now

    def resolve(slots: ConcatSlots): ConcatProperties = {

      val usedIdns = scala.collection.mutable.Set[Idn]()

      def uniqueIdn(i: Idn, j: Int = 0): Idn = {
        val ni = if (j == 0 && i.nonEmpty) i else if (i.isEmpty) s"_${j + 1}" else s"${i}_$j"
        if (usedIdns.contains(ni))
          uniqueIdn(i, j + 1)
        else {
          usedIdns += ni
          ni
        }
      }

      val resolvedAtts = scala.collection.mutable.ListBuffer[AttrType]()
      for (s <- slots) {
        find(s.t) match {
          case ResolvedType(RecordType(recAtts)) =>
            aFind(recAtts) match {
              case Attributes(atts)      =>
                for (att <- atts) {
                  resolvedAtts += AttrType(uniqueIdn(att.idn), att.tipe)
                }
              case _: AttributesVariable =>
                // Return early since we cannot know the sequence of attributes from the beginning
                return ConcatProperties(resolvedAtts.to, false)
              case c1: ConcatAttributes  =>
                val props1 = getConcatProperties(c1)
                for (att <- props1.atts) {
                  resolvedAtts += AttrType(uniqueIdn(att.idn), att.tipe)
                }
                if (!props1.isComplete) {
                  return ConcatProperties(resolvedAtts.to, false)
                }
            }
          case ResolvedType(_: TypeVariable) =>
            // Return early since we don't know if this type variable will resolve to a record or other type
            return ConcatProperties(resolvedAtts.to, false)
          case t                                               =>
            resolvedAtts += AttrType(uniqueIdn(s.prefix), t)
        }
      }
      ConcatProperties(resolvedAtts.to, true)
    }

    var mostPrecise: ConcatProperties = null
    for (slots <- concatDefinition(c).slotsSet) {
      val props = resolve(slots)
      if (props.isComplete) {
        return props
      }
      if (mostPrecise == null || props.atts.length > mostPrecise.atts.length) {
        mostPrecise = props
      }
    }
    mostPrecise
  }

  private def unifyAttributes(a: RecordAttributes, b: RecordAttributes, occursCheck: Set[(Type, Type)]): Boolean = {
    //logger.debug(s"unifyAttributes a ${PrettyPrinter(a)} b ${PrettyPrinter(b)}")
    val na = aFind(a)
    val nb = aFind(b)
    (aFind(a), aFind(b)) match {
      case (Attributes(atts1), Attributes(atts2))                             =>
        if (atts1.length == atts2.length && atts1.map(_.idn) == atts2.map(_.idn))
          atts1.zip(atts2).map { case (att1, att2) => unify(att1.tipe, att2.tipe, occursCheck) }.forall(identity)
        else
          false
      case (AttributesVariable(atts1, sym1), AttributesVariable(atts2, sym2)) =>
        val commonIdns = atts1.map(_.idn).intersect(atts2.map(_.idn))
        for (idn <- commonIdns) {
          val att1 = na.getType(idn).head
          val att2 = nb.getType(idn).head
          if (!unify(att1, att2, occursCheck)) {
            return false
          }
        }
        if (commonIdns.size == atts1.size && commonIdns.size == atts2.size) {
          recAttsVarMap.union(na, nb)
          true
        } else {
          // TODO: We seem to create too many AttributesVariable...
          val commonAttrs = commonIdns.map { case idn => AttrType(idn, na.getType(idn).head) } // Safe to take from the first attribute since they were already unified in the new map
          val nc = AttributesVariable(atts1.filter { case att => !commonIdns.contains(att.idn) } ++ atts2.filter { case att => !commonIdns.contains(att.idn) } ++ commonAttrs, SymbolTable.next())
          recAttsVarMap.union(na, nb).union(nb, nc)
          true
        }
      case (AttributesVariable(atts1, _), Attributes(atts2))                  =>
        if (!atts1.map(_.idn).subsetOf(atts2.map(_.idn).toSet)) {
          false
        } else {
          for (att1 <- atts1) {
            if (!unify(att1.tipe, nb.getType(att1.idn).get, occursCheck)) {
              return false
            }
          }
          recAttsVarMap.union(na, nb)
          true
        }
      case (_: Attributes, _: AttributesVariable)                             =>
        unifyAttributes(b, a, occursCheck)
      //      case (_: ConcatAttributes, _: ConcatAttributes) if a == b =>
      //        true
      case (ca: ConcatAttributes, cb: ConcatAttributes)          =>
        // check if their beginnings are incompatible
        val propsA = getConcatProperties(ca)
        val propsB = getConcatProperties(cb)
        if (!propsA.atts.zip(propsB.atts).map { case (att1, att2) => unify(att1.tipe, att2.tipe, occursCheck) }.forall(identity)) {
          return false
        }
        // check if their common attribute variables are incompatible
        val defCa = concatDefinition(ca)
        val defCb = concatDefinition(cb)
        val commonIdns = defCa.atts.map(_.idn).intersect(defCb.atts.map(_.idn))
        for (idn <- commonIdns) {
          val att1 = na.getType(idn).head
          val att2 = nb.getType(idn).head
          if (!unify(att1, att2, occursCheck)) {
            return false
          }
        }
        // all checks ok, so can unify
        val nc = ConcatAttributes()
        freshConcat(nc, defCa.atts ++ defCb.atts, defCa.slotsSet ++ defCb.slotsSet)
        recAttsVarMap.union(na, nb).union(nb, nc)
        true
      case (ca: ConcatAttributes, Attributes(atts1))            =>
        val props = getConcatProperties(ca)
        // if complete, check if they are the same length
        if (props.isComplete && props.atts.length != atts1.length) {
          return false
        }
        // check if the beginning matches
        for ((att1, att2) <- props.atts.zip(atts1)) {
          if (att1.idn != att2.idn || !unify(att1.tipe, att2.tipe, occursCheck)) {
            return false
          }
        }
        // check if the variable attributes match
        val defCa = concatDefinition(ca)
        if (!defCa.atts.map(_.idn).subsetOf(atts1.map(_.idn).toSet)) {
          false
        } else {
          for (att <- defCa.atts) {
            if (!unify(att.tipe, nb.getType(att.idn).get, occursCheck)) {
              return false
            }
          }
        }
        // all checks ok, so can unify
        recAttsVarMap.union(ca, nb)
        true
      case (_: Attributes, _: ConcatAttributes)                =>
        unifyAttributes(b, a, occursCheck)
      case (ca: ConcatAttributes, AttributesVariable(atts1, _)) =>
        val defCa = concatDefinition(ca)
        // check if the overlapping names match
        val commonIdns = defCa.atts.map(_.idn).intersect(atts1.map(_.idn))
        for (idn <- commonIdns) {
          val att1 = na.getType(idn).head
          val att2 = nb.getType(idn).head
          if (!unify(att1, att2, occursCheck)) {
            return false
          }
        }
        // check if the names are already present in the beginning, and if so, their types match
        val propsCa = getConcatProperties(ca)
        for (att <- atts1) {
          // find the variable attribute in the concats list
          val catt = propsCa.atts.collectFirst { case catt if catt.idn == att.idn => catt }
          if (catt.isEmpty && propsCa.isComplete) {
            // the variable attribute is not in the concats, even though the concat is complete
            return false
          } else if (catt.isDefined && !unify(catt.get.tipe, att.tipe)) {
            // the variable attribute is in the concats but the types don't unify
            return false
          }
        }
        // all ok, so can union
        val nc = ConcatAttributes()
        freshConcat(nc, defCa.atts ++ atts1, defCa.slotsSet)
        recAttsVarMap.union(na, nb).union(nb, nc)
        true
      case (_: AttributesVariable, _: ConcatAttributes)        =>
        unifyAttributes(b, a, occursCheck)
    }
  }

  def logConcatProperties() = {
    logger.debug(s"Concat attributes map:\n${recAttsVarMap.toString}")
    logger.debug(concatDefinition.toString)
    logger.debug(
      s"Concat attributes details:\n" +
        concatDefinition.map {
          case (c, cdef) =>
            val props = getConcatProperties(c)
            if (props.isComplete) {
              s"${PrettyPrinter(c)}(${PrettyPrinter(Attributes(props.atts.map{ case att => AttrType(att.idn, walk(att.tipe))}))})"
            } else {
              val natts = cdef.atts.map { case AttrType(idn1, t1) => AttrType(idn1, walk(t1)) }
              val nslotsSet = cdef.slotsSet.map(_.map { case ConcatSlot(prefix, t1) => ConcatSlot(prefix, walk(t1))})
              s"${PrettyPrinter(c)}(atts=${PrettyPrinter(AttributesVariable(natts))})(slotsSet=$nslotsSet)"
            }
        }.mkString("\n"))
  }

  // ah ha! at every unify, i can / should replace it then, otherwise, only at the end (which anyway is fine, but just seems more work(?))

  // that could be what the auto-propagate could be about
  // after every unify - of any type,
  // i'd try to replace a root concat attributes with smtg more specific
  // all in all, they are equivalent

  //////

  /** Hindley-Milner unification algorithm.
    */
  def unify(t1: Type, t2: Type, occursCheck: Set[(Type, Type)] = Set()): Boolean = {
    //      logger.debug(s"   Unifying t1 ${TypesPrettyPrinter(t1)} and t2 ${TypesPrettyPrinter(t2)}")
    if (occursCheck.contains((t1, t2))) {
      return true
    }
    val nt1 = find(t1)
    val nt2 = find(t2)
    (nt1, nt2) match {
      // TODO: Add NothingType

      case (_: AnyType, t) =>
        true
      case (t, _: AnyType) =>
        true

      case (_: IntType, _: IntType)     =>
        true
      case (_: BoolType, _: BoolType)   =>
        true
      case (_: FloatType, _: FloatType) =>
        true
      case (_: StringType, _: StringType) =>
        true

      case (CollectionType(m1, inner1), CollectionType(m2, inner2)) =>
        unifyMonoids(m1, m2) && unify(inner1, inner2, occursCheck + ((t1, t2)))

      case (FunType(p1, e1), FunType(p2, e2)) =>
        unify(p1, p2, occursCheck + ((t1, t2))) && unify(e1, e2, occursCheck + ((t1, t2)))

      case (RecordType(a1), RecordType(a2)) =>
        unifyAttributes(a1, a2, occursCheck + ((t1, t2)))

      case (PatternType(atts1), PatternType(atts2)) =>
        if (atts1.length == atts2.length)
          atts1.zip(atts2).map { case (att1, att2) => unify(att1.tipe, att2.tipe, occursCheck + ((t1, t2))) }.forall(identity)
        else
          false

      case (PatternType(atts1), RecordType(Attributes(atts2))) =>
        if (atts1.length == atts2.length)
          atts1.zip(atts2).map { case (att1, att2) => unify(att1.tipe, att2.tipe, occursCheck + ((t1, t2))) }.forall(identity)
        else
          false

      case (RecordType(Attributes(atts1)), PatternType(atts2)) =>
        unify(t2, t1, occursCheck + ((t1, t2)))

      case (p1: PrimitiveType, p2: PrimitiveType) =>
        typesVarMap.union(p2, p1)
        true
      case (p1: PrimitiveType, _: BoolType)   =>
        typesVarMap.union(p1, nt2)
        true
      case (p1: PrimitiveType, _: IntType)    =>
        typesVarMap.union(p1, nt2)
        true
      case (p1: PrimitiveType, _: FloatType)  =>
        typesVarMap.union(p1, nt2)
        true
      case (p1: PrimitiveType, _: StringType) =>
        typesVarMap.union(p1, nt2)
        true
      case (_: BoolType, _: PrimitiveType)    =>
        unify(nt2, nt1, occursCheck + ((t1, t2)))
      case (_: IntType, _: PrimitiveType)     =>
        unify(nt2, nt1, occursCheck + ((t1, t2)))
      case (_: FloatType, _: PrimitiveType)   =>
        unify(nt2, nt1, occursCheck + ((t1, t2)))
      case (_: StringType, _: PrimitiveType)  =>
        unify(nt2, nt1, occursCheck + ((t1, t2)))

      case (p1: NumberType, p2: NumberType) =>
        typesVarMap.union(p2, p1)
        true
      case (p1: NumberType, _: FloatType) =>
        typesVarMap.union(p1, nt2)
        true
      case (p1: NumberType, _: IntType)   =>
        typesVarMap.union(p1, nt2)
        true
      case (_: FloatType, _: NumberType)  =>
        unify(nt2, nt1, occursCheck + ((t1, t2)))
      case (_: IntType, _: NumberType)    =>
        unify(nt2, nt1, occursCheck + ((t1, t2)))

      case (UserType(sym1), UserType(sym2)) if sym1 == sym2 =>
        true
      case (v1: TypeVariable, v2: TypeVariable) =>
        typesVarMap.union(v2, v1)
        true
      case (v1: TypeVariable, v2: VariableType) =>
        typesVarMap.union(v1, v2)
        true
      case (v1: VariableType, v2: TypeVariable) =>
        unify(v2, v1, occursCheck + ((t1, t2)))
      case (v1: TypeVariable, _)                =>
        typesVarMap.union(v1, nt2)
        true
      case (_, v2: TypeVariable)                =>
        unify(v2, nt1, occursCheck + ((t1, t2)))
      case _                                    =>
        false
    }
  }

  /** This creates a monoid variable... comment it later :) it's used to postpone unification later: see MaxOfMonoid
    * where unifyMonoids is copied with the *NEW VARIABLE* we created here
    */
  private def maxMonoid(ts: Seq[CollectionType]): CollectionMonoid = {
    val nm = MonoidVariable()
    ts.map(_.m).map { case m => addMonoidOrder(m, nm) }
    nm
  }

  /** The type of a partition in a given SELECT.
    * Examples:
    *   SELECT age, PARTITION FROM students GROUP BY age
    *   The type of the SELECT is list(age, list[student])
    *
    *   SELECT age, PARTITION FROM students S GROUP BY age
    *   The type of the SELECT is (same as before): list(age, list[student])
    *
    *   SELECT age, PARTITION FROM students, professors WHERE student_age = professor_age GROUP BY student_age
    *   The type of the SELECT is list(age, list[(_1: student, _2: professor)])
    *
    *   SELECT age, PARTITION FROM students S, professors WHERE s.student_age = professor_age GROUP BY student_age
    *   The type of the SELECT is list(age, list[(s: student, _2: professor)])
    */
  private lazy val selectPartitionType: Select => Type = attr {
    s =>
      def aux: Type = {
        val fromTypes = s.from.map {
          case Gen(_, e) =>
            val t = expType(e)
            find(t) match {
              case t1: CollectionType => t1
              case _ => return NothingType()
            }
        }
        val ninner =
          if (fromTypes.length == 1) {
            fromTypes.head.innerType
          } else {
            val idns = s.from.zipWithIndex.map {
              case (Gen(Some(PatternIdn(IdnDef(idn))), _), _) => idn.takeWhile(_ != '$')
              case (Gen(None, _), i)                          => s"_${i + 1}"
            }
            assert(idns.toSet.size == idns.length) // TODO: Making sure that user PatternIdns do not match _1, _2 by accident!
            RecordType(Attributes(idns.zip(fromTypes.map(_.innerType)).map { case (idn, innerType) => AttrType(idn, innerType) }))
          }
        CollectionType(maxMonoid(fromTypes), ninner)
      }
      aux
  }

  /** The type of a * in a given SELECT.
    * Examples:
    *   SELECT * FROM students
    *   The type of the SELECT is list[student]
    *
    *   SELECT age, * FROM students
    *   Reports an error (it's too dangerous to multiply the table by itself)
    *
    *   SELECT age, * FROM students GROUP BY age
    *   The type of the SELECT is list(age, list[student])
    *
    *   SELECT age, * FROM students S GROUP BY age
    *   The type of the SELECT is (same as before): list(age, list[student])
    *
    *   SELECT * FROM students GROUP BY age
    *   The type of the SELECT is list[list[student]]
    *
    *   SELECT * FROM students, professors
    *   The type of the SELECT is list(... student ... professor ...)
    *   If field names are shared in the inner tables, these are renamed by prefixing an auto-generated name.
    *
    *   SELECT * FROM students S, professors P
    *   The type of the SELECT is list(... student ... professor ...)
    *   If field names are shared in the inner tables, these are renamed by prefixing the generator name.
    *
    *   SELECT age, * FROM students, professors WHERE student_age = professor_age GROUP BY student_age
    *   The type of the SELECT is (age, list[... student ... professor ...])
    *   (Same field renaming policy applies as above)
    *
    *   SELECT age, PARTITION FROM students S, professors WHERE s.student_age = professor_age GROUP BY student_age
    *   The type of the SELECT is (age, list[... student ... professor ...])
    *   (Same field renaming policy applies as above)
    */
  private lazy val selectStarType: Select => Type = attr {
    s =>
      def aux: Type = {
        val fromTypes: Seq[CollectionType] = s.from.map {
          case Gen(_, e) =>
            val t = expType(e)
            find(t) match {
              case t1: CollectionType => t1
              case _ => return NothingType()
            }
        }

        s.proj match {
          case _: Star if s.group.isEmpty && fromTypes.length == 1 =>
            // SELECT * FROM students
            return fromTypes.head.innerType
          case _ if s.group.isDefined && fromTypes.length == 1 =>
            // SELECT * FROM students GROUP BY age
            return CollectionType(maxMonoid(fromTypes), fromTypes.head.innerType)
          case _ =>
        }

        if (s.proj != Star() && s.group.isEmpty) {
          // SELECT age, * FROM students
          tipeErrors += IllegalStar(s, Some(parserPosition(s)))
          return NothingType()
        }

        if (s.from.length == 1) {
          //   SELECT age, * FROM students GROUP BY age
          // we know group by is defined otherwise we would have terminated earlier
          // we don't want to make a concat for that (we don't concatenate actually)
          CollectionType(maxMonoid(fromTypes), fromTypes.head.innerType)
        } else {
          // maybe there is no group by
          // compute the inner type and
          // add to the map attributes -> froms

          val c = ConcatAttributes()

          val slots = s.from.zip(fromTypes).map {
            case (Gen(None, _), t) => ConcatSlot("", t.innerType)
            case (Gen(Some(PatternIdn(IdnDef(idn))), _), t) => ConcatSlot(idn, t.innerType)

          }
          freshConcat(c, slotsSet = Set(slots))
          val inner = RecordType(c)
          if (s.group.isDefined) {
            //   SELECT age, * FROM students, professors GROUP BY age
            CollectionType(maxMonoid(fromTypes), inner)
          } else {
            //   SELECT * FROM students, professors
            inner
          }
        }
      }
      aux
  }

  /** Given a type, returns a new type that replaces type variables as much as possible, given the map m.
    * This is the type representing the group of types.
    */

  // TODO: Refactor into World.VarMap to avoid duplicated code
  // TODO: Perhaps do the same for all getVariableTypes, etc etc

  private def find(t: Type): Type =
    if (typesVarMap.contains(t)) typesVarMap(t).root else t

  private def aFind(t: RecordAttributes): RecordAttributes =
    if (recAttsVarMap.contains(t)) recAttsVarMap(t).root else t

  /** Reconstruct the type by resolving all inner variable types as much as possible.
    * Also, try to match the type into an existing user type.
    */
  private def walk(t: Type): Type = {

    def pickMostRepresentativeType(g: Group[Type]): Type = {
      val ut = g.elements.collectFirst { case u: UserType => u }
      ut match {
        case Some(picked) =>
          // Prefer user type
          picked
        case None =>
          val ct = g.elements.find { case _: VariableType => false; case _ => true }
          ct match {
            case Some(picked) =>
              // Otherwise, prefer a final - i.e. non-variable - type
              picked
            case None =>
              val vt = g.elements.find { case _: TypeVariable => false; case _ => true }
              vt match {
                case Some(picked) =>
                  // Otherwise, prefer a variable type that is not a type variable (e.g. a number or constrainted record)
                  picked
                case None =>
                  // Finally, prefer the root type variable
                  g.root
              }
          }
      }
    }

    // TODO should we pass occurscheck from the reconstruct type? I guess yes
    //
    def reconstructAttributes(atts: RecordAttributes, occursCheck: Set[Type]): RecordAttributes = aFind(atts) match {
      case Attributes(atts1)              =>
        Attributes(atts1.map { case AttrType(idn1, t1) => AttrType(idn1, reconstructType(t1, occursCheck)) })
      case AttributesVariable(atts1, sym) =>
        AttributesVariable(atts1.map { case AttrType(idn1, t1) => AttrType(idn1, reconstructType(t1, occursCheck)) }, sym)
      case c: ConcatAttributes =>
        val props = getConcatProperties(c)
        if (props.isComplete)
          Attributes(props.atts.map { case AttrType(idn1, t1) => AttrType(idn1, reconstructType(t1, occursCheck)) })
        else
          c
    }

    def reconstructType(t: Type, occursCheck: Set[Type]): Type = {
      val r = if (occursCheck.contains(t)) {
        t
      } else {
        t match {
          case _: NothingType                  => t
          case _: AnyType                      => t
          case _: IntType                      => t
          case _: BoolType                     => t
          case _: FloatType                    => t
          case _: StringType                   => t
          case _: DateTimeType                 => t
          case _: IntervalType                 => t
          case _: RegexType                    => t
          case _: UserType                     => t
          case _: PrimitiveType                => if (!typesVarMap.contains(t)) t else reconstructType(pickMostRepresentativeType(typesVarMap(t)), occursCheck + t)
          case _: NumberType                   => if (!typesVarMap.contains(t)) t else reconstructType(pickMostRepresentativeType(typesVarMap(t)), occursCheck + t)
          case RecordType(a)                   => RecordType(reconstructAttributes(a, occursCheck + t))
          case PatternType(atts)          => PatternType(atts.map { case att => PatternAttrType(reconstructType(att.tipe, occursCheck + t)) })
          case CollectionType(m, innerType)    => CollectionType(mFind(m).asInstanceOf[CollectionMonoid], reconstructType(innerType, occursCheck + t))
          case FunType(p, e)                   => FunType(reconstructType(p, occursCheck + t), reconstructType(e, occursCheck + t))
          case t1: TypeVariable => if (!typesVarMap.contains(t1)) t1 else reconstructType(pickMostRepresentativeType(typesVarMap(t1)), occursCheck + t)
        }
      }
      r.nullable = r.nullable || t.nullable
      r
    }

    reconstructType(t, Set())
  }

  def partitionType(p: Entity): Type = p match {
    case PartitionEntity(_, t) => t
    case _ => NothingType()
  }

  def starType(s: Entity): Type = s match {
    case StarEntity(_, t) => t
    case _ => NothingType()
  }

  def solve(n: RawNode): Unit = {

    def sameType(e1: Exp, e2: Exp, desc: Option[String] = None) = {
      val t1 = expType(e1)
      val t2 = expType(e2)
      val r = unify(t1, t2)
      if (!r) {
        tipeErrors += IncompatibleTypes(walk(t1), walk(t2), Some(parserPosition(e1)), Some(parserPosition(e2)))
      }
    }

    def hasType(e: Exp, expected: Type, desc: Option[String] = None) = {
      val t = expType(e)
      val r = unify(t, expected)
      if (!r) {
        tipeErrors += UnexpectedType(walk(t), walk(expected), desc, Some(parserPosition(e)))
      }
    }

    def genPatternHasType(g: Gen) = {
      val t = expType(g.e)
      find(t) match {
        case CollectionType(_, innerType) =>
          val expected = patternType(g.p.get)
          val r = unify(innerType, expected)
          if (!r) {
            tipeErrors += PatternMismatch(g.p.get, walk(innerType), Some(parserPosition(g.p.get)))
          }
      }
    }

    def maxOfMonoids(n: Exp, gs: Seq[Gen]): Unit = {
      val fromTypes = gs.map {
        case Gen(_, e) =>
          val te = expType(e)
          find(te) match {
            case tf: CollectionType => tf
            case _ => return
          }
      }

      val t = expType(n)
      val m = n match {
        case Comp(pm: PrimitiveMonoid, _, _) => pm
        // TODO: AlgebraNode is too general for reduce
        case (_: AlgebraNode | Comp(_: CollectionMonoid, _, _) | _: Select) =>
          find(t) match {
            case CollectionType(cm, _) => cm
            case _: NothingType => return
          }
      }

      for (minType <- fromTypes) {
        val minM = minType.m
        val nv = MonoidVariable()
        addMonoidOrder(nv, m)
        val r = unifyMonoids(minM, nv)
        if (!r) {
          // TODO: Fix error message: should have m and nm?
          tipeErrors += IncompatibleMonoids(m, walk(minType), Some(parserPosition(n)))
        }
      }
    }

    def boundByType(b: Bind) = tipeBind(b)

    def idnIsDefined(idnExp: IdnExp) = {

      def getType(nt: Type): Boolean = {
        val t1 = nt match {
          case TypeScheme(t, typeSyms, monoidSyms, attSyms) =>
            if (typeSyms.isEmpty && monoidSyms.isEmpty && attSyms.isEmpty)
              t
            else instantiateTypeScheme(t, typeSyms, monoidSyms, attSyms)
          case t => t
        }

        val t = expType(idnExp)
        val r = unify(t, t1)
        if (!r) {
          // The same decl has been used with two different types.
          // TODO: Can we have a more precise error messages? Set the None to a better message!
          tipeErrors += UnexpectedType(walk(t), walk(t1), Some("same declaration used with two different types"), Some(parserPosition(idnExp)))
        }
        r
      }

      val idn = idnExp.idn

      entity(idn) match {
        case _: UnknownEntity =>
          // Identifier is unknown

          lookupAttributeEntity(idnExp) match {
            case GenAttributeEntity(att, _, _) =>
              // We found the attribute identifier in a generator
              getType(att.tipe)
            case IntoAttributeEntity(att, _, _) =>
              // We found the attribute identifier in a Into
              getType(att.tipe)
            case _: UnknownEntity =>
              // We didn't found the attribute identifier
              tipeErrors += UnknownDecl(idn, Some(parserPosition(idn)))
              false
            case _: MultipleEntity =>
              // We found the attribute identifier more than once
              tipeErrors += AmbiguousIdn(idn, Some(parserPosition(idn)))
              false
          }
        case _: MultipleEntity =>
          // Error already reported earlier when processing IdnDef
          false
        case _ =>
          // We found an entity for the identifier.
          // However, we must still check it is not ambiguous so we look up in the anonymous chain as well.
          lookupAttributeEntity(idnExp) match {
            case _: UnknownEntity =>
              // All good
              getType(idnType(idn))
            case (_: GenAttributeEntity | _: IntoAttributeEntity | _: MultipleEntity) =>
              // We found the same identifier used by the user and being anonymous as well!
              tipeErrors += AmbiguousIdn(idn, Some(parserPosition(idn)))
              false
          }
      }
    }

    def funAppType(funApp: FunApp) = {
      val f = funApp.f
      val e = funApp.e
      val t = expType(f)
      find(t) match {
        case FunType(expected, output) =>
          val t1 = expType(e)

          def makeUpPattern(t: Type): Type = {
            def recurse(t: Type): Type = find(t) match {
              case RecordType(Attributes(atts)) => PatternType(atts.map { att => PatternAttrType(makeUpPattern(find(att.tipe))) })
              case _ => t
            }
            recurse(t)
          }

          // what do we unify the input parameter type with?
          // if expected is identified as a pattern and e is a record, then try to unify against the pattern version of e
          //                                        and e is not a record, then leave it as is and try. I guess it will fail.
          // if expected is a typevariable, we don't know what it is. If e is a record, then expected should be a pattern with the same
          // shape, therefore we unify against that record. If e is not a record, then we'll unify against whatever it is, it will just
          // work.
          val eType = expected match {
            case _: PatternType => makeUpPattern(t1) // if f takes a pattern, turn the input parameter type into a pattern
            case _: TypeVariable => makeUpPattern(t1)
            case _ => find(t1)
          }
          //
          //            val eType = makeUpPattern(t1)
          val r = unify(eType, expected)
          if (!r) {
            tipeErrors += IncompatibleTypes(walk(t1), walk(expected), Some(parserPosition(e)), Some(parserPosition(f)))
          }

          val tf = expType(funApp)
          val r1 = unify(output, tf)

          if (!r1) {
            tipeErrors += IncompatibleTypes(walk(output), walk(tf), Some(parserPosition(e)), Some(parserPosition(f)))
          }
      }
    }

    def partitionHasType(p: Partition) = {
      partitionEntity(p) match {
        case PartitionEntity(s, t) =>
          val t1 = selectPartitionType(s)
          val r = unify(t, t1)
          if (!r) {
            tipeErrors += UnexpectedType(t, t1, Some("Unexpected partition type"), Some(parserPosition(p)))
          }
        case _ =>
          tipeErrors += UnknownPartition(p, Some(parserPosition(p)))
      }
    }

    def starHasType(s: Star) = {
      starEntity(s) match {
        case StarEntity(e, t) =>
          e match {
            case s1: Select =>
              val t1 = find(selectStarType(s1))
              val r = unify(t, t1)
              if (!r) {
                tipeErrors += UnexpectedType(t, t1, Some("Unexpected star type"), Some(parserPosition(s)))
              }
            case c: Comp =>
              // TODO: ...
              ???
          }
        case _ =>
          tipeErrors += UnknownStar(s, Some(parserPosition(s)))
      }
    }

    n match {

      case Gen(None, e) =>
        solve(e)
        hasType(e, CollectionType(MonoidVariable(), TypeVariable()))

      case g @ Gen(Some(p), e) =>
        solve(e)
        hasType(e, CollectionType(MonoidVariable(), TypeVariable()))
        genPatternHasType(g)

      case b: Bind =>
        boundByType(b)

      case c @ Comp(m, qs, e) =>
        for (q <- qs) {
          solve(q)
        }
        solve(e)
        m match {
          case _: NumberMonoid =>
            val gs = qs.collect { case g: Gen => g }
            hasType(e, NumberType())
            sameType(c, e)
            maxOfMonoids(c, gs)

          case _: BoolMonoid =>
            val gs = qs.collect { case g: Gen => g }
            hasType(e, BoolType())
            sameType(c, e)
            maxOfMonoids(c, gs)

          case m: CollectionMonoid =>
            val gs = qs.collect { case g: Gen => g }
            hasType(c, CollectionType(m, expType(e)))
            maxOfMonoids(c, gs)
        }

      case s @ Select(froms, distinct, g, proj, w, o, h) =>
        for (f <- froms) {
          solve(f)
        }
        if (g.isDefined) {
          solve(g.get)
        }
        solve(proj)
        if (w.isDefined) {
          solve(w.get)
        }
        if (o.isDefined) {
          solve(o.get)
        }
        if (h.isDefined) {
          solve(h.get)
        }
        val m =
          if (o.isDefined)
            ListMonoid()
          else if (distinct)
            SetMonoid()
          else
            MonoidVariable()

        hasType(s, CollectionType(m, expType(proj)))
        maxOfMonoids(s, froms)

      case f @ FunAbs(p, e) =>
        solve(e)
        hasType(f, FunType(patternType(p), expType(e)))

      case n @ ExpBlock(binds, e) =>
        for (b <- binds) {
          solve(b)
        }
        solve(e)
        sameType(n, e)

      case n @ MergeMonoid(m, e1, e2) =>
        solve(e1)
        solve(e2)
        m match {
          case _: BoolMonoid =>
            hasType(n, BoolType())
            hasType(e1, BoolType())
            hasType(e2, BoolType())

          case _: NumberMonoid =>
            hasType(n, NumberType())
            sameType(n, e1)
            sameType(e1, e2)

          case m1: CollectionMonoid =>
            hasType(e1, CollectionType(m1, TypeVariable()))
            sameType(e1, e2)
            sameType(n, e1)
        }

      case n @ BinaryExp(op, e1, e2) =>
        solve(e1)
        solve(e2)
        op match {
          case _: EqualityOperator =>
            hasType(n, BoolType())
            sameType(e1, e2)

          case _: ComparisonOperator =>
            hasType(n, BoolType())
            sameType(e2, e1)
            hasType(e1, NumberType())

          case _: ArithmeticOperator =>
            sameType(n, e1)
            sameType(e1, e2)
            hasType(e2, NumberType())
        }

      case n @ InExp(e1, e2) =>
        solve(e1)
        solve(e2)
        val inner = TypeVariable()
        hasType(e2, CollectionType(MonoidVariable(), inner))
        hasType(e1, inner)
        hasType(n, BoolType())

      case n @ UnaryExp(op, e) =>
        solve(e)
        op match {
          case _: Not =>
            sameType(n, e)
            hasType(e, BoolType())

          case _: Neg =>
            sameType(n, e)
            hasType(e, NumberType())

          case _: ToBag =>
            val inner = TypeVariable()
            hasType(e, CollectionType(MonoidVariable(), inner))
            hasType(n, CollectionType(BagMonoid(), inner))

          case _: ToList =>
            val inner = TypeVariable()
            hasType(e, CollectionType(MonoidVariable(), inner))
            hasType(n, CollectionType(ListMonoid(), inner))

          case _: ToSet =>
            val inner = TypeVariable()
            hasType(e, CollectionType(MonoidVariable(), inner))
            hasType(n, CollectionType(SetMonoid(), inner))

          case _: ToInt =>
            hasType(n, IntType())

        }

      case n: IdnExp =>
        idnIsDefined(n)

      case n @ RecordProj(e, idn) =>
        solve(e)
        hasType(e, RecordType(AttributesVariable(Set(AttrType(idn, expType(n))))))

      case RecordCons(atts) =>
        for (att <- atts) {
          solve(att.e)
        }

      case n @ FunApp(f, e) =>
        solve(f)
        solve(e)
        hasType(f, FunType(TypeVariable(), expType(n)))
        funAppType(n)
        //hasType(f, FunType(expType(e), expType(n))))

      case _: ZeroCollectionMonoid =>

      case ConsCollectionMonoid(_, e) =>
        solve(e)

      case MultiCons(m, es) =>
        for (e <- es) {
          solve(e)
        }

        if (es.length > 1) {
          val thead = expType(es.head)
          for (e <- es.tail) {
            hasType(e, thead)
          }
        }

      case n @ IfThenElse(e1, e2, e3) =>
        solve(e1)
        solve(e2)
        solve(e3)
        hasType(e1, BoolType(), Some("if condition must be a boolean"))
        sameType(e2, e3, Some("then and else must be of the same type"))
        sameType(n, e2)

      case p: Partition =>
        partitionHasType(p)

      case s: Star =>
        starHasType(s)

      case n @ Reduce(m, g, e) =>
        solve(g)
        solve(e)
        m match {
          case m1: CollectionMonoid =>
            hasType(n, CollectionType(m1, expType(e)))

          case _: NumberMonoid =>
            hasType(e, NumberType())
            sameType(n, e)

          case _: BoolMonoid =>
            hasType(e, BoolType())
            hasType(n, BoolType())
        }

      case n @ Filter(g, p) =>
        solve(g)
        solve(p)
        sameType(n, g.e)
        hasType(p, BoolType())

      case n @ Nest(m, g, k, p, e) =>
        solve(g)
        solve(k)
        solve(e)
        solve(p)
        m match {
          case rm: CollectionMonoid =>
            val m = MonoidVariable()
            hasType(g.e, CollectionType(m, TypeVariable()))
            hasType(p, BoolType())
            hasType(n, CollectionType(m, RecordType(Attributes(Seq(AttrType("_1", expType(k)), AttrType("_2", CollectionType(rm, expType(e))))))))

          case rm: NumberMonoid =>
            val m = MonoidVariable()
            val nt = NumberType()
            hasType(g.e, CollectionType(m, TypeVariable()))
            hasType(e, nt)
            hasType(p, BoolType())
            hasType(n, CollectionType(m, RecordType(Attributes(Seq(AttrType("_1", expType(k)), AttrType("_2", nt))))))

          case rm: BoolMonoid =>
            val m = MonoidVariable()
            val bt = BoolType()
            hasType(g.e, CollectionType(m, TypeVariable()))
            hasType(e, bt)
            hasType(p, BoolType())
            hasType(n, CollectionType(m, RecordType(Attributes(Seq(AttrType("_1", expType(k)), AttrType("_2", bt))))))
        }

      case n @ Nest2(m, g, k, p, e) =>
        solve(g)
        solve(k)
        solve(e)
        solve(p)
        m match {
          case rm: CollectionMonoid =>
            val m = MonoidVariable()
            val inner = TypeVariable()
            hasType(g.e, CollectionType(m, inner))
            hasType(p, BoolType())
            hasType(n, CollectionType(m, RecordType(Attributes(Seq(AttrType("_1", inner), AttrType("_2", CollectionType(rm, expType(e))))))))

          case rm: NumberMonoid =>
            val m = MonoidVariable()
            val nt = NumberType()
            val inner = TypeVariable()
            hasType(g.e, CollectionType(m, inner))
            hasType(e, nt)
            hasType(p, BoolType())
            hasType(n, CollectionType(m, RecordType(Attributes(Seq(AttrType("_1", inner), AttrType("_2", nt))))))

          case rm: BoolMonoid =>
            val m = MonoidVariable()
            val bt = BoolType()
            val inner = TypeVariable()
            hasType(g.e, CollectionType(m, inner))
            hasType(e, bt)
            hasType(p, BoolType())
            hasType(n, CollectionType(m, RecordType(Attributes(Seq(AttrType("_1", inner), AttrType("_2", bt))))))
        }

      case n @ Join(g1, g2, p) =>
        solve(g1)
        solve(g2)
        val t1 = TypeVariable()
        val t2 = TypeVariable()
        hasType(g1.e, CollectionType(MonoidVariable(), t1))
        hasType(g2.e, CollectionType(MonoidVariable(), t2))
        hasType(p, BoolType())
        hasType(n, CollectionType(MonoidVariable(), RecordType(Attributes(Seq(AttrType("_1", t1), AttrType("_2", t2))))))
        maxOfMonoids(n, Seq(g1, g2))

      case n @ OuterJoin(g1, g2, p) =>
        solve(g1)
        solve(g2)
        val t1 = TypeVariable()
        val t2 = TypeVariable()
        hasType(g1.e, CollectionType(MonoidVariable(), t1))
        hasType(g2.e, CollectionType(MonoidVariable(), t2))
        hasType(p, BoolType())
        hasType(n, CollectionType(MonoidVariable(), RecordType(Attributes(Seq(AttrType("_1", t1), AttrType("_2", t2))))))
        maxOfMonoids(n, Seq(g1, g2))

      case n @ Unnest(g1, g2, p) =>
        solve(g1)
        solve(g2)
        val t1 = TypeVariable()
        val t2 = TypeVariable()
        hasType(g1.e, CollectionType(MonoidVariable(), t1))
        hasType(g2.e, CollectionType(MonoidVariable(), t2))
        hasType(p, BoolType())
        hasType(n, CollectionType(MonoidVariable(), RecordType(Attributes(Seq(AttrType("_1", t1), AttrType("_2", t2))))))
        maxOfMonoids(n, Seq(g1, g2))

      case n @ OuterUnnest(g1, g2, p) =>
        solve(g1)
        solve(g2)
        val t1 = TypeVariable()
        val t2 = TypeVariable()
        hasType(g1.e, CollectionType(MonoidVariable(), t1))
        hasType(g2.e, CollectionType(MonoidVariable(), t2))
        hasType(p, BoolType())
        hasType(n, CollectionType(MonoidVariable(), RecordType(Attributes(Seq(AttrType("_1", t1), AttrType("_2", t2))))))
        maxOfMonoids(n, Seq(g1, g2))

      case n @ Sum(e) =>
        solve(e)
        val mv = MonoidVariable()
        addMonoidOrder(mv, SumMonoid())
        val tn = NumberType()
        hasType(e, CollectionType(mv, tn))
        hasType(n, tn)

      case n @ Max(e) =>
        solve(e)
        val tn = NumberType()
        hasType(e, CollectionType(MonoidVariable(), tn))
        hasType(n, tn)

      case n @ Min(e) =>
        solve(e)
        val tn = NumberType()
        hasType(e, CollectionType(MonoidVariable(), tn))
        hasType(n, tn)

      case n @ Avg(e) =>
        solve(e)
        val tn = NumberType()
        hasType(e, CollectionType(MonoidVariable(), tn))
        hasType(n, tn)

      case n @ Count(e) =>
        solve(e)
        hasType(e, CollectionType(MonoidVariable(), TypeVariable()))

      case n @ Exists(e) =>
        solve(e)
        hasType(e, CollectionType(MonoidVariable(), TypeVariable()))

      case n @ Into(e1, e2) =>
        solve(e1)
        solve(e2)
        hasType(e1, RecordType(AttributesVariable(Set())))

      case n @ ParseAs(e, r, _) =>
        solve(e)
        solve(r)
        hasType(e, StringType())

      case n @ ToEpoch(e, _) =>
        solve(e)
        hasType(e, StringType())

      case _: Const =>

    }
  }

  /** For debugging.
    * Prints all the type groups.
    */
  def printTypedTree(): Unit = {

    def printMap(pos: Set[Position], t: Type) = {
      val posPerLine = pos.groupBy(_.line)
      var output = s"Type: ${FriendlierPrettyPrinter(t)}\n"
      for ((line, lineno) <- queryString.split("\n").zipWithIndex) {
        output += line + "\n"
        if (posPerLine.contains(lineno + 1)) {
          val cols = posPerLine(lineno + 1).map(_.column).toList.sortWith(_ < _)
          var c = 0
          for (col <- cols) {
            output += " " * (col - c - 1)
            output += "^"
            c = col
          }
          output += "\n"
        }
      }
      output
    }

    val collectMaps = collect[List, (Type, Position)] {
      case e: Exp =>
        val t = expType(e)
        if (!typesVarMap.contains(t)) {
          t -> e.pos
        } else {
          walk(typesVarMap(t).root) -> e.pos
        }
    }

    for ((t, items) <- collectMaps(tree.root).groupBy(_._1)) {
      logger.debug(printMap(items.map(_._2).toSet, t))
    }

  }
}
