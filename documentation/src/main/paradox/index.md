<div align="center"><img src="logo.svg" width="700"/></div>

`endless-transaction` is a Scala library that provides a flexible functional abstraction to orchestrate distributed transactions based on cats-effect and the [endless](https://endless4s.github.io/) library. It is a simple tool to simplify the process of coordinating transactions using a @ref:[two-phase commit protocol](2pc.md).

This is not "old-school" 2PC however: the library abstractions can be used to describe any form of two-phase consensus, allowing for both short and long-lived transactions (aka sagas).

<div align="right">
<a href="https://typelevel.org/projects/affiliate/"><img src="https://typelevel.org/img/assets/typelevel-brand.svg" height="40px" align="right" alt="Typelevel Affiliate Project" /></a>
<img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly"/>
</div>

@@@ index
* [Getting Started](getting-started.md)
* [In a nutshell](nutshell.md)
* [Two-phase commit](2pc.md)
* [Abstractions](abstractions.md)
* [Example app](example.md)
* [Reference](reference.md)
@@@
