import Dependencies.*
import sbtversionpolicy.Compatibility.None

val scala213 = "2.13.12"
val scala3 = "3.3.1"

val commonSettings = Seq(
  wartremoverExcluded += sourceManaged.value,
  Compile / compile / wartremoverErrors ++= Warts.allBut(
    Wart.Any,
    Wart.Nothing,
    Wart.ImplicitParameter,
    Wart.Throw,
    Wart.DefaultArguments,
    Wart.Recursion
  ),
  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) =>
      Seq(compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full))
    case _ => Nil
  }),
  Compile / scalacOptions ++= Seq("-Xfatal-warnings"),
  Compile / scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) => Seq("-Ykind-projector:underscores")
    case Some((2, _)) =>
      Seq("-Xsource:3", "-P:kind-projector:underscore-placeholders", "-Xlint:unused")
    case _ => Nil
  })
)

inThisBuild(
  List(
    organization := "io.github.endless4s",
    homepage := Some(url("https://github.com/endless4s/endless")),
    licenses := List("MIT License" -> url("http://opensource.org/licenses/mit-license.php")),
    developers := List(
      Developer(
        "jchapuis",
        "Jonas Chapuis",
        "me@jonaschapuis.com",
        url("https://jonaschapuis.com")
      )
    ),
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeProjectHosting := Some(
      xerial.sbt.Sonatype.GitHubHosting("endless4s", "endless", "me@jonaschapuis.com")
    ),
    Global / onChangedBuildSource := ReloadOnSourceChanges,
    PB.protocVersion := "3.17.3", // works on Apple Silicon,
    versionPolicyIntention := Compatibility.None,
    versionScheme := Some("early-semver"),
    versionPolicyIgnoredInternalDependencyVersions := Some(
      "^\\d+\\.\\d+\\.\\d+\\+\\d+".r
    ), // Support for versions generated by sbt-dynver
    coverageExcludedPackages := "<empty>;endless.transaction.test.*;endless.transaction.proto.*;endless.transaction.example.proto.*"
  )
)

lazy val lib = (project in file("."))
  .settings(commonSettings*)
  .settings(scalaVersion := scala213, crossScalaVersions := Seq(scala213, scala3))
  .settings(name := "endless-transaction")
  .settings(
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    )
  )
  .settings(
    libraryDependencies ++= Seq(
      `endless-core`,
      `endless-protobuf-helpers`,
      `scalapb-runtime`,
      `cats-effect`
    ) ++ (munit ++ `munit-cats-effect-3` ++ `scalacheck-effect-munit` ++ `log4cats-testing` ++ `cats-scalacheck` ++ `cats-effect-testkit`)
      .map(_ % Test)
  )

lazy val pekkoRuntime = (project in file("pekko"))
  .dependsOn(lib)
  .settings(commonSettings*)
  .settings(scalaVersion := scala213, crossScalaVersions := Seq(scala213, scala3))
  .settings(libraryDependencies ++= Seq(`endless-runtime-pekko`) ++ pekkoProvided)
  .settings(name := "endless-transaction-pekko")

lazy val akkaRuntime = (project in file("akka"))
  .dependsOn(lib)
  .settings(commonSettings*)
  .settings(scalaVersion := scala213, crossScalaVersions := Seq(scala213, scala3))
  .settings(libraryDependencies ++= Seq(`endless-runtime-akka`))
  .settings(name := "endless-transaction-akka")

lazy val example = (project in file("example"))
  .settings(commonSettings*)
  .settings(scalaVersion := scala213)
  .dependsOn(lib % "test->test;compile->compile", pekkoRuntime)
  .settings(name := "endless-transaction-example")
  .settings(
    Compile / PB.targets := Seq(scalapb.gen() -> (Compile / sourceManaged).value / "scalapb")
  )
  .settings(
    libraryDependencies ++= Seq(
      `endless-core`,
      `logback-classic`,
      `scalapb-runtime`
    ) ++ pekko ++ pekkoTest ++ http4s ++ `log4cats-core` ++ `log4cats-slf4j` ++
      (munit ++ `munit-cats-effect-3` ++ `scalacheck-effect-munit` ++ `log4cats-testing` ++ `cats-scalacheck` ++ `cats-effect-testkit`)
        .map(_ % Test)
  )
  .settings(run / fork := true, publish / skip := true)

lazy val root = project
  .aggregate(lib, pekkoRuntime, akkaRuntime, example)
  .dependsOn(example)
  .settings(commonSettings*)
  .settings(crossScalaVersions := Nil)
  .settings(publish / skip := true)
