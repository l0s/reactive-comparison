# Comparing Reactive vs. Blocking Paradigms

This project compares the ergonomics and performance of implementing the
same REST API using the traditional blocking paradigm, the async
paradigm, and the reactive paradigm.

## The Stack(s)

The reactive implementation uses Spring WebFlux with Netty. The blocking
implementation uses Spring WebMVC with Netty. The async implementation uses
Spring WebMVC with Tomcat.

### In-Common

In order to make the comparison as fair as possible, I used the following
common components:

* Spring
  * Boot
  * Web MVC
  * HATEOAS
* JDBC
  * HikariCP
  * PostgreSQL Driver
* PostgreSQL
* Resilience4J

In addition, both the blocking and reactive implementations use Netty.

## Application Design Considerations

This is not meant to be production code. It is meant to simulate
production runtime conditions.

### Relational Database

I chose to use a datastore that supports transactions and ensures data
consistency. This simulates scenarios in which the application is forced
to wait for consistency checks to be completed.

In practice, there is no real contention at the database level because only
one instance of the application is run at any given time. There _is_
in-app contention; in-app locks ensure that shared objects are only
modified by a single thread at a time.

### JDBC

I chose to use JDBC directly rather than an ORM or a SQL framework.
Because many of these frameworks buffer all the results of a SQL query
into memory, I wanted the ability to populate data streams as data
became available. I also wanted to ensure the data access layer was not
introducing any unnecessary overhead.

## For Future Followup

* Test load balancing
* Incorporate reactive JDBC

## References

* https://blog.softwaremill.com/how-not-to-use-reactive-streams-in-java-9-7a39ea9c2cb3
* https://ordina-jworks.github.io/reactive/2016/12/12/Reactive-Programming-Spring-Reactor.html
