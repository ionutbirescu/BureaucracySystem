# BureaucracySystem
Project 1 for CEBP - **Bureaucracy Manager** (problem statement: [ProblemReq.md](ProblemReq.md)).

Customers (threads) must obtain a target document. No document can be obtained directly:
each one needs intermediate documents issued by other offices. Offices have queues and one or
more counters, and any counter can close for a coffee break at any time.

The project has **three versions**:

| Version   | What happens                                                         | Where                                   |
|-----------|----------------------------------------------------------------------|-----------------------------------------|
| Correct   | Every customer gets every document; nobody is served twice or lost   | `bureaucracy.simulation.SimulationMain` |
| Faulty 1  | Data race: unsynchronized queue, customers lost or served twice      | `bureaucracy.faulty1.Faulty1Demo`       |
| Faulty 2  | Deadlock: a customer holds counter B while waiting for office A      | `bureaucracy.faulty2.DeadlockDemo`      |

## Requirements
- JDK 25 or newer (the project JDK is openjdk-27). The `main` methods are package-private
  (`static void main`, JDK 25+), as Qodana recommends for this language level.
- Maven (only for compiling and running the tests). In IntelliJ: right-click `pom.xml` -> *Add as Maven Project*.

## Build, run, test

```bash
mvn compile                                    # compile to target/classes
mvn test                                       # run the JUnit tests (test/)

# Correct version: [configFile] [customers], exit code 0 = consistent run
java -cp target/classes bureaucracy.simulation.SimulationMain config/simulation.properties 12

# Faulty 2: deadlock + detector (exit code 0 = deadlock detected as expected)
java -cp target/classes bureaucracy.faulty2.DeadlockDemo
java -cp target/classes bureaucracy.faulty2.DeadlockDemo --fixed   # same scenario with the fix

# Harness: runs all versions, each in its own JVM, and prints a summary table
java -cp target/classes bureaucracy.harness.Harness [customers]
```

Harness output (logs of every run in `out/harness/<version>.log`):

```
VERSION        EXPECTED                               RESULT         TIME  LOG
correct        all customers served, no duplicates    OK           4424 ms  out/harness/correct.log
faulty1        data race exposed                      OK           4193 ms  out/harness/faulty1.log
faulty2        deadlock detected                      OK           3151 ms  out/harness/faulty2.log
faulty2-fixed  no deadlock after the fix              OK           3032 ms  out/harness/faulty2-fixed.log
```

A version whose main class is not on the classpath yet is reported as `SKIPPED`.

## Configuration
`config/simulation.properties`:

```properties
# office.<ID> = <Name> : <Counters>
office.TOWN = Town Hall : 3
# doc.<ID> = <Name> : <OfficeID> : <ProcessingTimeMs> : <Prerequisites>
doc.BUILDING_PERMIT = Building permit : TOWN : 500 : PROOF_OF_ADDRESS, TAX_CERT, BIRTH_CERT
```

The config is validated before anything starts: missing prerequisites, dependency cycles,
documents issued by unknown offices and offices with no counters are rejected. By default
customers ask, round-robin, for the documents that have prerequisites.

## Project layout

| Package                    | Content                                                        | Owner |
|----------------------------|----------------------------------------------------------------|-------|
| `bureaucracy.model`        | `Customer`, `Document`                                         | all   |
| `bureaucracy.office`       | `Office`, `Counter`, `BreakScheduler`, `OfficeAPI`             | P1/P3 |
| `bureaucracy.customer`     | `CustomerTask` (customer thread), `RetryPolicy`                | P2    |
| `bureaucracy.config`       | `ConfigLoader`, `DocumentPlanner` (cycle check, topological plan) | P3 |
| `bureaucracy.faulty1`      | `FaultyOffice` + `Faulty1Demo`                                 | P1/P3 |
| `bureaucracy.faulty2`      | `DeadlockOffice`, `DeadlockCounter` + `DeadlockDemo`           | P2/P4 |
| `bureaucracy.simulation`   | `SimulationMain`, `Simulation`, `SimulationMetrics`, `SimulationReport` | P4 |
| `bureaucracy.diagnostics`  | `DeadlockDetector` (ThreadMXBean + wait-for graph + thread dump) | P4  |
| `bureaucracy.harness`      | `Harness` (runs all versions)                                  | P4    |
| `test/`                    | JUnit 5 tests                                                  | P4    |

## Correct version: startup, shutdown, metrics (P4)

`Simulation.run()`:

1. **Startup**: build one `Office` with N `Counter`s per config entry, start the offices and the
   `BreakScheduler`, then submit one `CustomerTask` per customer to an `ExecutorService`
   (platform threads named `customer-N`). Every customer first waits on a **start gate**
   (`CountDownLatch(1)`), so they all arrive at the same time and the queues really fill up.
2. **Running**: the main thread waits on a **finished** `CountDownLatch(N)` with a timeout.
   Each customer counts it down in a `finally`, so a failed or interrupted customer can't hang the main thread.
3. **Clean shutdown**, in reverse order of dependencies: customers
   (`shutdown` + `awaitTermination`, then `shutdownNow` if needed) -> break scheduler -> offices
   -> report -> logger last, so no message is lost. Ctrl+C runs the same shutdown through a JVM shutdown hook.

**Metrics** (`SimulationMetrics`) are recorded by the customer threads themselves with
`LongAdder` / `LongAccumulator` (lock-free, no lost updates): outcomes, min/avg/max time per
customer, re-queues, documents held. Counter statistics are read once all offices have stopped.
`SimulationReport` then checks the invariants of the correct solution:

- every customer finished, and every one with `SUCCESS` actually holds its target document;
- **documents served by all counters == documents held by all customers**, so nobody was
  served twice and nobody was lost (this is exactly what breaks in Faulty 1).

Customers in the correct version use a patient `RetryPolicy` (60 s per attempt). A customer
that times out and leaves the queue may still be served later, and the report would then show
"served twice".

## Faulty 2: deadlock demo and detector (P4)

Two customers in **opposite order**, with one counter per office:

```
Customer#1 holds TOWN-1  --needs a stamp from-->  TAX   (TAX-1 held by Customer#2)
Customer#2 holds TAX-1   --needs a stamp from-->  TOWN  (TOWN-1 held by Customer#1)
```

All four Coffman conditions hold: mutual exclusion (one counter), hold-and-wait (the counter is
kept while queuing elsewhere), no preemption (`future.get()` without a timeout), circular wait.

`DeadlockDetector` combines three signals:

1. `ThreadMXBean.findDeadlockedThreads()` is what `jstack` uses for *"Found one Java-level
   deadlock"*. It only understands `synchronized` monitors and owned locks (`ReentrantLock`).
   **It does not see this deadlock**: a `Semaphore` permit and a `CompletableFuture` have no owner thread.
   The demo prints this, and a test checks it.
2. A **wait-for graph** built from the domain: who holds which counter (`getHeldBy()`) and
   which office each office sends customers to for a stamp. A cycle
   `Customer#1@TOWN-1 -> Customer#2@TAX-1 -> Customer#1@TOWN-1` is a circular wait.
3. **No progress**: no counter finished a customer during the whole observation window.

The demo reports a deadlock when the JVM finds a lock cycle, or when the wait-for cycle is present
on every sample and nothing progressed. It then prints a jstack-like dump of the stuck threads,
including the **virtual** threads (via `HotSpotDiagnosticMXBean.dumpThreads`, same as
`jcmd <pid> Thread.dump_to_file`), which shows both customers parked in
`DeadlockCounter.serve(...)` waiting for the other office.

To inspect it by hand while it is stuck: `jcmd <pid> Thread.dump_to_file -format=text dump.txt`
(`jstack` alone does not list virtual threads).

**Fix** (`--fixed`): the stamp becomes an ordinary prerequisite and is fetched *before* going
to the counter. Nobody holds a counter while queuing at another office, which breaks hold-and-wait,
and both customers finish. The correct `Office` follows the same rule: a customer never keeps a
counter while asking another office for something.

## Tests (`mvn test`)

| Test                     | Checks                                                                    |
|--------------------------|---------------------------------------------------------------------------|
| `SimulationTest`         | correct run with coffee breaks is consistent; served == held; timeout and invalid configs are reported; offices are closed after the run |
| `OfficeCoffeeBreakTest`  | 30 customers, both counters take a break: everyone served exactly once     |
| `ConfigAndPlannerTest`   | config parsing, prerequisites come first in the plan, cycle / missing prerequisite detection |
| `DeadlockDemoTest`       | Faulty 2 deadlocks and is detected, ThreadMXBean misses it, the fixed version finishes; ThreadMXBean does catch a classic `synchronized` deadlock |
