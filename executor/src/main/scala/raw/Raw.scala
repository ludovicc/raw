package raw

import com.typesafe.scalalogging.StrictLogging
import raw.algebra.LogicalAlgebra.LogicalAlgebraNode
import raw.algebra.Typer
import raw.psysicalalgebra.PhysicalAlgebra._
import raw.psysicalalgebra.{LogicalToPhysicalAlgebra, PhysicalAlgebraPrettyPrinter}

import scala.annotation.StaticAnnotation
import scala.collection.mutable
import scala.language.experimental.macros

class RawImpl(val c: scala.reflect.macros.whitebox.Context) extends StrictLogging {

  import c.universe._

  case class AccessPath(tipe: raw.Type, tree: Tree, isSpark: Boolean)

  def extractParams(tree: Tree): (Tree, Tree) = tree match {
    case q"new $name( ..$params )" =>
      println("Extracted params: " + params)
      params match {
        case List(queryTree, catalogTree) =>
          println("query: " + queryTree + ", catalog: " + catalogTree)
          (queryTree.asInstanceOf[Tree], catalogTree.asInstanceOf[Tree])
        //        case List(queryTree:c.Expr[String], catalogTree:c.Expr[HList]) => (queryTree, catalogTree)
        //        case q"($query:String, $catalog:HList)"  List(queryTree:c.Expr[String], catalogTree:c.Expr[HList]) => (queryTree, catalogTree)
      }
  }

  def extractLiteral(tree: Tree): String = tree match {
    case Literal(Constant(value: String)) => value
  }

  def query_impl(annottees: c.Expr[Any]*): c.Expr[Any] = {
    /** Bail out during compilation with error message. */
    def bail(message: String) = c.abort(c.enclosingPosition, message)

    /** Infer RAW's type from the Scala type. */
    def inferType(t: c.Type): (raw.Type, Boolean) = {
      val rawType = t match {
        case TypeRef(_, sym, Nil) if sym.fullName == "scala.Int" => raw.IntType()
        case TypeRef(_, sym, Nil) if sym.fullName == "scala.Any" => raw.TypeVariable(new raw.Variable())
        case TypeRef(_, sym, Nil) if sym.fullName == "scala.Predef.String" => raw.StringType()

        case TypeRef(_, sym, List(t1)) if sym.fullName == "scala.Predef.Set" => raw.SetType(inferType(t1)._1)
        case TypeRef(_, sym, List(t1)) if sym.fullName == "scala.List" || sym.fullName == "scala.collection.immutable.List" => raw.ListType(inferType(t1)._1)
        case TypeRef(_, sym, t1) if sym.fullName.startsWith("scala.Tuple") =>
          val regex = """scala\.Tuple(\d+)""".r
          sym.fullName match {
            case regex(n) => raw.RecordType(List.tabulate(n.toInt) { case i => raw.AttrType(s"_${i + 1}", inferType(t1(i))._1) }, None)
          }
        case TypeRef(_, sym, t1) if sym.fullName.startsWith("scala.Function") =>
          val regex = """scala\.Function(\d+)""".r
          sym.fullName match {
            case regex(n) => raw.FunType(inferType(t1(0))._1, inferType(t1(1))._1)
          }

        case t@TypeRef(_, sym, Nil) =>
          val symName = sym.fullName
          val ctor = t.decl(termNames.CONSTRUCTOR).asMethod
          raw.RecordType(ctor.paramLists.head.map { case sym1 => raw.AttrType(sym1.name.toString, inferType(sym1.typeSignature)._1) }, Some(symName))

        case TypeRef(_, sym, List(t1)) if sym.fullName == "org.apache.spark.rdd.RDD" =>
          raw.ListType(inferType(t1)._1)

        case TypeRef(pre, sym, args) =>
          bail(s"Unsupported TypeRef($pre, $sym, $args)")
      }

      val isSpark = t.typeSymbol.fullName.equals("org.apache.spark.rdd.RDD")
      (rawType, isSpark)
    }

    def recordTypeSym(r: RecordType) = r match {
      case RecordType(_, Some(symName)) => symName
      case _ =>
        // TODO: The following naming convention may conflict with user type names. Consider prefixing all types using `type = Prefix???`
        val uniqueId = if (r.hashCode() < 0) s"_n${Math.abs(r.hashCode()).toString}" else s"_p${r.hashCode().toString}"
        uniqueId
    }


    /** Build case classes that correspond to record types.
      */
    def buildCaseClasses(logicalTree: LogicalAlgebraNode, world: World, typer: Typer): Set[Tree] = {
      import org.kiama.rewriting.Rewriter.collect

      // Extractor for anonymous record types
      object IsAnonRecordType {
        def unapply(e: raw.algebra.Expressions.Exp): Option[RecordType] = typer.expressionType(e) match {
          case r@RecordType(_, None) => Some(r)
          case _ => None
        }
      }

      // Create Scala type from RAW type
      def tipe(t: raw.Type): String = t match {
        case _: BoolType => "Boolean"
        case FunType(t1, t2) => ???
        case _: StringType => "String"
        case _: IntType => "Int"
        case _: FloatType => "Float"
        case r: RecordType => recordTypeSym(r)
        case BagType(innerType) => ???
        case ListType(innerType) => s"List[${tipe(innerType)}]"
        case SetType(innerType) => s"Set[${tipe(innerType)}]"
        case UserType(idn) => tipe(world.userTypes(idn))
        case TypeVariable(v) => ???
        case _: AnyType => ???
        case _: NothingType => ???
      }

      // Collect all record types from tree
      val collectAnonRecordTypes = collect[List, raw.RecordType] {
        case IsAnonRecordType(t) => t
      }

      // Convert all collected record types to a set to remove repeated types, leaving only the structural types
      val anonRecordTypes = collectAnonRecordTypes(logicalTree).toSet

      // Create corresponding case class
      val code = anonRecordTypes
        .map {
        case r@RecordType(atts, None) =>
          val args = atts.map(att => s"${att.idn}: ${tipe(att.tipe)}").mkString(",")
          val cl = s"""case class ${recordTypeSym(r)}($args)"""
          //            logger.info("Defined case class: {}", cl)
          cl
      }

      code.map(c.parse)
    }

    /** Build code-generated query plan from logical algebra tree.
      */
    //    def buildCode(logicalTree: LogicalAlgebraNode, physicalTree: PhysicalAlgebraNode, world: World, typer: Typer, accessPaths: Map[String, Tree]): Tree = {
    def buildCode(logicalTree: LogicalAlgebraNode, physicalTree: PhysicalAlgebraNode, world: World, typer: Typer): Tree = {
      import algebra.Expressions._

      def build(a: PhysicalAlgebraNode): Tree = {
        def exp(e: Exp): Tree = {

          def binaryOp(op: BinaryOperator): String = op match {
            case _: Eq => "=="
            case _: Neq => "!="
            case _: Ge => ">="
            case _: Gt => ">"
            case _: Le => "<="
            case _: Lt => "<"
            case _: Sub => "-"
            case _: Div => "/"
            case _: Mod => "%"
          }

          def recurse(e: Exp): String = e match {
            case Null => "null"
            case BoolConst(v) => v.toString
            case IntConst(v) => v
            case FloatConst(v) => v
            case StringConst(v) => s""""$v""""
            case _: Arg => "arg"
            case RecordProj(e1, idn) => s"${recurse(e1)}.$idn"
            case RecordCons(atts) =>
              val sym = recordTypeSym(typer.expressionType(e) match { case r: RecordType => r })
              val vals = atts.map(att => recurse(att.e)).mkString(",")
              s"""$sym($vals)"""
            case IfThenElse(e1, e2, e3) => s"if (${recurse(e1)}) ${recurse(e2)} else ${recurse(e3)}"
            case BinaryExp(op, e1, e2) => s"${recurse(e1)} ${binaryOp(op)} ${recurse(e2)}"
            case MergeMonoid(m, e1, e2) => m match {
              case _: SumMonoid => ???
              case _: MaxMonoid => ???
              case _: MultiplyMonoid => ???
              case _: AndMonoid => s"${recurse(e1)} && ${recurse(e2)}"
              case _: OrMonoid => ???
            }
            case UnaryExp(op, e1) => op match {
              case _: Not => s"!${recurse(e1)}"
              case _: Neg => s"-${recurse(e1)}"
              case _: ToBool => s"${recurse(e1)}.toBoolean"
              case _: ToInt => s"${recurse(e1)}.toInt"
              case _: ToFloat => s"${recurse(e1)}.toFloat"
              case _: ToString => s"${recurse(e1)}.toString"
            }
          }

          c.parse(s"(arg => ${recurse(e)})")
        }

        def zero(m: PrimitiveMonoid): Tree = m match {
          case _: AndMonoid => q"true"
          case _: OrMonoid => q"false"
          case _: SumMonoid => q"0"
          case _: MultiplyMonoid => q"1"
          case _: MaxMonoid => q"0" // TODO: Fix since it is not a monoid
        }

        def fold(m: PrimitiveMonoid): Tree = m match {
          case _: AndMonoid => q"((a, b) => a && b)"
          case _: OrMonoid => q"((a, b) => a || b)"
          case _: SumMonoid => q"((a, b) => a + b)"
          case _: MultiplyMonoid => q"((a, b) => a * b)"
          case _: MaxMonoid => q"((a, b) => if (a > b) a else b)"
        }

        a match {
          case ScalaNest(m, e, f, p, g, child) => m match {
            case m1: PrimitiveMonoid =>
              val z1 = m1 match {
                case _: AndMonoid => BoolConst(true)
                case _: OrMonoid => BoolConst(false)
                case _: SumMonoid => IntConst("0")
                case _: MultiplyMonoid => IntConst("1")
                case _: MaxMonoid => IntConst("0") // TODO: Fix since it is not a monoid
              }
              val f1 = IfThenElse(BinaryExp(Eq(), g, Null), z1, e)
              q"""${build(child)}.groupBy(${exp(f)}).toList.map(v => (v._1, v._2.filter(${exp(p)}))).map(v => (v._1, v._2.map(${exp(f1)}))).map(v => (v._1, v._2.foldLeft(${zero(m1)})(${fold(m1)})))"""
            case m1: BagMonoid =>
              ???
            case m1: ListMonoid =>
              val f1 = q"""(arg => if (${exp(g)}(arg) == null) List() else ${exp(e)}(arg))""" // TODO: Remove indirect function call
              q"""${build(child)}.groupBy(${exp(f)}).toList.map(v => (v._1, v._2.filter(${exp(p)}))).map(v => (v._1, v._2.map($f1))).map(v => (v._1, v._2.to[scala.collection.immutable.List]))"""
            case m1: SetMonoid =>
              val f1 = q"""(arg => if (${exp(g)}(arg) == null) Set() else ${exp(e)}(arg))""" // TODO: Remove indirect function call
              q"""${build(child)}.groupBy(${exp(f)}).toSet.map(v => (v._1, v._2.filter(${exp(p)}))).map(v => (v._1, v._2.map($f1))).map(v => (v._1, v._2.to[scala.collection.immutable.Set]))"""
          }
          case ScalaOuterJoin(p, left, right) =>
            q"""
            ${build(left)}.flatMap(l =>
              if (l == null)
                List((null, null))
              else {
                val ok = ${build(right)}.map(r => (l, r)).filter(${exp(p)})
                if (ok.isEmpty)
                  List((l, null))
                else
                  ok
              }
            )
            """
          case r@ScalaReduce(m, e, p, child) =>
            val code = m match {
              case m1: PrimitiveMonoid =>
                // TODO: Replace foldLeft with fold?
                q"""${build(child)}.filter(${exp(p)}).map(${exp(e)}).foldLeft(${zero(m1)})(${fold(m1)})"""
              case _: BagMonoid =>
                /* TODO: There is no Bag implementation on the Scala or Java standard libs. Here we use Guava's Bag
                 * implementation. Verify if this dependency on Guava could cause problems.
                 * The code below does not import any of the dependencies, instead it uses the fully qualified package names.
                 * The rationale is that if the client code does any manipulation of the result as an ImmutableMultiset,
                 * then it must declare the imports to compile. If it does not downcast the result, then it does not need
                 * the imports.
                 */
                q"""val e = ${build(child)}.filter(${exp(p)}).map(${exp(e)})
                    com.google.common.collect.ImmutableMultiset.copyOf(scala.collection.JavaConversions.asJavaIterable(e))"""
              case _: ListMonoid =>
                q"""${build(child)}.filter(${exp(p)}).map(${exp(e)}).to[scala.collection.immutable.List]"""
              case _: SetMonoid =>
                q"""${build(child)}.filter(${exp(p)}).map(${exp(e)}).to[scala.collection.immutable.Set]"""
            }
            if (r eq physicalTree)
              code
            else
              q"""List($code)"""
          case ScalaSelect(p, child) => // The AST node Select() conflicts with built-in node Select() in c.universe
            q"""${build(child)}.filter(${exp(p)})"""
          case ScalaScan(name, _) =>
            Ident(TermName(name))

          // Spark operators
          case SparkScan(name, tipe) =>
            Ident(TermName(name))
          //            q"""${accessPaths(name)}"""

          case SparkSelect(p, child) =>
            q"""${build(child)}.filter(${exp(p)})"""

          case SparkReduce(m, e, p, child) =>
            val childCode = build(child)
            val code = m match {
              case m1: PrimitiveMonoid =>
                /* TODO: Use Spark implementations of monoids if supported. For instance, RDD has max and min actions.
                 * Compare the Spark implementations versus a generic code generator based on fold
                 */
                q"""$childCode.filter(${exp(p)}).map(${exp(e)}).fold(${zero(m1)})(${fold(m1)})"""

              case _: ListMonoid =>
                // TODO Can this be made more efficient?
                q"""$childCode.filter(${exp(p)}).map(${exp(e)}).toLocalIterator.to[scala.collection.immutable.List]"""

              case _: BagMonoid =>
                // TODO: Can we improve the lower bound for the value?
                q"""val m: scala.collection.Map[_, Long] = $childCode.filter(${exp(p)}).map(${exp(e)}).countByValue()
                    val b = com.google.common.collect.ImmutableMultiset.builder[Any]()
                    m.foreach( p => b.addCopies(p._1, p._2.toInt) )
                    b.build()
                 """
              case _: SetMonoid =>
                /* - calling distinct in each partition reduces the size of the data that has to be sent to the driver,
                 *   by eliminating the duplicates early.
                 *
                 * - toLocalIterator() retrieves one partition at a time by the driver, which requires less memory than
                 * collect(), which first gets all results.
                 *
                 * - toSet is a Scala local operation.
                 */
                val filterPredicate = exp(p)
                val mapFunction = exp(e)
                q"""$childCode.filter($filterPredicate).map($mapFunction).distinct.toLocalIterator.to[scala.collection.immutable.Set]"""
            }
            code

          case SparkNest(m, e, f, p, g, child) =>
            val childTree: Tree = build(child)
            //            println("Child: " + childTree)
            // Common part
            // build a ProductCons of the f variables and group rows of the child by its value
            //            val tree = q"""${childTree}.groupBy(${exp(f)}).map(v => (v._1, v._2.filter(${exp(p)})))"""
            childTree
          //            m match {
          //              case m1: PrimitiveMonoid =>
          //                val z1 = m1 match {
          //                  case _: AndMonoid => BoolConst(true)
          //                  case _: OrMonoid => BoolConst(false)
          //                  case _: SumMonoid => IntConst("0")
          //                  case _: MultiplyMonoid => IntConst("1")
          //                  case _: MaxMonoid => IntConst("0") // TODO: Fix since it is not a monoid
          //                }
          //                val f1 = IfThenElse(BinaryExp(Eq(), g, Null), z1, e)
          //                q"""${tree}.map(v => (v._1, v._2.map(${exp(f1)}))).map(v => (v._1, v._2.foldLeft(${zero(m1)})(${fold(m1)})))"""
          //              case m1: BagMonoid =>
          //                ???
          //              case m1: ListMonoid =>
          //                ???
          //              case m1: SetMonoid =>
          //                val f1 = q"""(arg => if (${exp(g)}(arg) == null) Set() else ${exp(e)}(arg))""" // TODO: Remove indirect function call
          //                q"""${tree}.map(v => (v._1, v._2.map($f1))).map(v => (v._1, v._2.to[scala.collection.immutable.Set]))"""
          //            }
          case r@SparkJoin(p, left, right) =>
            val leftCode = build(left)
            val rightCode = build(right)
            q"""
               val rddLeft = $leftCode
               val rddRight = $rightCode
               rddLeft.cartesian(rddRight).filter(${exp(p)})
             """

          case SparkMerge(m, left, right) => ???

          /*
outer-join, X OJ(p) Y, is a left outer-join between X and Y using the join
predicate p. The domain of the second generator (the generator of w) in
Eq. (O5) is always nonempty. If Y is empty or there are no elements that
can be joined with v (this condition is tested by universal quantification),
then the domain is the singleton value [NULL], i.e., w becomes null.
Otherwise each qualified element w of Y is joined with v.

Delegates to PairRDDFunctions#leftOuterJoin. We cannot use this method directly, because
it takes RDDs in the following form: (k, v), (k, w) => (k, (v, Option(w)), using k to make the
matching. While we have (p, left, right) and want  (v, w) with p used to match the elements.
The code bellow does the following transformations:
1. Compute RDD(v, w) such that v!=null and p(v, w) is true.
2. Apply PairRDDFunctions#leftOuterJoin.
  RDD(v, w).leftOuterJoin( RDD(v, v) ) => (v, (v, Option[w]))
3. Transform in the output format of this operator.
   (v, (v, Some[w])) -> (v, w)
   (v, (v, None)) -> (v, null)
           */
          // TODO: Using null below can be problematic with RDDs of value types (AnyVal)?
          case SparkOuterJoin(p, left, right) =>
            val code = q"""
              val leftRDD = ${build(left)}
              val rightRDD = ${build(right)}
              val matching = leftRDD
                .cartesian(rightRDD)
                .filter(tuple => tuple._1 != null)
                .filter(${exp(p)})

              val resWithOption = leftRDD
                .map(v => (v, v))
                .leftOuterJoin(matching)

              resWithOption.map( {
                case (v1, (v2, None)) => (v1, null)
                case (v1, (v2, Some(w))) => (v1, w)
              })
              """
            code
          case SparkOuterUnnest(path, pred, child) => ???
          case SparkUnnest(path, pred, child) => ???
        }
      }

      build(physicalTree)
    }


    /** Macro main code.
      *
      * Check if the query string is known at compile time.
      * If so, build the algebra tree, then build the executable code, and return that to the user.
      * If not, build code that calls the interpreted executor and return that to the user.
      */
    println("Annotation: " + c.prefix.tree)
    println("Annottated target:\n" + showCode(annottees.head.tree) + "\nTree:\n" + showRaw(annottees.head.tree))

    val typ = c.typecheck(annottees.head.tree)
    println("Typed tree: " + typ + "\nTree:\n" + showRaw(typ))
    typ match {
      case ClassDef(_, className, _, Template(List(Select(Ident(pckName), parentName)), valDef, trees2)) =>
        println("classname: " + className)
        //        println("trees1: " + trees1 + " " + showRaw(trees1))
        println("trees1: " + showRaw(pckName) + " " + showRaw(parentName))
        println("valDef: " + valDef)
        println("trees2: " + trees2)

        //        println(s"Matched top level Query object. Name: $name, Body: $body")
        //        println(s"Matched top level Query object. Name: $name, Params: $params, Body: $body")
        var query: Option[String] = None
        val accessPathsBuilder = mutable.HashMap[String, AccessPath]()
        trees2.foreach(v => {
          println("+++" + v + " " + showRaw(v))
          v match {
            case ValDef(_, TermName("query "), _, Literal(Constant(queryString: String))) =>
              query = Some(queryString)

            case ValDef(_, TermName(termName), typeTree, accessPathTree) =>
              println("Matched: " + termName + " typeTree: " + typeTree + " -> " + showRaw(typeTree))
              //              println("Found access path. name: " + termName + ", type: " + typeTree + ", expression: " + accessPathTree)
              val (rawTpe, isSpark) = inferType(typeTree.tpe)
              println("Infered type: " + rawTpe)
              accessPathsBuilder.put(termName.trim, AccessPath(rawTpe, accessPathTree, isSpark))

            case _ =>
              println("Ignoring element: " + v)
          }
        })

        //      case q"class $name extends raw.RawQuery { ..$body }" =>
        //        println(s"Matched top level Query object. Name: $name, Body: $body")
        //        //        println(s"Matched top level Query object. Name: $name, Params: $params, Body: $body")
        //        var query: Option[String] = None
        //        val accessPathsBuilder = mutable.HashMap[String, AccessPath]()
        //
        //        //        params.foreach(v => {
        //        //          println("Matching v: " + v + " " + showRaw(v))
        //        //          //          val typed: Tree = c.typecheck(v.duplicate)
        //        //          //          println("Typed: " + typed)
        //        //          v match {
        //        //            case ValDef(_, term, typeTree, accessPathTree) =>
        //        //              val t: Tree = q""" $term : $typeTree"""
        //        //              println(" " + t + " " + showRaw(t))
        //        //              val typed = c.typecheck(t)
        //        //              println(" " + typed)
        //        //            case ValDef(_, TermName(termName), typeTree, accessPathTree) =>
        //        //              println("Found access path. name: " + termName + ", type: " + typeTree + ", raw: " + showRaw(typeTree) + ", expression: " + accessPathTree)
        //        //              println("Type checking: " + typeTree)
        //        //              val typed = c.typecheck(typeTree)
        //        //              println("Typed: " + typed)
        //        //              val (rawTpe, isSpark) = inferType(typeTree.tpe)
        //        //              accessPathsBuilder.put(termName, AccessPath(rawTpe, accessPathTree, isSpark))
        //        //          }
        //        //        })
        //
        //        body.foreach(v => {
        //          println("Matching v: " + v + " " + showRaw(v))
        //          val vTyped = c.typecheck(v)
        //          println("Matching field: " + v + ".\nTree: " + showRaw(vTyped))
        //          vTyped match {
        //            case ValDef(_, TermName("query"), _, Literal(Constant(queryString: String))) =>
        //              query = Some(queryString)
        //
        //            case ValDef(_, TermName(termName), typeTree, accessPathTree) =>
        //              println("Found access path. name: " + termName + ", type: " + typeTree + ", expression: " + accessPathTree)
        //              val (rawTpe, isSpark) = inferType(typeTree.tpe)
        //              accessPathsBuilder.put(termName, AccessPath(rawTpe, accessPathTree, isSpark))
        //          }
        //        })

        val accessPaths = accessPathsBuilder.toMap
        println("Access paths: " + accessPaths)
        println(s"Query: $query")

        val sources = accessPaths.map({ case (name, AccessPath(rawType, _, _)) => name -> rawType })
        println("Sources: " + sources)
        val world = new World(sources)
        // Parse the query, using the catalog generated from what the user gave.
        Query(query.get, world) match {
          case Right(logicalTree) =>
            val typer = new algebra.Typer(world)
            val isSpark: Map[String, Boolean] = accessPaths.map({ case (name, ap) => (name, ap.isSpark) })
            val physicalTree = LogicalToPhysicalAlgebra(logicalTree, isSpark)
            val algebraStr = PhysicalAlgebraPrettyPrinter(physicalTree)
            logger.info("Algebra:\n{}", algebraStr)

            println("trees2: " + trees2)
            val caseClasses: Set[Tree] = buildCaseClasses(logicalTree, world, typer)
            println("case classes:\n" + caseClasses)
            //            val generatedTree: Tree = buildCode(logicalTree, physicalTree, world, typer, accessPaths.map { case (name, AccessPath(_, tree, _)) => name -> tree })
            val generatedTree: Tree = buildCode(logicalTree, physicalTree, world, typer)
            val scalaCode = showCode(generatedTree)
            println("Query execution code:\n" + scalaCode)
            QueryLogger.log(query.get, algebraStr, scalaCode)

            val q"class $name($params) extends RawQuery { ..$body }" = annottees.head.tree
            println("body: " + body)
            println("params: " + params)

            val code = q"""
            class $className($params) extends RawQuery {
              ..$body
              ..$caseClasses
              def computeResult = {
                 $generatedTree
              }
            }
          """
            println("Complete code:\n" + showCode(code))
            c.Expr[Any](code)
          case Left(err) => bail(err.err)
        }
    }
  }
}

//object Raw {
//  def query(q: String, catalog: HList): Any = macro RawImpl.query
//}

abstract class RawQuery {
  val query: String

  def computeResult: Any
}

//class queryAnnotation(query: String, catalog: HList) extends StaticAnnotation {
class rawQueryAnnotation extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro RawImpl.query_impl
}