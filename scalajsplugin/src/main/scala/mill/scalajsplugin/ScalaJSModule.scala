package mill
package scalajsplugin

import java.io.File

import ammonite.ops.Path
import mill.scalajsplugin.Lib._
import mill.scalaplugin.Lib.resolveDependencies
import mill.scalaplugin.{Dep, ScalaModule, TestScalaModule}

trait ScalaJSModule extends ScalaModule { outer =>

  def scalaJSVersion: T[String]

  def scalaJSBinaryVersion = T{ scalaJSVersion().split('.').dropRight(1).mkString(".") }

  def scalaJSLinkerClasspath: T[Seq[PathRef]] = T{
    val jsBridgeKey = "MILL_SCALAJS_BRIDGE_" + scalaJSBinaryVersion().replace('.', '_')
    val jsBridgePath = sys.props(jsBridgeKey)
    if (jsBridgePath != null) jsBridgePath.split(File.pathSeparator).map(f => PathRef(Path(f), quick = true)).toVector
    else {
      val dep = scalaJSLinkerIvyDep(scalaJSBinaryVersion())
      resolveDependencies(
        repositories,
        scalaVersion(),
        scalaBinaryVersion(),
        Seq(dep)
      )
    }
  }

  override def assembly = T{
    val linker = scalaJSLinkerBridge(scalaJSLinkerClasspath().map(_.path))
    link(mainClass(), Seq(compile().classes.path), compileDepClasspath().map(_.path), linker)
  }

  override def scalacPluginIvyDeps = T{
    super.scalacPluginIvyDeps() ++ Seq(Dep.Point("org.scala-js", "scalajs-compiler", scalaJSVersion()))
  }

  override def ivyDeps = T{
    super.ivyDeps() ++ Seq(Dep("org.scala-js", "scalajs-library", scalaJSVersion()))
  }

}

trait TestScalaJSModule extends ScalaJSModule with TestScalaModule