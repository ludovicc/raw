package raw.algebra.unnester

import raw._
import raw.catalog._

class Unnester(cat: Catalog) {

  private sealed abstract class Term
  private case class CalculusTerm(t: calculus.canonical.TypedExpression) extends Term
  private case class AlgebraTerm(t: algebra.Algebra) extends Term
  
  private sealed abstract class Pattern
  private case object EmptyPattern extends Pattern
  private case class VariablePattern(v: calculus.canonical.Variable) extends Pattern
  private case class PairPattern(a: Pattern, b: Pattern) extends Pattern
  
  /** Helper object to pattern match nested comprehensions. */
  private object NestedComprehension {
    import raw.calculus.canonical._
    
    def unapply(e: Expression): Option[Comprehension] = e match {
      case RecordProjection(_, NestedComprehension(e), _) => Some(e)
      case RecordConstruction(t, atts) => atts.map(att => att.e match {
        case NestedComprehension(e) => Some(e)
        case _ => None
      }).flatten.headOption
      case IfThenElse(_, NestedComprehension(e1), _, _) => Some(e1)
      case IfThenElse(_, _, NestedComprehension(e2), _) => Some(e2)
      case IfThenElse(_, _, _, NestedComprehension(e3)) => Some(e3)
      case BinaryOperation(_, _, NestedComprehension(e1), _) => Some(e1)
      case BinaryOperation(_, _, _, NestedComprehension(e2)) => Some(e2)
      case ConsCollectionMonoid(_, _, NestedComprehension(e)) => Some(e)
      case MergeMonoid(_, _, NestedComprehension(e1), _) => Some(e1)
      case MergeMonoid(_, _, _, NestedComprehension(e2)) => Some(e2)
      case Not(NestedComprehension(e)) => Some(e)
      case c : Comprehension => Some(c)
      case _ => None
    }
  }
  
  /** This method returns true if the expression has a nested comprehension. */
  private def hasNestedComprehension(e: calculus.canonical.TypedExpression): Boolean = e match {
    case NestedComprehension(_) => true
    case _ => false
  }

  /** This method returns the variable in the path. */
  def getPathVariable(p: calculus.canonical.Path): calculus.canonical.Variable = {
    import raw.calculus.canonical._
    
    p match {
      case VariablePath(v) => v
      case InnerPath(p, _) => getPathVariable(p)
    }
  }
  
  /** This method returns true if the comprehension 'c' does not depend on variables defined
   *  in 's' generators. */
  private def isIndependent(s: List[calculus.canonical.Generator], c: calculus.canonical.Comprehension): Boolean = {
    import raw.calculus.canonical._
    
    val gens = s.map{ case Generator(v, _) => v }
    
    def apply(e: TypedExpression): Boolean = e match {
      case n : Null => true
      case c : Constant => true
      case v : Variable => !gens.contains(v)
      case RecordProjection(_, e, _) => apply(e)
      case RecordConstruction(_, atts) => !atts.map(att => apply(att.e)).contains(false)
      case IfThenElse(_, e1, e2, e3) => apply(e1) && apply(e2) && apply(e3)
      case BinaryOperation(_, _, e1, e2) => apply(e1) && apply(e2)
      case z : EmptySet => true
      case z : EmptyBag => true
      case z : EmptyList => true
      case ConsCollectionMonoid(_, _, e) => apply(e)
      case MergeMonoid(_, _, e1, e2) => apply(e1) && apply(e2)
      case Comprehension(_, _, e, gs, pred) => 
        apply(e) && !gs.map{ case Generator(_, p) => !gens.contains(getPathVariable(p)) }.contains(false) && apply(pred)
      case Not(e) => apply(e)
    }
    
    apply(c)    
  }
  
  /** This method returns a new expression with nested comprehension 'c' replaced by variable 'v'. */
  private def applyVariable(e: calculus.canonical.TypedExpression, v: calculus.canonical.Variable, c: calculus.canonical.Comprehension): calculus.canonical.TypedExpression = {
    import raw.calculus.canonical._
    
    e match {
      case n : Null => n
      case c : Constant => c
      case v : Variable => v
      case RecordProjection(t, e, name) => RecordProjection(t, applyVariable(e, v, c), name)
      case RecordConstruction(t, atts) => RecordConstruction(t, atts.map(att => AttributeConstruction(att.name, applyVariable(att.e, v, c))))
      case IfThenElse(t, e1, e2, e3) => IfThenElse(t, applyVariable(e1, v, c), applyVariable(e2, v, c), applyVariable(e3, v, c))
      case BinaryOperation(t, op, e1, e2) => BinaryOperation(t, op, applyVariable(e1, v, c), applyVariable(e2, v, c))
      case z : EmptySet => z
      case z : EmptyBag => z
      case z : EmptyList => z
      case ConsCollectionMonoid(t, m, e) => ConsCollectionMonoid(t, m, applyVariable(e, v, c))
      case MergeMonoid(t, m, e1, e2) => MergeMonoid(t, m, applyVariable(e1, v, c), applyVariable(e2, v, c))
      case c1 : Comprehension if c == c1 => v
      case Comprehension(t, m, e, gs, pred) => Comprehension(t, m, applyVariable(e, v, c), gs, applyVariable(pred, v, c))
      case Not(e) => Not(applyVariable(e, v, c))
    }
  }
 
  /** This method returns the set of canonical variables in the pattern. */
  private def getPatternVariables(p: Pattern): List[calculus.canonical.Variable] = p match {
    case EmptyPattern => List()
    case VariablePattern(v) => List(v)
    case PairPattern(a, b) => getPatternVariables(a) ++ getPatternVariables(b)
  }
  
  /** This method returns the set of variables in the expression.
   *  The expression cannot contain nested comprehensions.
   */
  private def getExpressionVariables(e: calculus.canonical.TypedExpression): Set[calculus.canonical.Variable] = {
    import raw.calculus.canonical._
    
    e match {
      case n : Null => Set()
      case c : Constant => Set()
      case v : Variable => Set(v)
      case RecordProjection(_, e, _) => getExpressionVariables(e)
      case RecordConstruction(t, atts) => atts.flatMap(att => getExpressionVariables(att.e)).toSet
      case IfThenElse(_, e1, e2, e3) => getExpressionVariables(e1) ++ getExpressionVariables(e2) ++ getExpressionVariables(e3) 
      case BinaryOperation(_, _, e1, e2) => getExpressionVariables(e1) ++ getExpressionVariables(e2)
      case z : EmptySet => Set()
      case z : EmptyBag => Set()
      case z : EmptyList => Set()
      case ConsCollectionMonoid(_, _, e) => getExpressionVariables(e)
      case MergeMonoid(_, _, e1, e2) => getExpressionVariables(e1) ++ getExpressionVariables(e2)
      case c : Comprehension => throw RawInternalException("unexpected Comprehension found")
      case Not(e) => getExpressionVariables(e)
    }
  }
  
  /** Implementation of the 'split_predicate(p, left, right)' method described in page 36 of [1].
    */
  private def splitPredicate(p: calculus.canonical.TypedExpression, left: Pattern, right: Pattern) = {
    /** This method flattens a predicate in CNF form into a list of predicates. */
    def flatten(e: calculus.canonical.TypedExpression): List[calculus.canonical.TypedExpression] = e match {
       case calculus.canonical.MergeMonoid(t, calculus.AndMonoid(), e1, e2) => flatten(e1) ::: flatten(e2)
       case _ => List(e)
     }
    
    /** This method folds a list of CNF predicates into a single predicate. */
    def fold(e: List[calculus.canonical.TypedExpression]) = {
      if (e.isEmpty) {
        calculus.canonical.BoolConst(true)
      } else {
        val head = e.head
        val rest = e.drop(1)
        rest.foldLeft(head)((a, b) => calculus.canonical.MergeMonoid(calculus.BoolType, calculus.AndMonoid(), a, b))
      }
    }
        
    val pred = flatten(p) 
    val p1 = pred.filter(p => !hasNestedComprehension(p) && getExpressionVariables(p).subsetOf(getPatternVariables(right).toSet))
    val p2 = pred.filter(p => !hasNestedComprehension(p) && getExpressionVariables(p).subsetOf(getPatternVariables(left).toSet) && getExpressionVariables(p).subsetOf(getPatternVariables(right).toSet))
    val p3 = pred.filter(p => !p1.contains(p) && !p2.contains(p))
    (fold(p1), fold(p2), fold(p3))
  }
   
  private def convertType(t: calculus.MonoidType): algebra.ExpressionType = t match {
    case t : calculus.PrimitiveType => convertPrimitiveType(t)
    case t : calculus.RecordType => convertRecordType(t)
    case t : calculus.CollectionType => throw RawInternalException("unexpected collection type")
  }
  
  private def convertPrimitiveType(t: calculus.PrimitiveType): algebra.PrimitiveType = t match {
    case calculus.BoolType => algebra.BoolType
    case calculus.FloatType => algebra.FloatType
    case calculus.IntType => algebra.IntType
    case calculus.StringType => algebra.StringType
  }

  private def convertRecordType(t: calculus.RecordType): algebra.RecordType = 
    algebra.RecordType(t.atts.map(att => algebra.Attribute(att.name, convertType(att.monoidType))))

  private def convertVariable(v: calculus.canonical.Variable, p: Pattern) =
    algebra.Argument(convertType(v.monoidType), getPatternVariables(p).indexOf(v))
    
    /** This method converts a canonical typed expression to the equivalent algebra expression.
   *  Algebra expressions only use primitive types and primitive monoids.
   *  The remaining is handled by the algebra operators.
   */
  private def convertExpression(e: calculus.canonical.TypedExpression, p: Pattern): algebra.Expression = e match {
    case calculus.canonical.BoolConst(v) => algebra.BoolConst(v)
    case calculus.canonical.IntConst(v) => algebra.IntConst(v)
    case calculus.canonical.FloatConst(v) => algebra.FloatConst(v)
    case calculus.canonical.StringConst(v) => algebra.StringConst(v)
    case v : calculus.canonical.Variable => convertVariable(v, p)
    case calculus.canonical.RecordProjection(t, e, name) => algebra.RecordProjection(convertType(t), convertExpression(e, p), name)
    case calculus.canonical.RecordConstruction(t : calculus.RecordType, atts) => algebra.RecordConstruction(convertRecordType(t), atts.map(att => algebra.AttributeConstruction(att.name, convertExpression(att.e, p))))
    case calculus.canonical.IfThenElse(t : calculus.PrimitiveType, e1, e2, e3) => algebra.IfThenElse(convertPrimitiveType(t), convertExpression(e1, p), convertExpression(e2, p), convertExpression(e3, p))
    case calculus.canonical.BinaryOperation(t : calculus.PrimitiveType, op, e1, e2) => algebra.BinaryOperation(convertPrimitiveType(t), op, convertExpression(e1, p), convertExpression(e2, p))
    case calculus.canonical.MergeMonoid(t : calculus.PrimitiveType, m : calculus.PrimitiveMonoid, e1, e2) => algebra.MergeMonoid(convertPrimitiveType(t), convertPrimitiveMonoid(m), convertExpression(e1, p), convertExpression(e2, p))
    case calculus.canonical.Not(e) => algebra.Not(convertExpression(e, p))
  }
  
  private def convertPath(p: calculus.canonical.Path, w: Pattern): algebra.Path = p match {
    case calculus.canonical.VariablePath(v) => algebra.ArgumentPath(getPatternVariables(w).indexOf(v))
    case calculus.canonical.InnerPath(p, name) => algebra.InnerPath(convertPath(p, w), name)
  }
    
  /** This method converts a monoid AST node from the calculus representation to the equivalent algebra representation. */
  private def convertMonoid(m: calculus.Monoid): algebra.Monoid = m match {
    case m : calculus.PrimitiveMonoid => convertPrimitiveMonoid(m)
    case m : calculus.SetMonoid => algebra.SetMonoid
    case m : calculus.BagMonoid => algebra.BagMonoid    
    case m : calculus.ListMonoid => algebra.ListMonoid
  }
  
  private def convertPrimitiveMonoid(m: calculus.PrimitiveMonoid): algebra.PrimitiveMonoid = m match {
    case m : calculus.SumMonoid => algebra.SumMonoid
    case m : calculus.MultiplyMonoid => algebra.MultiplyMonoid
    case m : calculus.MaxMonoid => algebra.MaxMonoid
    case m : calculus.OrMonoid => algebra.OrMonoid
    case m : calculus.AndMonoid => algebra.AndMonoid    
  }
  
  private def buildArgumentList(w: Pattern, u: Pattern) =
    getPatternVariables(u).map(u => convertVariable(u, w))
    
  private def reducePattern(l: Pattern, r: Pattern): Pattern = {
    val rs = getPatternVariables(r).toSet
    def recurse(p: Pattern): Pattern = p match {
      case EmptyPattern => EmptyPattern
      case VariablePattern(v) if rs.contains(v) => EmptyPattern
      case VariablePattern(v) => VariablePattern(v)
      case PairPattern(a, b) => PairPattern(recurse(a), recurse(b))
    }
    recurse(l)
  }
    
  /** Implementation of the Query Unnesting Algorithm described in Figure 11, page 37 of [1].
    */
  private def T(e: Term, u: Pattern, w: Pattern, E: Term): Term = e match {
    case CalculusTerm(calculus.canonical.Comprehension(t, m, e1, s, p)) => p match {
      case NestedComprehension(ep) if isIndependent(s, ep) => {
        /** Rule C11 */
        val v = calculus.canonical.Variable(calculus.normalizer.Variable(calculus.parser.Variable(ep.monoidType)))
        T(CalculusTerm(calculus.canonical.Comprehension(t, m, e1, s, applyVariable(p, v, ep))),
                       u, PairPattern(w, VariablePattern(v)),
                       T(CalculusTerm(ep), w, w, E))
      }
      case _ => e match {
        case CalculusTerm(calculus.canonical.Comprehension(t, m, e1, List(), p)) => e1 match {
           case NestedComprehension(ep) => {
             /** Rule C12 */
             val v = calculus.canonical.Variable(calculus.normalizer.Variable(calculus.parser.Variable(ep.monoidType)))
             T(CalculusTerm(calculus.canonical.Comprehension(t, m, applyVariable(e1, v, ep), List(), p)),
                            u, PairPattern(w, VariablePattern(v)),
                            T(CalculusTerm(ep), w, w, E))
           }
           case _ => {
             if (u == EmptyPattern) {
               /** Rule C5 */
               AlgebraTerm(algebra.Reduce(convertMonoid(m),
                                          convertExpression(e1, w),
                                          convertExpression(p, w),
                                          E match { case AlgebraTerm(t) => t }))
             } else {
               /** Rule C8 */
               AlgebraTerm(algebra.Nest(convertMonoid(m),
                                        convertExpression(e1, w),
                                        buildArgumentList(w, u),
                                        convertExpression(p, w),
                                        buildArgumentList(w, reducePattern(w, u)),
                                        E match { case AlgebraTerm(t) => t }))
             }
           }
        }
        case _ => e match {
          case CalculusTerm(calculus.canonical.Comprehension(t, m, e1, calculus.canonical.Generator(v, x) :: r, p)) => {
            val (p1, p2, p3) = splitPredicate(p, w, VariablePattern(v))
            if (u == EmptyPattern) {
              if (w == EmptyPattern) {
                /** Rule C4 */
                T(CalculusTerm(calculus.canonical.Comprehension(t, m, e1, r, p3)),
                               u, VariablePattern(v),
                               AlgebraTerm(algebra.Select(convertExpression(calculus.canonical.MergeMonoid(calculus.BoolType, calculus.AndMonoid(), p1, p2), VariablePattern(v)),
                                                          algebra.Scan(cat.getName(getPathVariable(x).v.v)))))
              } else {
                x match {
                  case x : calculus.canonical.VariablePath => {
                    /** Rule C6 */
                    T(CalculusTerm(calculus.canonical.Comprehension(t, m, e1, r, p3)),
                                   u, PairPattern(w, VariablePattern(v)),
                                   AlgebraTerm(algebra.Join(convertExpression(p2, PairPattern(w, VariablePattern(v))),
                                                            E match { case AlgebraTerm(t) => t },
                                                            algebra.Select(convertExpression(p1, VariablePattern(v)),
                                                                           algebra.Scan(cat.getName(getPathVariable(x).v.v))))))
                  }
                  case x : calculus.canonical.InnerPath => {
                    /** Rule C7 */
                    T(CalculusTerm(calculus.canonical.Comprehension(t, m, e1, r, p3)),
                                   u, PairPattern(w, VariablePattern(v)),
                                   AlgebraTerm(algebra.Unnest(convertPath(x, w),
                                                              convertExpression(calculus.canonical.MergeMonoid(calculus.BoolType, calculus.AndMonoid(), p1, p2), PairPattern(w, VariablePattern(v))),
                                                              E match { case AlgebraTerm(t) => t })))
                  }
                }
              }
            } else {
              x match {
                case x : calculus.canonical.VariablePath => {
                  /** Rule C9 */
                  T(CalculusTerm(calculus.canonical.Comprehension(t, m, e1, r, p3)),
                                 u, PairPattern(w, VariablePattern(v)),
                                 AlgebraTerm(algebra.OuterJoin(convertExpression(p2, PairPattern(w, VariablePattern(v))),
                                                               E match { case AlgebraTerm(t) => t },
                                                               algebra.Select(convertExpression(p1, VariablePattern(v)),
                                                                              algebra.Scan(cat.getName(getPathVariable(x).v.v))))))
                }
                case x : calculus.canonical.InnerPath => {
                  /** Rule C10 */
                  T(CalculusTerm(calculus.canonical.Comprehension(t, m, e1, r, p3)),
                                 u, PairPattern(w, VariablePattern(v)),
                                 AlgebraTerm(algebra.OuterUnnest(convertPath(x, w),
                                                                 convertExpression(calculus.canonical.MergeMonoid(calculus.BoolType, calculus.AndMonoid(), p1, p2), PairPattern(w, VariablePattern(v))),
                                                                 E match { case AlgebraTerm(t) => t })))
                }
              }
            }
          }
        }
      }
    }
  }

  def unnest(e: calculus.canonical.TypedExpression) = {
    T(CalculusTerm(e), EmptyPattern, EmptyPattern, AlgebraTerm(algebra.Empty)) match { case AlgebraTerm(t) => t }
  }
  
}
