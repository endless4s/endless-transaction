# endless-transaction <img src="https://raw.githubusercontent.com/endless4s/endless-transaction/master/documentation/src/main/paradox/logo-symbol-only.svg" width="200">

[![Release](https://github.com/endless4s/endless-transaction/actions/workflows/release.yml/badge.svg?branch=master)](https://github.com/endless4s/endless-transaction/actions/workflows/release.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.endless4s/endless-transaction_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.endless4s/endless-transaction_2.13)
[![codecov](https://codecov.io/gh/endless4s/endless-transaction/branch/master/graph/badge.svg?token=aH9vOhLxVS)](https://codecov.io/gh/endless4s/endless-transaction)
[![Typelevel Affiliate Project](https://img.shields.io/badge/typelevel-affiliate%20project-FFB4B5.svg)](https://typelevel.org/projects/affiliate/)
<a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>

`endless-transaction` is a Scala library that provides a functional abstraction for distributed transactions based on cats-effect and the endless library. It is designed to simplify the process of coordinating transactions using the two-phase commit protocol. Transactions are implemented with persistent entities using event-sourcing, making the system resilient to failures.

Head to the [documentation](https://endless4s.github.io/transaction/index.html) to learn more.