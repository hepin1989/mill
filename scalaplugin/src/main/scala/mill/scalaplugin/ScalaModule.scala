package mill
package scalaplugin

import ammonite.ops._
import coursier.{Cache, MavenRepository, Repository, Resolution}
import mill.define.Task
import mill.define.Task.{Module, TaskModule}
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.{createAssembly, createJar, interactiveSubprocess, subprocess}
import mill.scalaplugin.publish._

import Lib._
trait TestScalaModule extends ScalaModule with TaskModule {
  override def defaultCommandName() = "test"
  def testFramework: T[String]

  def forkWorkingDir = ammonite.ops.pwd
  def forkArgs = T{ Seq.empty[String] }
  def forkTest(args: String*) = T.command{
    val outputPath = tmp.dir()/"out.json"

    Jvm.subprocess(
      mainClass = "mill.scalaplugin.TestRunner",
      classPath = Jvm.gatherClassloaderJars(),
      jvmOptions = forkArgs(),
      options = Seq(
        testFramework(),
        (runDepClasspath().map(_.path) :+ compile().classes.path).mkString(" "),
        Seq(compile().classes.path).mkString(" "),
        args.mkString(" "),
        outputPath.toString
      ),
      workingDir = forkWorkingDir
    )
    upickle.default.read[Option[String]](ammonite.ops.read(outputPath)) match{
      case Some(errMsg) => Result.Failure(errMsg)
      case None => Result.Success(())
    }
  }
  def test(args: String*) = T.command{
    TestRunner(
      testFramework(),
      runDepClasspath().map(_.path) :+ compile().classes.path,
      Seq(compile().classes.path),
      args
    ) match{
      case Some(errMsg) => Result.Failure(errMsg)
      case None => Result.Success(())
    }
  }
}
trait ScalaModule extends Module with TaskModule{ outer =>
  def defaultCommandName() = "run"
  trait Tests extends TestScalaModule{
    def scalaVersion = outer.scalaVersion()
    override def projectDeps = Seq(outer)
  }
  def scalaVersion: T[String]
  def mainClass: T[Option[String]] = None

  def scalaBinaryVersion = T{ scalaVersion().split('.').dropRight(1).mkString(".") }
  def ivyDeps = T{ Seq[Dep]() }
  def compileIvyDeps = T{ Seq[Dep]() }
  def scalacPluginIvyDeps = T{ Seq[Dep]() }
  def runIvyDeps = T{ Seq[Dep]() }
  def basePath: Path

  def scalacOptions = T{ Seq.empty[String] }
  def javacOptions = T{ Seq.empty[String] }

  val repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  def projectDeps = Seq.empty[ScalaModule]
  def depClasspath = T{ Seq.empty[PathRef] }


  def upstreamRunClasspath = T{
    Task.traverse(
      for (p <- projectDeps)
      yield T.task(p.runDepClasspath() ++ Seq(p.compile().classes))
    )
  }

  def upstreamCompileDepClasspath = T{
    Task.traverse(projectDeps.map(_.compileDepClasspath))
  }
  def upstreamCompileDepSources = T{
    Task.traverse(projectDeps.map(_.externalCompileDepSources))
  }

  def upstreamCompileOutput = T{
    Task.traverse(projectDeps.map(_.compile))
  }

  def resolveDeps(deps: Task[Seq[Dep]], sources: Boolean = false) = T.task{
    resolveDependencies(
      repositories,
      scalaVersion(),
      scalaBinaryVersion(),
      deps()
    )
  }
  def externalCompileDepClasspath = T{
    upstreamCompileDepClasspath().flatten ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())}
    )()
  }
  def externalCompileDepSources: T[Seq[PathRef]] = T{
    upstreamCompileDepSources().flatten ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())},
      sources = true
    )()
  }
  /**
    * Things that need to be on the classpath in order for this code to compile;
    * might be less than the runtime classpath
    */
  def compileDepClasspath: T[Seq[PathRef]] = T{
    upstreamCompileOutput().map(_.classes) ++
    depClasspath() ++
    externalCompileDepClasspath()
  }

  /**
    * Strange compiler-bridge jar that the Zinc incremental compile needs
    */
  def compilerBridge: T[PathRef] = T{
    val compilerBridgeKey = "MILL_COMPILER_BRIDGE_" + scalaVersion().replace('.', '_')
    val compilerBridgePath = sys.props(compilerBridgeKey)
    if (compilerBridgePath != null) PathRef(Path(compilerBridgePath), quick = true)
    else {
      val dep = compilerBridgeIvyDep(scalaVersion())
      val classpath = resolveDependencies(
        repositories,
        scalaVersion(),
        scalaBinaryVersion(),
        Seq(dep)
      )
      classpath match {
        case Seq(single) => PathRef(single.path, quick = true)
        case Seq() => throw new Exception(dep + " resolution failed")
        case _ => throw new Exception(dep + " resolution resulted in more than one file")
      }
    }
  }

  def scalacPluginClasspath: T[Seq[PathRef]] =
    resolveDeps(
      T.task{scalacPluginIvyDeps()}
    )()

  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Seq[PathRef]] = T{
    resolveDeps(
      T.task{scalaCompilerIvyDeps(scalaVersion()) ++ scalaRuntimeIvyDeps(scalaVersion())},
    )() ++ scalacPluginClasspath()
  }

  /**
    * Things that need to be on the classpath in order for this code to run
    */
  def runDepClasspath: T[Seq[PathRef]] = T{
    upstreamRunClasspath().flatten ++
    depClasspath() ++
    resolveDeps(
      T.task{ivyDeps() ++ runIvyDeps() ++ scalaRuntimeIvyDeps(scalaVersion())},
    )()
  }

  def prependShellScript: T[String] = T{ "" }

  def sources = T.source{ basePath / 'src }
  def resources = T.source{ basePath / 'resources }
  def allSources = T{ Seq(sources()) }
  def compile: T[CompilationResult] = T.persistent{
    compileScala(
      scalaVersion(),
      allSources().map(_.path),
      compileDepClasspath().map(_.path),
      scalaCompilerClasspath().map(_.path),
      compilerBridge().path,
      scalacOptions(),
      scalacPluginClasspath().map(_.path),
      javacOptions(),
      upstreamCompileOutput()
    )
  }
  def assembly = T{
    val outDir = T.ctx().dest/up
    val n = name()
    val v = version()
    val jarName = s"${n}-${v}.jar"
    val dest = outDir/jarName
    createAssembly(
      dest,
      (runDepClasspath().filter(_.path.ext != "pom") ++ Seq(resources(), compile().classes)).map(_.path).filter(exists),
      prependShellScript = prependShellScript()
    )
    PathRef(dest)
  }

  def classpath = T{ Seq(resources(), compile().classes) }

  def jar = T{
    val jarName = s"${name()}_${scalaBinaryVersion()}-${version()}.jar"
    createJar(
      T.ctx().dest / jarName,
      Seq(resources(), compile().classes).map(_.path).filter(exists),
      mainClass()
    )
  }

  def docsJar = T{
    val outDir = T.ctx().dest

    val javadocDir = outDir / 'javadoc
    mkdir(javadocDir)

    val options = {
      val files = ls.rec(sources().path).filter(_.isFile).map(_.toNIO.toString)
      files ++ Seq("-d", javadocDir.toNIO.toString, "-usejavacp")
    }

    val jarName = s"${name()}_${scalaBinaryVersion()}-${version()}-javadoc.jar"

    subprocess(
      "scala.tools.nsc.ScalaDoc",
      compileDepClasspath().filterNot(_.path.ext == "pom").map(_.path),
      options = options
    )
    createJar(outDir / jarName, Seq(javadocDir))
  }

  def sourcesJar = T{
    val outDir = T.ctx().dest
    val jarName = s"${name()}_${scalaBinaryVersion()}-${version()}-sources.jar"

    val inputs = Seq(sources(), resources()).map(_.path).filter(exists)

    createJar(outDir / jarName, inputs)
  }

  def run() = T.command{
    val main = mainClass().getOrElse(throw new RuntimeException("No mainClass provided!"))
    subprocess(main, runDepClasspath().map(_.path) :+ compile().classes.path)
  }

  def runMain(mainClass: String) = T.command{
    subprocess(mainClass, runDepClasspath().map(_.path) :+ compile().classes.path)
  }

  def console() = T.command{
    interactiveSubprocess(
      mainClass = "scala.tools.nsc.MainGenericRunner",
      classPath = externalCompileDepClasspath().map(_.path) :+ compile().classes.path,
      options = Seq("-usejavacp")
    )
  }

  def organization: T[String] = "acme"
  def name: T[String] = pwd.last.toString
  def version: T[String] = "0.0.1-SNAPSHOT"

  // build artifact name as "mill-2.12.4" instead of "mill-2.12"
  def useFullScalaVersionForPublish: Boolean = false

  def pom = T{
    // pom part
    val scalaFull = scalaVersion()
    val scalaBin = scalaBinaryVersion()
    val org = organization()
    val n = name()
    val v = version()
    val deps = ivyDeps()
    val dependencies = deps.map(d => Artifact.fromDep(d, scalaFull, scalaBin))
    val artScalaVersion = if (useFullScalaVersionForPublish) scalaFull else scalaBin
    val artifact = ScalaArtifact(org, n, v, artScalaVersion)
    val settings =
      PomSettings(
        artifact.group,
        "url",
        licenses = Seq(License("Apache License, Version 2.0", "http://www.apache.org/licenses/LICENSE-2.0")),
        scm = SCM("", ""),
        developers = Seq(
          Developer("rockjam", "Nikolai Tatarinov", "https://github.com/rockjam")
        )
      )

    val pom = PomFile.generatePom(artifact, dependencies, settings)
    val pomName = s"${n}_${scalaBin}-${v}.pom"
    val pomPath = T.ctx().dest / pomName
    write.over(pomPath, pom)
    PathRef(pomPath)
  }

  def publish() = T.command {
    val artifacts = Seq(jar(), sourcesJar(), docsJar(), pom()).map(_.path)
    val artifact = ScalaArtifact(organization(), name(), version(), scalaBinaryVersion())
    SonatypeLib.publish(artifacts, artifact)(T.ctx().log)
  }

  def publishLocal() = T.command {
    val file = jar() // there should be sequence of files
    val scalaFull = scalaVersion()
    val scalaBin = scalaBinaryVersion()
    val deps = ivyDeps()
    val dependencies = deps.map(d => Artifact.fromDep(d, scalaFull, scalaBin))
    val artScalaVersion = if (useFullScalaVersionForPublish) scalaFull else scalaBin
    val artifact = ScalaArtifact(organization(), name(), version(), artScalaVersion)
    LocalPublisher.publish(file, artifact, dependencies)
  }

}
trait SbtScalaModule extends ScalaModule { outer =>
  def basePath: Path
  override def sources = T.source{ basePath / 'src / 'main / 'scala }
  override def resources = T.source{ basePath / 'src / 'main / 'resources }
  trait Tests extends super.Tests{
    def basePath = outer.basePath
    override def sources = T.source{ basePath / 'src / 'test / 'scala }
    override def resources = T.source{ basePath / 'src / 'test / 'resources }
  }
}
