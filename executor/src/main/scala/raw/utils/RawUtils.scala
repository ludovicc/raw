package raw.utils

import java.nio.file.{Files, Path}

import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FileUtils

object RawUtils extends StrictLogging {

  def cleanOrCreateDirectory(p: Path) = {
    if (Files.isDirectory(p)) {
      FileUtils.cleanDirectory(p.toFile)
    } else {
      logger.info(s"Creating results directory: $p")
      Files.createDirectory(p)
    }
  }
}
