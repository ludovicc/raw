package raw.perf

import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger

import com.google.common.io.Resources
import com.typesafe.scalalogging.StrictLogging
import org.apache.spark.rdd.RDD
import raw.RawQuery
import raw.datasets.AccessPath
import raw.executionserver.RawMutableURLClassLoader

import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.{Global, Settings}

class QueryCompilerClient(val rawClassloader: RawMutableURLClassLoader, outputDir: Option[Path] = None) extends StrictLogging {
  private[this] val baseOutputDir: Path = outputDir match {
    case Some(path) => path
    case None => Files.createTempDirectory("rawqueries")
  }

  // Where the server saves the generated scala source for each query
  private[this] val sourceOutputDir: Path = {
    val dir = baseOutputDir.resolve("src")
    Files.createDirectory(dir)
    dir
  }

  // Where the compiler writes the generated classes that implement the queries.
  private[this] val classOutputDir: Path = {
    val dir = baseOutputDir.resolve("classes")
    Files.createDirectory(dir)
    // Point the classloader to the directory with the query classes.
    this.rawClassloader.addURL(dir.toUri.toURL)
    dir
  }

  private[this] val compilerSettings = {
    val settings = new Settings
    settings.embeddedDefaults[QueryCompilerClient]

    // Needed for macro annotations
    val mpPlugin: URL = Resources.getResource("paradise_2.11.7-2.1.0-M5.jar")
    val p = Paths.get(mpPlugin.toURI)
    logger.info("Loading plugin: " + p)
    settings.processArgumentString(s"-Xplugin:${p.toString}") match {
      case (false, xs) => throw new RuntimeException("Failed to set configuration: " + xs)
      case (true, _) =>
    }
    settings.require.tryToSet(List("macroparadise"))

    settings.usejavacp.value = true
    //settings.showPlugins only works if you're not compiling a file, same as -help
    logger.info(s"Compiling queries to directory: $classOutputDir")
    settings.d.tryToSet(List(classOutputDir.toString))
    settings
  }
  private[this] val compileReporter = new StoreReporter()

  private[this] val compiler = new Global(compilerSettings, compileReporter)

  private[this] val ai = new AtomicInteger()

  private[this] def newClassName(): String = {
    "Query" + ai.getAndIncrement()
  }

  def compileOQL(oql: String, accessPaths: List[AccessPath[_]]): Either[String, RawQuery] = {
    compile("oql", oql, accessPaths)
  }

  def compileLogicalPlan(plan: String, accessPaths: List[AccessPath[_]]): Either[String, RawQuery] = {
    compile("plan", plan, accessPaths)
  }

  private[this] def compile(queryFieldName: String, query: String, accessPaths: List[AccessPath[_]]): Either[String, RawQuery] = {
    //    logger.info("Access paths: " + accessPaths)
    val queryName = newClassName()
    val aps: List[String] = accessPaths.map(ap => ap.tag.toString())
    logger.info(s"Access paths: $aps")

    /* For every top level type argument of the access path, import the containing package. The is, for the following
     * access paths: RDD[raw.Publications], RDD[raw.patients.Patient], generate "import raw._" and "import raw.patients._"
     *
     * NOTE: this does not check nested types, that is, if Patient contains references to a Diagnostic instance,
     * it will only import the package of Patient. Therefore, any nested types should be in the same package as the
     * top level type. This limitation can be eliminated by using TypeTags instead of ClassTags in the AccessPath class
     * and by recursively scanning the full type of the access path.
     */
    val imports = aps.map(ap => ap.lastIndexOf(".") match {
      case -1 => return Left(s"Case classes in access paths should not be at top level package: $ap")
      case i: Int => "import " + ap.substring(0, i + 1) + "_"
    }).toSet.mkString("\n")

    //    val imports = accessPaths.map(ap => s"import ${ap.tag.toString()}").mkString("\n")
    val args = accessPaths.map(ap => s"${ap.name}: RDD[${ap.tag.runtimeClass.getSimpleName}]").mkString(", ")

    val code = s"""
package raw.query

import org.apache.spark.rdd.RDD
import raw.{rawQueryAnnotation, RawQuery}
$imports

@rawQueryAnnotation
class $queryName($args) extends RawQuery {
  val $queryFieldName =
  \"\"\"
  $query
  \"\"\"
}
"""

    logger.info(s"Generated code:\n$code")
    val srcFile: Path = sourceOutputDir.resolve(queryName + ".scala")
    Files.write(srcFile, code.getBytes(StandardCharsets.UTF_8))
    logger.info(s"Wrote source file: ${srcFile.toAbsolutePath}")

    // Compile the query
    val run = new compiler.Run()
    run.compile(List(srcFile.toString))
    if (compileReporter.hasErrors) {
      // the reporter keeps the state between runs, so it must be explicitly reset so that errors from previous
      // compilation runs are not falsely reported in the subsequent runs
      val message = "Query compilation failed. Compilation messages:\n" + compileReporter.infos.mkString("\n")
      compileReporter.reset()
      return Left(message)
    }

    // Load the main query class
    val queryClass = s"raw.query.$queryName"
    logger.info("Creating new instance of: " + queryClass)
    val clazz = rawClassloader.loadClass(queryClass)

    // Find the constructor
    val ctorTypeArgs = List.fill(accessPaths.size)(classOf[RDD[_]])
    val ctor = clazz.getConstructor(ctorTypeArgs: _*)

    // Create an instance of the query using the rdd instance given in the access paths.
    val ctorArgs = accessPaths.map(ap => ap.path)
    Right(ctor.newInstance(ctorArgs: _*).asInstanceOf[RawQuery])
  }
}