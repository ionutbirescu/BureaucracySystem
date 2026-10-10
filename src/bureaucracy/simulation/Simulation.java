package bureaucracy.simulation;

import bureaucracy.config.DocumentPlanner;
import bureaucracy.config.SimulationConfig;
import bureaucracy.customer.CustomerTask;
import bureaucracy.customer.RetryPolicy;
import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;
import bureaucracy.office.BreakScheduler;
import bureaucracy.office.Counter;
import bureaucracy.office.Office;
import bureaucracy.office.OfficeAPI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The correct solution, wired together: offices + counters (P1), customer threads (P2),
 * config, planner and coffee breaks (P3), started and stopped in a fixed order (P4).
 *
 * Startup:  offices -> break scheduler -> customer threads, all held at a start gate
 *           (CountDownLatch) so they really arrive at the same time.
 * Running:  the main thread waits on a "finished" CountDownLatch, with a timeout.
 * Shutdown: customers (ExecutorService) -> break scheduler -> offices; the caller
 *           then prints the report and shuts the logger down last.
 */
public final class Simulation {

    public record Options(int customers, List<String> targets, RetryPolicy retryPolicy,
                          boolean coffeeBreaks, long timeoutMs) {

        // Patient customers: a timed-out ticket may still be served later, which would
        // be reported as "served twice". The timeout is only a safety net.
        public static final RetryPolicy PATIENT = new RetryPolicy(3, 100, 1_000, 60_000);

        public static Options defaults(int customers) {
            return new Options(customers, List.of(), PATIENT, true, 120_000);
        }

        public Options withoutCoffeeBreaks() {
            return new Options(customers, targets, retryPolicy, false, timeoutMs);
        }

        public Options withTargets(List<String> newTargets) {
            return new Options(customers, newTargets, retryPolicy, coffeeBreaks, timeoutMs);
        }

        public Options withTimeoutMs(long newTimeoutMs) {
            return new Options(customers, targets, retryPolicy, coffeeBreaks, newTimeoutMs);
        }
    }

    private static final String SRC = "Simulation";

    private final SimulationConfig config;
    private final Options options;
    private final SimulationLogger logger;
    private final DocumentPlanner planner;

    private final List<Office> offices = new ArrayList<>();
    private final SimulationMetrics metrics = new SimulationMetrics();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final CountDownLatch terminated = new CountDownLatch(1);
    private volatile ExecutorService customerPool;

    public Simulation(SimulationConfig config, Options options, SimulationLogger logger) {
        if (options.customers() <= 0) {
            throw new IllegalArgumentException("customers must be > 0");
        }
        this.config = config;
        this.options = options;
        this.logger = logger;
        this.planner = new DocumentPlanner(config.documents());
        planner.validateAcyclic();
    }

    /** Runs the whole simulation once and blocks until it is over and everything is shut down. */
    public SimulationReport run() throws InterruptedException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("a Simulation can only be run once");
        }
        long start = System.currentTimeMillis();
        BreakScheduler breaks = null;
        boolean timedOut = false;
        try {
            Map<String, OfficeAPI> officeById = buildOffices();
            offices.forEach(Office::start);

            if (options.coffeeBreaks()) {
                List<Counter> allCounters = offices.stream().flatMap(o -> o.getCounters().stream()).toList();
                breaks = new BreakScheduler(allCounters, logger);
                breaks.start();
            }

            List<String> targets = options.targets().isEmpty() ? defaultTargets() : options.targets();
            int n = options.customers();
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(n);

            AtomicInteger threadNo = new AtomicInteger();
            customerPool = Executors.newFixedThreadPool(n,
                    r -> new Thread(r, "customer-" + threadNo.incrementAndGet()));

            for (int i = 1; i <= n; i++) {
                String target = targets.get((i - 1) % targets.size());
                Customer customer = new Customer(i, target);
                List<Document> plan = planner.createPlan(target);
                CustomerTask task = new CustomerTask(customer, plan, officeById,
                        options.retryPolicy(), logger, customer.toString());
                customerPool.execute(() -> {
                    try {
                        startGate.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        metrics.recordCustomer(task);
                        finished.countDown();
                    }
                });
            }

            logger.info(SRC, n + " customer(s) ready, targets " + targets + " - opening the doors");
            startGate.countDown();

            if (!finished.await(options.timeoutMs(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                logger.error(SRC, "timeout after " + options.timeoutMs() + " ms, "
                        + finished.getCount() + " customer(s) still inside - interrupting them");
            }
        } finally {
            shutdown(breaks);
        }
        return metrics.report(options.customers(), offices, System.currentTimeMillis() - start, timedOut);
    }

    /** Asks a running simulation to stop early (e.g. Ctrl+C) and waits briefly for the shutdown. */
    public void stop(long waitMs) throws InterruptedException {
        ExecutorService pool = customerPool;
        if (pool != null) {
            pool.shutdownNow();
        }
        if (!terminated.await(waitMs, TimeUnit.MILLISECONDS)) {
            logger.warn(SRC, "simulation still shutting down after " + waitMs + " ms");
        }
    }

    private void shutdown(BreakScheduler breaks) throws InterruptedException {
        try {
            ExecutorService pool = customerPool;
            if (pool != null) {
                pool.shutdown();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn(SRC, "customers did not leave in time, interrupting");
                    pool.shutdownNow();
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error(SRC, "some customer threads ignore interruption, leaving them behind");
                    }
                }
            }
            if (breaks != null) {
                breaks.stop();
            }
            offices.forEach(Office::shutdown);
            logger.info(SRC, "all offices closed");
        } finally {
            terminated.countDown();
        }
    }

    private Map<String, OfficeAPI> buildOffices() {
        Map<String, Map<String, Document>> documentsByOffice = new HashMap<>();
        for (Document document : config.documents().values()) {
            if (!config.offices().containsKey(document.getOfficeId())) {
                throw new IllegalArgumentException("Document " + document.getId()
                        + " is issued by unknown office " + document.getOfficeId());
            }
            documentsByOffice.computeIfAbsent(document.getOfficeId(), id -> new HashMap<>())
                    .put(document.getId(), document);
        }

        Map<String, OfficeAPI> officeById = new LinkedHashMap<>();
        for (SimulationConfig.OfficeSpec spec : config.offices().values()) {
            if (spec.counterCount() <= 0) {
                throw new IllegalArgumentException("Office " + spec.id() + " needs at least one counter");
            }
            List<Counter> counters = new ArrayList<>();
            for (int c = 1; c <= spec.counterCount(); c++) {
                counters.add(new Counter(spec.id() + "-" + c, spec.id(), logger));
            }
            Office office = new Office(spec.id(), spec.name(), counters,
                    documentsByOffice.getOrDefault(spec.id(), Map.of()), logger);
            offices.add(office);
            officeById.put(office.getId(), office);
        }
        return officeById;
    }

    /** "No documents may be obtained directly": by default customers ask for documents that have prerequisites. */
    private List<String> defaultTargets() {
        List<String> targets = config.documents().values().stream()
                .filter(d -> !d.getPrerequisites().isEmpty())
                .map(Document::getId)
                .toList();
        return targets.isEmpty() ? List.copyOf(config.documents().keySet()) : targets;
    }

    public List<Office> getOffices() {
        return List.copyOf(offices);
    }
}
