package mill.scalajsplugin

import java.io.File

trait ScalaJSLinkerBridge {
  def link(sources: Seq[File], libraries: Seq[File], dest: File, main: Option[String]): Unit
}
