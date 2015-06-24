import sbt._
import Keys._
import com.typesafe.sbt.pgp.PgpKeys._
import spray.revolver.RevolverPlugin.Revolver
import spray.revolver.RevolverPlugin.Revolver

val commonSettings = Seq(
  organization := "me.chrons",
  version := Version.library,
  scalaVersion := "2.11.6",
  scalacOptions ++= Seq("-unchecked", "-feature", "-deprecation", "-encoding", "utf8"),
  testFrameworks += new TestFramework("utest.runner.Framework"),
  libraryDependencies ++= Seq(
    "com.lihaoyi" %%% "utest" % "0.3.1" % "test"
  )
)

def preventPublication(p: Project) =
  p.settings(
    publish :=(),
    publishLocal :=(),
    publishSigned :=(),
    publishLocalSigned :=(),
    publishArtifact := false,
    publishTo := Some(Resolver.file("Unused transient repository", target.value / "fakepublish")),
    packagedArtifacts := Map.empty)

lazy val booflow = crossProject
  .settings(commonSettings: _*)
  .settings(
    name := "booflow",
    libraryDependencies ++= Seq(
      "me.chrons" %%% "boopickle" % "0.1.5-SNAPSHOT"
    ),
    scmInfo := Some(ScmInfo(
      url("https://github.com/ochrons/booflow"),
      "scm:git:git@github.com:ochrons/booflow.git",
      Some("scm:git:git@github.com:ochrons/booflow.git"))),
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomExtra :=
      <url>https://github.com/ochrons/booflow</url>
        <licenses>
          <license>
            <name>MIT license</name>
            <url>http://www.opensource.org/licenses/mit-license.php</url>
          </license>
        </licenses>
        <developers>
          <developer>
            <id>ochrons</id>
            <name>Otto Chrons</name>
            <url>https://github.com/ochrons</url>
          </developer>
        </developers>,
    pomIncludeRepository := { _ => false },
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    }
  ).jsSettings(
    // use PhantomJS for testing, because we need real browser JS stuff like TypedArrays
    scalaJSStage in Global := FastOptStage,
    jsDependencies += RuntimeDOM,
    scalacOptions ++= (if (isSnapshot.value) Seq.empty
    else Seq({
      val a = baseDirectory.value.toURI.toString.replaceFirst("[^/]+/?$", "")
      val g = "https://raw.githubusercontent.com/ochrons/booflow"
      s"-P:scalajs:mapSourceURI:$a->$g/v${version.value}/"
    }))
  ).jvmSettings(
  )

lazy val booflowJS = booflow.js

lazy val booflowJVM = booflow.jvm

lazy val example = crossProject
  .settings(commonSettings: _*)
  .settings(
    name := "example",
    libraryDependencies ++= Seq(
    )
  )
  .jsSettings(
    bootSnippet := "BooApp().main();",
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "0.8.1",
      "com.lihaoyi" %%% "scalatags" % "0.4.6",
      "org.monifu" %%% "monifu" % "1.0-M1"
    )
  )
  .jvmSettings(Revolver.settings: _*)
  .jvmSettings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-RC3",
      "com.typesafe.akka" %% "akka-http-experimental" % "1.0-RC3"
    )
  )

lazy val exampleJS = preventPublication(example.js).settings(workbenchSettings: _*).dependsOn(booflowJS)

lazy val exampleJVM = preventPublication(example.jvm).settings(
  // reStart depends on running fastOptJS on the JS project
  Revolver.reStart <<= Revolver.reStart dependsOn (fastOptJS in(exampleJS, Compile)),
  (resourceGenerators in Compile) <+=
    (fastOptJS in Compile in exampleJS, packageScalaJSLauncher in Compile in exampleJS)
      .map((f1, f2) => Seq(f1.data, f2.data)),
  watchSources <++= (watchSources in exampleJS)
).dependsOn(booflowJVM)

lazy val root = preventPublication(project.in(file(".")))
  .settings()
  .aggregate(booflowJS, booflowJVM, exampleJS, exampleJVM)
