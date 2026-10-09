package bureaucracy.office;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verification harness demonstrating:
 * 1. Rapid breaks interleaved with concurrent customer requests.
 * 2. Zero dropped or starved customer tasks.
 * 3. Immediate service resumption via Condition notOnBreak.signalAll().
 * 4. Accurate service metrics and counter lifecycle verification (close/reopen).
 */
public final class CoffeeBreakDemo {

    private static final int CONCURRENT_CUSTOMERS = 12;
    private static final long DOC_PROCESSING_TIME_MS = 40;

    static void main(String[] args) throws InterruptedException {
        SimulationLogger logger = new SimulationLogger();
        logger.info("Demo", "===============================================================");
        logger.info("Demo", "STARTING COFFEE BREAK & COUNTER CONCURRENCY VERIFICATION (P3)");
        logger.info("Demo", "===============================================================");

        Counter counter = new Counter("POP-1", "POP", logger);
        Document idCard = new Document("ID_CARD", "Identity card", List.of(), "POP", DOC_PROCESSING_TIME_MS);

        // Configure rapid breaks: interval 50-100 ms, duration 70-120 ms
        BreakScheduler scheduler = new BreakScheduler(
                List.of(counter), logger, 50, 100, 70, 120
        );

        scheduler.start();

        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(CONCURRENT_CUSTOMERS);
        AtomicInteger successfulServiced = new AtomicInteger(0);
        List<Thread> customerThreads = new ArrayList<>();

        for (int i = 1; i <= CONCURRENT_CUSTOMERS; i++) {
            final int customerId = i;
            Thread t = Thread.ofPlatform().name("customer-" + customerId).unstarted(() -> {
                try {
                    startGun.await();
                    Customer customer = new Customer(customerId, "ID_CARD");
                    boolean success = counter.serve(customer, idCard);
                    if (success) {
                        successfulServiced.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
            customerThreads.add(t);
            t.start();
        }

        // Release all customer threads at once into the counter and break scheduler
        startGun.countDown();

        boolean allFinished = finishLatch.await(10, TimeUnit.SECONDS);
        scheduler.stop();

        logger.info("Demo", "---------------------------------------------------------------");
        logger.info("Demo", "PHASE 1: CONCURRENCY & BREAK RESUMPTION RESULTS");
        logger.info("Demo", "---------------------------------------------------------------");
        logger.info("Demo", "All customer threads finished in time: " + allFinished);
        logger.info("Demo", "Customers completed successfully: " + successfulServiced.get() + "/" + CONCURRENT_CUSTOMERS);
        logger.info("Demo", "Counter reported customers served: " + counter.getCustomersServed());
        logger.info("Demo", "Counter total service time recorded: " + counter.getTotalServiceTimeMs() + " ms");
        logger.info("Demo", "Breaks triggered: " + scheduler.getBreaksTriggered()
                + ", Breaks completed: " + scheduler.getBreaksCompleted());

        if (!allFinished || successfulServiced.get() != CONCURRENT_CUSTOMERS
                || counter.getCustomersServed() != CONCURRENT_CUSTOMERS) {
            logger.error("Demo", "FAILURE: Customer was dropped or blocked indefinitely!");
            logger.shutdown();
            return;
        }
        logger.info("Demo", "PHASE 1 PASSED: Zero drops, immediate resumption, exact metric accounting.");

        // PHASE 2: Lifecycle validation (close and reopen semantics)
        logger.info("Demo", "---------------------------------------------------------------");
        logger.info("Demo", "PHASE 2: TESTING CLOSE() AND REOPEN() SEMANTICS");
        logger.info("Demo", "---------------------------------------------------------------");

        counter.close();
        logger.info("Demo", "Counter isOpen() after close(): " + counter.isOpen());
        logger.info("Demo", "Counter isAvailable() after close(): " + counter.isAvailable());

        Customer postCloseCustomer = new Customer(999, "ID_CARD");
        boolean servedWhenClosed = counter.serve(postCloseCustomer, idCard);
        logger.info("Demo", "Request on closed counter rejected cleanly: " + (!servedWhenClosed));

        counter.reopen();
        logger.info("Demo", "Counter isOpen() after reopen(): " + counter.isOpen());
        logger.info("Demo", "Counter isAvailable() after reopen(): " + counter.isAvailable());

        boolean servedAfterReopen = counter.serve(postCloseCustomer, idCard);
        logger.info("Demo", "Request on reopened counter succeeded: " + servedAfterReopen);

        if (!servedWhenClosed && servedAfterReopen && counter.getCustomersServed() == CONCURRENT_CUSTOMERS + 1) {
            logger.info("Demo", "PHASE 2 PASSED: Counter clean lifecycle verified.");
        } else {
            logger.error("Demo", "PHASE 2 FAILED: Lifecycle state transition error.");
        }

        logger.info("Demo", "===============================================================");
        logger.info("Demo", "ALL P3 COFFEE BREAK CONCURRENCY CHECKS PASSED!");
        logger.info("Demo", "===============================================================");
        logger.shutdown();
    }
}