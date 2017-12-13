package mill.scalajsplugin

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import mill._
import mill.define.{Target, Task}
import mill.discover.Discovered
import mill.discover.Mirror.LabelledTarget
import mill.eval.Result
import mill.util.TestEvaluator
import sbt.internal.inc.CompileFailed
import utest._

trait HelloJSWorldModule extends ScalaJSModule {
  def scalaVersion = "2.12.4"
  def scalaJSVersion = "0.6.21"
  def basePath = HelloJSWorldTests.workspacePath
  override def mainClass = Some("Main")
}

object HelloJSWorld extends HelloJSWorldModule

object HelloJSWorldTests extends TestSuite {

  val srcPath = pwd / 'scalajsplugin / 'src / 'test / 'resource / "hello-js-world"
  val workspacePath = pwd / 'target / 'workspace / "hello-js-world"
  val outputPath = workspacePath / 'out
  val mainObject = workspacePath / 'src / 'main / 'scala / "Main.scala"

  def eval[T](t: Task[T], mapping: Map[Target[_], LabelledTarget[_]]) =
    TestEvaluator.eval(mapping, outputPath)(t)

  val helloWorldMapping = Discovered.mapping(HelloJSWorld)

  def tests: Tests = Tests {
    prepareWorkspace()
    'compile - {
      'fromScratch - {
        val Right((result, evalCount)) =
          eval(HelloJSWorld.compile, helloWorldMapping)

        val outPath = result.classes.path
        val analysisFile = result.analysisFile
        val outputFiles = ls.rec(outPath)
        val expectedClassfiles = compileClassfiles(outputPath / 'compile / 'classes)
        assert(
          outPath == outputPath / 'compile / 'classes,
          exists(analysisFile),
          outputFiles.toSet == expectedClassfiles,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) =
          eval(HelloJSWorld.compile, helloWorldMapping)
        assert(unchangedEvalCount == 0)
      }
    }
    'assembly - {
      'fromScratch - {
        val Right((result, evalCount)) =
          eval(HelloJSWorld.assembly, helloWorldMapping)
        assert(result.path == outputPath / "assembly.js")
      }
    }
  }

  def compileClassfiles(parentDir: Path) = Set(
    parentDir / "Main.class",
    parentDir / "Main$.class",
    parentDir / "Main$delayedInit$body.class",
    parentDir / "Main$.sjsir",
    parentDir / "Main$delayedInit$body.sjsir",
  )

  def prepareWorkspace(): Unit = {
    rm(workspacePath)
    mkdir(workspacePath / up)
    cp(srcPath, workspacePath)
  }

}
