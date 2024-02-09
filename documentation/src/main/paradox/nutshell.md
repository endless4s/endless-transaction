# In a nutshell

While the traditional 2PC protocol is synchronous and blocking, `endless-transaction` is designed to be asynchronous and non-blocking, allowing for long-running transactions and the ability to handle failures and retries. In order words, you can use it as a tool to achieve various degrees of consistency in distributed systems. Diverse forms of two-phase consensus appear frequently in service-oriented systems, especially when involving complex business workflows and heterogeneous data stores. Its usefulness goes beyond the specific area of strongly consistent, atomic transactions such as [XA](https://en.wikipedia.org/wiki/X/Open_XA).

