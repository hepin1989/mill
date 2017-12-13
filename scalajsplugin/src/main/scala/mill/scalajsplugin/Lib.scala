package mill
package scalajsplugin

import java.io.File
import java.net.URLClassLoader

import ammonite.ops.{Path, mkdir, rm, _}
import mill.eval.PathRef
import mill.scalaplugin.Dep
import mill.util.Ctx

import scala.collection.breakOut
import scala.language.reflectiveCalls

object Lib {

  def scalaJSLinkerIvyDep(scalaJSVersion: String): Dep =
    Dep("com.lihaoyi", s"mill-jsbridge_${scalaJSVersion.replace('.', '_')}", "0.1-SNAPSHOT")

  def scalaJSLinkerBridge(classPath: Seq[Path]): ScalaJSLinkerBridge = {
    val cl = new URLClassLoader(classPath.map(_.toIO.toURI.toURL).toArray)
    val bridge = cl.loadClass("mill.scalajsplugin.bridge.ScalaJSLinkerBridge").newInstance().asInstanceOf[ {
      def link(sources: Array[File], libraries: Array[File], dest: File, main: String): Unit
    }]
    (sources: Seq[File],
     libraries: Seq[File],
     dest: File,
     main: Option[String]) =>
      bridge.link(sources.toArray, libraries.toArray, dest, main.orNull)
  }

  def link(main: Option[String],
           inputPaths: Seq[Path],
           libraries: Seq[Path],
           linker: ScalaJSLinkerBridge)
          (implicit ctx: Ctx.DestCtx): PathRef = {
    val outputPath = ctx.dest.copy(segments = ctx.dest.segments.init :+ (ctx.dest.segments.last + ".js"))
    rm(outputPath)
    if (inputPaths.nonEmpty) {
      mkdir(outputPath / up)
      val inputFiles: Vector[File] = inputPaths.map(ls).flatMap(_.filter(_.ext == "sjsir")).map(_.toIO)(breakOut)
      linker.link(inputFiles, libraries.map(_.toIO)(breakOut), outputPath.toIO, main)
    }
    PathRef(outputPath)
  }

}
