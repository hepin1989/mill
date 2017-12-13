import java.io.File

val sharedSettings = Seq(
  scalaVersion := "2.12.4",
  organization := "com.lihaoyi",
  libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % "test",

  testFrameworks += new TestFramework("mill.UTestFramework"),

  parallelExecution in Test := false,
  test in assembly := {},

  libraryDependencies += "com.lihaoyi" %% "acyclic" % "0.1.7" % "provided",
  scalacOptions += "-P:acyclic:force",
  autoCompilerPlugins := true,
  addCompilerPlugin("com.lihaoyi" %% "acyclic" % "0.1.7"),
  libraryDependencies += "com.lihaoyi" % "ammonite" % "1.0.3" % "test" cross CrossVersion.full,

  sourceGenerators in Test += Def.task {
    val file = (sourceManaged in Test).value / "amm.scala"
    IO.write(file, """object amm extends App { ammonite.Main().run() }""")
    Seq(file)
  }.taskValue

)

def bridge(bridgeVersion: String) = Project(
  id = "bridge" + bridgeVersion.replace('.', '_'),
  base = file("bridge/" + bridgeVersion.replace('.', '_')),
  settings = Seq(
    organization := "com.lihaoyi",
    scalaVersion := bridgeVersion,
    name := "mill-bridge",
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.scala-sbt" % "compiler-interface" % "1.0.5"
    ),
    (sourceGenerators in Compile) += Def.task{
      import sys.process._
      import collection.JavaConverters._
      val v = scalaBinaryVersion.value
      val url =
        s"http://repo1.maven.org/maven2/org/scala-sbt/compiler-bridge_$v/1.0.5/compiler-bridge_$v-1.0.5-sources.jar"
      val curlDest = java.nio.file.Paths.get(target.value.toString, "sources")

      Seq("rm", "-rf", curlDest.toString).!
      java.nio.file.Files.createDirectories(curlDest)

      Seq("curl", "-L", "-o", curlDest.resolve("bridge.jar").toString, url).!
      Seq("unzip", curlDest.resolve("bridge.jar").toString, "-d", curlDest.toString).!

      val sources = java.nio.file.Files.walk(curlDest)
        .iterator
        .asScala
        .filter(_.toString.endsWith(".scala"))
        .map(_.toFile)
        .toSeq

      sources
    }.taskValue
  )
)
lazy val bridge2_10_6 = bridge("2.10.6")
lazy val bridge2_11_8 = bridge("2.11.8")
//lazy val bridge2_11_9 = bridge("2.11.9")
//lazy val bridge2_11_10 = bridge("2.11.10")
lazy val bridge2_11_11 = bridge("2.11.11")
//lazy val bridge2_12_0 = bridge("2.12.0")
//lazy val bridge2_12_1 = bridge("2.12.1")
//lazy val bridge2_12_2 = bridge("2.12.2")
lazy val bridge2_12_3 = bridge("2.12.3")
lazy val bridge2_12_4 = bridge("2.12.4")

lazy val core = project
  .settings(
    sharedSettings,
    name := "mill-core",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
      "com.lihaoyi" %% "sourcecode" % "0.1.4",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.lihaoyi" % "ammonite" % "1.0.3" cross CrossVersion.full,
      "org.scala-sbt" %% "zinc" % "1.0.5",
      "org.scala-sbt" % "test-interface" % "1.0"
    )
  )

val bridgeProps = Def.task{
  val mapping = Map(
    "MILL_COMPILER_BRIDGE_2_10_6" -> (packageBin in (bridge2_10_6, Compile)).value.absolutePath,
    "MILL_COMPILER_BRIDGE_2_11_8" -> (packageBin in (bridge2_11_8, Compile)).value.absolutePath,
    "MILL_COMPILER_BRIDGE_2_11_11" -> (packageBin in (bridge2_11_11, Compile)).value.absolutePath,
    "MILL_COMPILER_BRIDGE_2_12_3" -> (packageBin in (bridge2_12_3, Compile)).value.absolutePath,
    "MILL_COMPILER_BRIDGE_2_12_4" -> (packageBin in (bridge2_12_4, Compile)).value.absolutePath
  )
  for((k, v) <- mapping) yield s"-D$k=$v"
}
lazy val scalaplugin = project
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    sharedSettings,
    name := "mill-scalaplugin",
    fork in Test := true,
    baseDirectory in (Test, test) := (baseDirectory in (Test, test)).value / "..",
    javaOptions in (Test, test) := bridgeProps.value.toSeq
  )
lazy val scalajsplugin = project
  .dependsOn(scalaplugin % "compile->compile;test->test")
  .settings(
    sharedSettings,
    name := "mill-scalajsplugin",
    fork in Test := true,
    baseDirectory in (Test, test) := (baseDirectory in (Test, test)).value / "..",
    javaOptions in (Test, test) := bridgeProps.value.toSeq
  )
lazy val scalajsbridge_0_6 = project
  .in(file("scalajsplugin/bridge_0_6"))
  .settings(
    sharedSettings,
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-tools" % "0.6.21"
    )
  )
lazy val scalajsbridge_1_0 = project
  .in(file("scalajsplugin/bridge_1_0"))
  .settings(
    sharedSettings,
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-tools" % "1.0.0-M2"
    )
  )
val jsbridgeProps = Def.task{
  def bridgeClasspath(depClasspath: Classpath, jar: File) = {
    (depClasspath.files :+ jar).map(_.absolutePath).mkString(File.pathSeparator)
  }
  val mapping = Map(
    "MILL_SCALAJS_BRIDGE_0_6" -> bridgeClasspath((dependencyClasspath in (scalajsbridge_0_6, Compile)).value,
                                                 (packageBin in (scalajsbridge_0_6, Compile)).value),
    "MILL_SCALAJS_BRIDGE_1_0" -> bridgeClasspath((dependencyClasspath in (scalajsbridge_1_0, Compile)).value,
                                                 (packageBin in (scalajsbridge_1_0, Compile)).value)
  )
  for((k, v) <- mapping) yield s"-D$k=$v"
}
lazy val bin = project
  .dependsOn(scalaplugin, scalajsplugin)
  .settings(
    sharedSettings,
    assemblyOption in assembly := {
      (assemblyOption in assembly).value.copy(
        prependShellScript = Some(
          Seq(
            "#!/usr/bin/env sh",
            s"""exec java ${(bridgeProps.value ++ jsbridgeProps.value).mkString(" ")} -cp "$$0" mill.Main "$$@" """
          )
        )
      )
    },
    assembly in Test := {
      val dest = target.value/"mill"
      IO.copyFile(assembly.value, dest)
      import sys.process._
      Seq("chmod", "+x", dest.getAbsolutePath).!
      dest
    }
  )
