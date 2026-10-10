package bureaucracy.faulty2;

import bureaucracy.customer.CustomerTask;
import bureaucracy.customer.RetryPolicy;
import bureaucracy.diagnostics.DeadlockDetector;
import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;
import bureaucracy.office.OfficeAPI;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faulty 2 demo: two customers in opposite order + deadlock detector.
 *
 *   Customer#1 wants PROOF_OF_ADDRESS: holds TOWN-1, then needs a stamp from TAX.
 *   Customer#2 wants TAX_CERT:         holds TAX-1,  then needs a stamp from TOWN.
 *
 * Each one keeps its counter while waiting for the other office (hold-and-wait, no preemption,
 * circular wait, one counter per office = mutual exclusion): all four Coffman conditions.
 *
 * Run with "--fixed" to see the fix: the stamp is fetched first, as a normal prerequisite,
 * so no customer holds a counter while queuing somewhere else and both finish.
 *
 * Exit code 0 when the demo showed what it should (deadlock in faulty mode, none in fixed mode).
 */
public final class DeadlockDemo {

    public enum Mode { FAULTY, FIXED }

    public record Result(Mode mode, DeadlockDetector.Report report, Map<String, CustomerTask.Outcome> outcomes) {
        public boolean asExpected() {
            return mode == Mode.FAULTY ? report.deadlocked() : !report.deadlocked();
        }
    }

    private static final String SRC = "DeadlockDemo";
    private static final long SETTLE_MS = 800;   // let both customers reach their first counter
    private static final long WINDOW_MS = 2_000;  // no progress for this long + a cycle = deadlock
    private static final long SAMPLE_MS = 100;

    private DeadlockDemo() {}

    public static void main(String[] args) throws InterruptedException {
        Mode mode = args.length > 0 && args[0].equals("--fixed") ? Mode.FIXED : Mode.FAULTY;
        SimulationLogger logger = new SimulationLogger();
        Result result = run(mode, logger);
        logger.shutdown();
        // The deadlocked threads are daemon/virtual threads, so the JVM can still exit
        System.exit(result.asExpected() ? 0 : 1);
    }

    public static Result run(Mode mode, SimulationLogger logger) throws InterruptedException {
        logger.info(SRC, "=== Faulty 2 (deadlock) demo, mode " + mode + " ===");

        Document townStamp = new Document("TOWN_STAMP", "Town hall stamp", List.of(), "TOWN", 100);
        Document taxStamp  = new Document("TAX_STAMP", "Tax office stamp", List.of(), "TAX", 100);
        // In fixed mode the stamp is an ordinary prerequisite: fetched before reaching the counter
        Document proofOfAddress = new Document("PROOF_OF_ADDRESS", "Proof of address",
                mode == Mode.FIXED ? List.of(taxStamp.getId()) : List.of(), "TOWN", 300);
        Document taxCert = new Document("TAX_CERT", "Tax certificate",
                mode == Mode.FIXED ? List.of(townStamp.getId()) : List.of(), "TAX", 300);

        // One counter per office: a single held counter blocks the whole office
        DeadlockCounter townCounter = new DeadlockCounter("TOWN-1", "TOWN", logger);
        DeadlockCounter taxCounter = new DeadlockCounter("TAX-1", "TAX", logger);
        DeadlockOffice town = new DeadlockOffice("TOWN", "Town hall", List.of(townCounter),
                Map.of(proofOfAddress.getId(), proofOfAddress, townStamp.getId(), townStamp), logger);
        DeadlockOffice tax = new DeadlockOffice("TAX", "Tax office", List.of(taxCounter),
                Map.of(taxCert.getId(), taxCert, taxStamp.getId(), taxStamp), logger);

        // Which office each office sends its customers to for a stamp (used by the wait-for graph)
        Map<DeadlockOffice, DeadlockOffice> stampFrom = new LinkedHashMap<>();
        if (mode == Mode.FAULTY) {
            town.requireStamp(proofOfAddress.getId(), tax, taxStamp.getId());
            tax.requireStamp(taxCert.getId(), town, townStamp.getId());
            stampFrom.put(town, tax);
            stampFrom.put(tax, town);
        }

        List<DeadlockOffice> allOffices = List.of(town, tax);
        Map<String, OfficeAPI> offices = Map.of(town.getId(), town, tax.getId(), tax);
        allOffices.forEach(DeadlockOffice::start);

        // Opposite order: customer 1 goes TOWN -> TAX, customer 2 goes TAX -> TOWN
        RetryPolicy patient = new RetryPolicy(1, 0, 0, 60_000);
        Customer first = new Customer(1, proofOfAddress.getId());
        Customer second = new Customer(2, taxCert.getId());
        List<Document> firstPlan = mode == Mode.FIXED ? List.of(taxStamp, proofOfAddress) : List.of(proofOfAddress);
        List<Document> secondPlan = mode == Mode.FIXED ? List.of(townStamp, taxCert) : List.of(taxCert);
        List<CustomerTask> tasks = List.of(
                new CustomerTask(first, firstPlan, offices, patient, logger, first.toString()),
                new CustomerTask(second, secondPlan, offices, patient, logger, second.toString()));

        List<Thread> threads = new ArrayList<>();
        for (CustomerTask task : tasks) {
            threads.add(Thread.ofPlatform().daemon(true).name("customer-" + task.getCustomer().getId()).start(task));
        }

        Thread.sleep(SETTLE_MS);

        DeadlockDetector detector = new DeadlockDetector(
                () -> waitForGraph(stampFrom),
                () -> allOffices.stream().flatMap(o -> o.getCounters().stream())
                        .mapToLong(DeadlockCounter::getCustomersServed).sum(),
                List.of("customer-", "office-", "serve-", "Customer#"));
        DeadlockDetector.Report report = detector.observe(WINDOW_MS, SAMPLE_MS);

        printReport(logger, report);

        // Clean up what can be cleaned up; the two serve threads stay stuck forever (that is the bug)
        for (Thread thread : threads) {
            thread.interrupt();
            thread.join(1_000);
        }
        allOffices.forEach(DeadlockOffice::shutdown);

        Map<String, CustomerTask.Outcome> outcomes = new LinkedHashMap<>();
        for (CustomerTask task : tasks) {
            outcomes.put(task.getCustomer().toString(), task.getOutcome());
            logger.info(SRC, task.getCustomer() + " -> " + task.getOutcome());
        }
        Result result = new Result(mode, report, outcomes);
        logger.info(SRC, "demo " + (result.asExpected() ? "behaved as expected" : "did NOT behave as expected"));
        return result;
    }

    /**
     * Customer H holds counter C of office O, and O wants a stamp from office S.
     * If every counter of S is held, H waits for each of their holders: edge H -> holder.
     */
    static DeadlockDetector.WaitForGraph waitForGraph(Map<DeadlockOffice, DeadlockOffice> stampFrom) {
        DeadlockDetector.WaitForGraph graph = new DeadlockDetector.WaitForGraph();
        stampFrom.forEach((office, stampOffice) -> {
            boolean stampOfficeFull = stampOffice.getCounters().stream().allMatch(c -> c.getHeldBy() != null);
            if (!stampOfficeFull) {
                return;
            }
            for (DeadlockCounter held : office.getCounters()) {
                Customer holder = held.getHeldBy();
                if (holder == null) continue;
                for (DeadlockCounter blocking : stampOffice.getCounters()) {
                    Customer other = blocking.getHeldBy();
                    if (other != null) {
                        graph.addEdge(holder + "@" + held.getId(), other + "@" + blocking.getId());
                    }
                }
            }
        });
        return graph;
    }

    private static void printReport(SimulationLogger logger, DeadlockDetector.Report report) {
        logger.info(SRC, "---------------- detector report ----------------");
        logger.info(SRC, "progress (counters served) : " + report.progressAtStart() + " -> " + report.progressAtEnd()
                + " over " + WINDOW_MS + " ms");
        logger.info(SRC, "wait-for cycle             : "
                + (report.waitForCycle().isEmpty() ? "none" : String.join(" -> ", report.waitForCycle())));
        logger.info(SRC, "ThreadMXBean deadlock      : "
                + (report.jvmSawIt() ? report.jvmDeadlockedThreads()
                : "none (Semaphore/CompletableFuture have no owner, the JVM cannot see this cycle)"));
        if (report.deadlocked()) {
            logger.error(SRC, "DEADLOCK DETECTED: each customer holds a counter and waits for the other's office");
            logger.info(SRC, "thread dump of the stuck threads (jstack-like):\n" + report.threadDump());
        } else {
            logger.info(SRC, "NO DEADLOCK: no circular wait between customers");
        }
        logger.info(SRC, "-------------------------------------------------");
    }
}
