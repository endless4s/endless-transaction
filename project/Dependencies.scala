import sbt.*

object Dependencies {
  val endlessVersion = "0.29.0"
  val `endless-core` = "io.github.endless4s" %% "endless-core" % endlessVersion
  val `endless-protobuf-helpers` =
    "io.github.endless4s" %% "endless-protobuf-helpers" % endlessVersion
  val `endless-runtime-pekko` = "io.github.endless4s" %% "endless-runtime-pekko" % endlessVersion
  val `endless-runtime-akka` = "io.github.endless4s" %% "endless-runtime-akka" % endlessVersion

  val `scalapb-runtime` =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"

  val http4sVersion = "0.23.24"
  val http4s = Seq(
    "org.http4s" %% "http4s-ember-server" % http4sVersion,
    "org.http4s" %% "http4s-ember-client" % http4sVersion % Test,
    "org.http4s" %% "http4s-dsl" % http4sVersion,
    "org.http4s" %% "http4s-circe" % http4sVersion
  )

  lazy val pekkoVersion = "1.0.0"
  lazy val `pekko-actor-typed` = "org.apache.pekko" %% "pekko-actor-typed"
  lazy val `pekko-persistence-typed` = "org.apache.pekko" %% "pekko-persistence-typed"
  lazy val `pekko-cluster-typed` = "org.apache.pekko" %% "pekko-cluster-typed"
  lazy val `pekko-cluster-sharding-typed` = "org.apache.pekko" %% "pekko-cluster-sharding-typed"

  lazy val `pekko-actor-testkit-typed` = "org.apache.pekko" %% "pekko-actor-testkit-typed"
  lazy val `pekko-persistence-testkit` = "org.apache.pekko" %% "pekko-persistence-testkit"

  lazy val pekko =
    Seq(
      `pekko-actor-typed`,
      `pekko-cluster-typed`,
      `pekko-cluster-sharding-typed`,
      `pekko-persistence-typed`
    ).map(
      _ % pekkoVersion
    )
  lazy val pekkoProvided = pekko.map(_ % Provided)
  lazy val pekkoTest =
    Seq(`pekko-actor-testkit-typed`, `pekko-persistence-testkit`).map(_ % pekkoVersion)

  lazy val akkaVersion = "2.6.20"
  lazy val `akka-actor-typed` = "com.typesafe.akka" %% "akka-actor-typed"
  lazy val `akka-persistence-typed` = "com.typesafe.akka" %% "akka-persistence-typed"
  lazy val `akka-cluster-typed` = "com.typesafe.akka" %% "akka-cluster-typed"
  lazy val `akka-cluster-sharding-typed` = "com.typesafe.akka" %% "akka-cluster-sharding-typed"

  lazy val `akka-actor-testkit-typed` = "com.typesafe.akka" %% "akka-actor-testkit-typed"
  lazy val `akka-persistence-testkit` = "com.typesafe.akka" %% "akka-persistence-testkit"

  lazy val akka = Seq(
    `akka-actor-typed`,
    `akka-cluster-typed`,
    `akka-cluster-sharding-typed`,
    `akka-persistence-typed`
  ).map(_ % akkaVersion)
  lazy val akkaProvided = akka.map(_ % Provided)
  lazy val akkaTest =
    Seq(`akka-actor-testkit-typed`, `akka-persistence-testkit`).map(_ % akkaVersion)

  lazy val postgresql = "org.postgresql" % "postgresql" % "42.7.1"
  lazy val `pekko-persistence-jdbc` =
    ("org.apache.pekko" %% "pekko-persistence-jdbc" % "1.0.0").excludeAll("org.apache.pekko")
  lazy val slickVersion = "3.3.3"
  lazy val slick = Seq(
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion
  )

  lazy val `logback-classic` = "ch.qos.logback" % "logback-classic" % "1.4.14"

  lazy val log4catsVersion = "2.6.0"
  lazy val `log4cats-core` = Seq("org.typelevel" %% "log4cats-core" % log4catsVersion)
  lazy val `log4cats-slf4j` = Seq("org.typelevel" %% "log4cats-slf4j" % log4catsVersion)
  lazy val `log4cats-testing` = Seq("org.typelevel" %% "log4cats-testing" % log4catsVersion)

  lazy val munit =
    Seq("org.scalameta" %% "munit", "org.scalameta" %% "munit-scalacheck").map(_ % "0.7.29")

  lazy val scalatest = Seq("org.scalatest" %% "scalatest" % "3.2.15")

  lazy val `munit-cats-effect-3` = Seq("org.typelevel" %% "munit-cats-effect-3" % "1.0.7")

  lazy val catsEffectVersion = "3.5.3"
  lazy val `cats-effect` = "org.typelevel" %% "cats-effect" % catsEffectVersion

  lazy val `cats-effect-testkit` = Seq("org.typelevel" %% "cats-effect-testkit" % catsEffectVersion)

  lazy val `scalacheck-effect-munit` = Seq("org.typelevel" %% "scalacheck-effect-munit" % "1.0.4")

  lazy val `cats-scalacheck` = Seq("io.chrisdavenport" %% "cats-scalacheck" % "0.3.2")
}
