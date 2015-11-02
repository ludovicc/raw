package raw
package calculus

import java.time.format.DateTimeFormatter

trait DateTimeFormatAnalyzer extends Analyzer with NodePosition {

  import scala.collection.immutable.Seq
  import org.kiama.rewriting.Rewriter._
  import Calculus.ToEpoch

  private lazy val collectDateTimeFormatErrors =
    collect[List, Seq[RawError]] {
      case t @ ToEpoch(_, fmt) =>
        try {
          DateTimeFormatter.ofPattern(fmt)
          Seq()
          } catch {
            case e: java.lang.IllegalArgumentException =>
            Seq(InvalidDateTimeFormatSyntax(e.getMessage, Some(parserPosition(t))))
          }
    }

  lazy val dateTimeFormatErrors =
    collectDateTimeFormatErrors(tree.root).flatten

}
