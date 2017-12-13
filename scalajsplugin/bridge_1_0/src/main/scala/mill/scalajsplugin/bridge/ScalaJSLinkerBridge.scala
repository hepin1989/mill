package mill
package scalajsplugin
package bridge

import java.io.File

import org.scalajs.core.tools.io._
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.ScalaConsoleLogger

class ScalaJSLinkerBridge {
  def link(sources: Array[File], libraries: Array[File], dest: File, main: String): Unit = {
    val config = StandardLinker.Config()
    val linker = StandardLinker(config)
    val cache = new IRFileCache().newCache
    val irContainers = FileScalaJSIRContainer.fromClasspath(sources ++ libraries)
    val irFiles = cache.cached(irContainers)
    val destFile = AtomicWritableFileVirtualJSFile(dest)
    val logger = new ScalaConsoleLogger
    val initializer = Option(main).map { cls => ModuleInitializer.mainMethodWithArgs(cls, "main") }
    linker.link(irFiles, initializer.toSeq, destFile, logger)
  }
}