import Dependencies.*
import sbt.project

val scala213 = "2.13.12"
val scala3 = "3.4.1"

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
  scalaVersion := scala213,
  crossScalaVersions := Seq(scala213, scala3),
  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) =>
      Seq(compilerPlugin("org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full))
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
    homepage := Some(url("https://github.com/endless4s/endless-transaction")),
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
      xerial.sbt.Sonatype.GitHubHosting("endless4s", "endless-transaction", "me@jonaschapuis.com")
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

lazy val lib = (project in file("lib"))
  .settings(commonSettings *)
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
  .settings(commonSettings *)
  .settings(libraryDependencies ++= Seq(`endless-runtime-pekko`) ++ pekkoProvided)
  .settings(name := "endless-transaction-pekko")

lazy val akkaRuntime = (project in file("akka"))
  .dependsOn(lib)
  .settings(commonSettings *)
  .settings(libraryDependencies ++= Seq(`endless-runtime-akka`) ++ akkaProvided)
  .settings(name := "endless-transaction-akka")

lazy val example = (project in file("example"))
  .settings(commonSettings *)
  .dependsOn(lib % "test->test;compile->compile", pekkoRuntime, akkaRuntime)
  .settings(
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    Compile / PB.targets := Seq(scalapb.gen() -> (Compile / sourceManaged).value / "scalapb")
  )
  .settings(
    libraryDependencies ++= Seq(
      `endless-core`,
      `logback-classic`,
      `scalapb-runtime`
    ) ++ pekko ++ pekkoTest ++ akka ++ akkaTest ++ http4s ++ `log4cats-core` ++ `log4cats-slf4j` ++
      (munit ++ `munit-cats-effect-3` ++ `scalacheck-effect-munit` ++ `log4cats-testing` ++ `cats-scalacheck` ++ `cats-effect-testkit` ++ scalatest ++
        Seq(postgresql, `pekko-persistence-jdbc`) ++ slick).map(_ % Test)
  )
  .settings(crossScalaVersions := Nil)
  .enablePlugins(MultiJvmPlugin)
  .configs(MultiJvm)
  .settings(run / fork := true, publish / skip := true)

// Define a `Configuration` for each project.
val Lib = config("lib")
val AkkaRuntime = config("akka-runtime")
val PekkoRuntime = config("pekko-runtime")
val scaladocSiteProjects = List(
  lib -> (Lib, "endless.transaction", "lib"),
  akkaRuntime -> (AkkaRuntime, "endless.transaction.akka", "akka"),
  pekkoRuntime -> (PekkoRuntime, "endless.transaction.pekko", "pekko")
)
lazy val documentation = (project in file("documentation"))
  .enablePlugins(
    ParadoxMaterialThemePlugin,
    SitePreviewPlugin,
    ParadoxPlugin,
    ParadoxSitePlugin,
    SiteScaladocPlugin
  )
  .settings(
    paradoxProperties ++= (
      scaladocSiteProjects.map { case (_, (_, pkg, path)) =>
        s"scaladoc.${pkg}.base_url" -> s"api/${path}"
      }.toMap
    ),
    paradoxProperties ++= List(
      ("akka.min.version" -> akkaVersion),
      ("pekko.min.version" -> pekkoVersion)
    ).toMap,
    scaladocSiteProjects.flatMap { case (project, (conf, _, path)) =>
      SiteScaladocPlugin.scaladocSettings(
        conf,
        project / Compile / packageDoc / mappings,
        s"api/${path}"
      )
    },
    Compile / paradoxProperties ++= Map(
      "project.description" -> "endless-transaction is a Scala library that provides a functional abstraction for distributed transactions based on cats-effect and the endless library.",
      "project.title" -> "endless4s-transaction",
      "project.image" -> "https://endless4s.github.io/transaction/logo-open-graph.png"
    ),
    Compile / paradoxMaterialTheme := {
      val theme = (Compile / paradoxMaterialTheme).value
      val repository =
        (ThisBuild / sonatypeProjectHosting).value.get.scmInfo.browseUrl.toURI
      theme
        .withRepository(repository)
        .withFont("Overpass", "Overpass Mono")
        .withLogo("logo-symbol-only.svg")
        .withFavicon("favicon.png")
        .withSocial(repository)
        .withColor("blue grey", "red")
        .withGoogleAnalytics("G-KKHFXG4VB4")
    }
  )

lazy val root = project
  .in(file("."))
  .dependsOn(example)
  .aggregate(lib, pekkoRuntime, akkaRuntime, example, documentation)
  .settings(commonSettings *)
  .settings(crossScalaVersions := Nil)
  .settings(publish / skip := true)
  .settings(Compile / run / fork := true)
  .settings(name := "endless-transaction-root")

Compile / mainClass := Some("endless.transaction.example.app.pekko.Main")
