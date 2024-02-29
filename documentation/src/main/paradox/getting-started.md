# Getting Started

Add the following dependency to your `build.sbt` file:

@@@vars
```scala
libraryDependencies += "io.github.endless4s" %% "endless-transaction" % "$project.version$"
```
@@@

This will pull in the module containing the abstractions. You should add this dependency to the project that contains your business domain logic (typically "domain").

The Pekko runtime is available in `endless-transaction-pekko` and provides `PekkoTransactor`, an implementation of `Transactor` which is the entry-point to create a transaction coordinator  (for Akka, use `endless-transaction-akka`). 

@@@ warning { title="Compatibility" }
Since Pekko/Akka [do not allow mixed versions](https://doc.akka.io/docs/akka/current/common/binary-compatibility-rules.html#mixed-versioning-is-not-allowed) in a project, dependencies of `endless-transaction-pekko` (and `endless-transaction-akka` respectively) are marked a `Provided`. This means that your application `libraryDependencies` needs to directly include Pekko or Akka as a direct dependency. The minimal supported Pekko version is $pekko.min.version$, and Akka version is $akka.min.version$.  
@@@