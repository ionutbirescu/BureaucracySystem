package bureaucracy.faulty1;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runner demonstrating the concurrency bugs in {@link FaultyOffice}:
 * 1. Unsynchronized ArrayList queue under concurrent additions.
 * 2. Check-then-act race between dual dispatchers causing IndexOutOfBoundsException / lost requests.
 * 3. Lost updates on unsynchronized servedCount under contention.
 * 4. Deviations from FIFO ordering, duplicate completions, and skipped customers.
 */
public final class Faulty1Demo {

    private static final int TOTAL_CUSTOMERS = 100;
    private static final int PRODUCER_THREADS = 10;
    private static final int CUSTOMERS_PER_PRODUCER = TOTAL_CUSTOMERS / PRODUCER_THREADS;
    private static final long PROCESSING_TIME_MS = 10L;
    private static final long AWAIT_TIMEOUT_MS = 4_000L;

    private Faulty1Demo() {}

    static void main(String[] args) throws InterruptedException {
        SimulationLogger logger = new SimulationLogger();
        logger.info("Faulty1Demo", "Starting Faulty 1 Concurrency Demo (P3 runner)...");

        // 1. Capture uncaught dispatcher exceptions (e.g., IndexOutOfBoundsException in dispatchLoop)
        List<String> uncaughtExceptions = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String record = String.format("[%s] %s: %s",
                    thread.getName(),
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage() != null ? throwable.getMessage() : "no message");
            uncaughtExceptions.add(record);
            logger.error("Faulty1Demo", "Dispatcher crashed: " + record);
        });

        // 2. Setup FaultyOffice with a fast-processing document to amplify race contention
        Document idCard = new Document("ID_CARD", "Identity Card", List.of(), "POP", PROCESSING_TIME_MS);
        FaultyOffice office = new FaultyOffice("POP", "Population Office (Faulty)",
                Map.of(idCard.getId(), idCard), logger);
        office.start();

        // 3. Concurrently enqueue customers across multiple producer threads
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_THREADS);

        List<RequestTracker> submittedRequests = new CopyOnWriteArrayList<>();
        List<Integer> completionOrder = new CopyOnWriteArrayList<>();
        List<String> enqueueExceptions = new CopyOnWriteArrayList<>();
        AtomicInteger enqueuedSuccessfully = new AtomicInteger();

        for (int producerId = 0; producerId < PRODUCER_THREADS; producerId++) {
            final int startId = producerId * CUSTOMERS_PER_PRODUCER + 1;
            final int endId = startId + CUSTOMERS_PER_PRODUCER - 1;
            final String threadName = "customer-producer-" + producerId;

            Thread.ofPlatform().name(threadName).start(() -> {
                try {
                    startGate.await();
                    for (int id = startId; id <= endId; id++) {
                        Customer customer = new Customer(id, idCard.getId());
                        try {
                            CompletableFuture<Boolean> future = office.enqueue(customer, idCard.getId());
                            enqueuedSuccessfully.incrementAndGet();
                            final int customerId = id;
                            future.whenComplete((success, ex) -> {
                                if (Boolean.TRUE.equals(success)) {
                                    completionOrder.add(customerId);
                                }
                            });
                            submittedRequests.add(new RequestTracker(customerId, future));
                        } catch (Exception ex) {
                            enqueueExceptions.add("Customer#" + id + " enqueue threw "
                                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            });
        }

        // Release all producers simultaneously to maximize queue contention
        startGate.countDown();
        producersDone.await();

        // 4. Await completions with a bounded deadline to expose crashed dispatchers & lost futures
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        List<Integer> lostFutureCustomerIds = new ArrayList<>();
        int successfulCompletions = 0;
        int failedCompletions = 0;

        for (RequestTracker tracker : submittedRequests) {
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            try {
                Boolean result = tracker.future().get(remaining, TimeUnit.MILLISECONDS);
                if (Boolean.TRUE.equals(result)) {
                    successfulCompletions++;
                } else {
                    failedCompletions++;
                }
            } catch (TimeoutException e) {
                lostFutureCustomerIds.add(tracker.customerId());
            } catch (ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                uncaughtExceptions.add("Future error on Customer#" + tracker.customerId() + ": " + e.getMessage());
            }
        }

        // 5. Gather metrics and consistency results
        int officeServedCount = office.getServedCount();

        // FIFO order violation checks
        int fifoViolations = 0;
        List<String> fifoViolationSamples = new ArrayList<>();
        for (int i = 1; i < completionOrder.size(); i++) {
            int prev = completionOrder.get(i - 1);
            int curr = completionOrder.get(i);
            if (curr < prev) {
                fifoViolations++;
                if (fifoViolationSamples.size() < 5) {
                    fifoViolationSamples.add(String.format("Customer #%d served before Customer #%d", curr, prev));
                }
            }
        }

        // Duplicate services and lost customers
        Map<Integer, Integer> completionCounts = new HashMap<>();
        for (int id : completionOrder) {
            completionCounts.merge(id, 1, Integer::sum);
        }

        List<Integer> duplicateCustomerIds = new ArrayList<>();
        completionCounts.forEach((id, count) -> {
            if (count > 1) duplicateCustomerIds.add(id);
        });

        List<Integer> unservedCustomerIds = new ArrayList<>();
        for (int id = 1; id <= TOTAL_CUSTOMERS; id++) {
            if (!completionCounts.containsKey(id)) {
                unservedCustomerIds.add(id);
            }
        }

        // 6. Print the comparison report
        printComparisonReport(logger, TOTAL_CUSTOMERS, enqueuedSuccessfully.get(),
                successfulCompletions, failedCompletions, officeServedCount,
                fifoViolations, fifoViolationSamples, duplicateCustomerIds,
                unservedCustomerIds, lostFutureCustomerIds,
                enqueueExceptions, uncaughtExceptions);

        // 7. Clean shutdown
        office.shutdown();
        Thread.setDefaultUncaughtExceptionHandler(originalHandler);
        logger.shutdown();
    }

    private static void printComparisonReport(SimulationLogger logger,
                                              int totalArrived,
                                              int enqueuedCount,
                                              int completedCount,
                                              int failedCount,
                                              int officeServedCount,
                                              int fifoViolations,
                                              List<String> violationSamples,
                                              List<Integer> duplicateCustomerIds,
                                              List<Integer> unservedCustomerIds,
                                              List<Integer> lostFutures,
                                              List<String> enqueueExceptions,
                                              List<String> uncaughtExceptions) {
        logger.info("Report", "==========================================================");
        logger.info("Report", "              FAULTY 1 DEMO EXECUTION REPORT              ");
        logger.info("Report", "==========================================================");
        logger.info("Report", String.format("Total Customers Arrived (expected):  %d", totalArrived));
        logger.info("Report", String.format("Successfully Enqueued into Queue:    %d", enqueuedCount));
        logger.info("Report", String.format("Futures Completed with Success:      %d", completedCount));
        logger.info("Report", String.format("Futures Completed with Failure:      %d", failedCount));
        logger.info("Report", String.format("Office Counter (office.servedCount): %d", officeServedCount));
        logger.info("Report", "----------------------------------------------------------");
        logger.info("Report", "BUG 3 ANALYSIS (Lost Updates Discrepancy):");
        logger.info("Report", String.format("  Discrepancy (Completed vs Counter): %d", (completedCount - officeServedCount)));
        logger.info("Report", String.format("  Discrepancy (Arrived vs Counter):   %d", (totalArrived - officeServedCount)));
        if (completedCount != officeServedCount || totalArrived != officeServedCount) {
            logger.warn("Report", "  -> CONFIRMED: Plain unsynchronized int servedCount dropped increments under contention!");
        }

        logger.info("Report", "----------------------------------------------------------");
        logger.info("Report", "BUG 1 & 2 ANALYSIS (FIFO Deviations & Duplicates/Loss):");
        logger.info("Report", String.format("  FIFO Order Deviations (Inversions): %d", fifoViolations));
        for (String sample : violationSamples) {
            logger.warn("Report", "    * Violation sample: " + sample);
        }
        logger.info("Report", String.format("  Customers Served Multiple Times:    %d %s",
                duplicateCustomerIds.size(), duplicateCustomerIds.isEmpty() ? "[]" : duplicateCustomerIds));
        logger.info("Report", String.format("  Customers Skipped / Never Served:   %d %s",
                unservedCustomerIds.size(), unservedCustomerIds.size() <= 10 ? unservedCustomerIds : unservedCustomerIds.subList(0, 10) + "..."));

        logger.info("Report", "----------------------------------------------------------");
        logger.info("Report", "CONCURRENCY EXCEPTIONS & CRASHES CAUGHT:");
        logger.info("Report", String.format("  Lost Futures (Timed out waiting):   %d %s",
                lostFutures.size(), lostFutures.size() <= 10 ? lostFutures : lostFutures.subList(0, 10) + "..."));
        logger.info("Report", String.format("  Enqueue Exceptions:                 %d", enqueueExceptions.size()));
        for (String err : enqueueExceptions) {
            logger.error("Report", "    * " + err);
        }
        logger.info("Report", String.format("  Uncaught Dispatcher Exceptions:     %d", uncaughtExceptions.size()));
        for (String exc : uncaughtExceptions) {
            logger.error("Report", "    * " + exc);
        }

        logger.info("Report", "==========================================================");
        if (officeServedCount != totalArrived || fifoViolations > 0 || !lostFutures.isEmpty() || !uncaughtExceptions.isEmpty()) {
            logger.info("Report", "STATUS: All intentional Faulty 1 concurrency defects successfully demonstrated.");
        } else {
            logger.info("Report", "STATUS: No anomalies detected on this run (increase contention/customer count).");
        }
        logger.info("Report", "==========================================================");
    }

    private record RequestTracker(int customerId, CompletableFuture<Boolean> future) {}
}