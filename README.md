# Comparing Reactive vs. Blocking Paradigms

This project compares the ergonomics and performance of implementing the
same REST API using the traditional blocking paradigm vs. the reactive
paradigm.

## The Stack(s)

The reactive implementation uses Spring WebFlux and the blocking
implementation uses Spring Web MVC.

### In-Common

In order to make the comparison as fair as possible, I used the following
common components:

* Netty
* Spring Boot
  * Web MVC, HATEOAS
* JDBC
  * HikariCP, PostgreSQL Driver
* PostgreSQL

## For Future Followup

* Evaluate throughput
* Compare async servlet
* Incorporate reactive JDBC

## References

* https://blog.softwaremill.com/how-not-to-use-reactive-streams-in-java-9-7a39ea9c2cb3
* https://ordina-jworks.github.io/reactive/2016/12/12/Reactive-Programming-Spring-Reactor.html
