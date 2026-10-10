package bureaucracy.faulty2;

import bureaucracy.customer.CustomerTask;
import bureaucracy.diagnostics.DeadlockDetector;
import bureaucracy.logging.SimulationLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlockDemoTest {

    private SimulationLogger logger;

    @BeforeEach
    void quietLogger() {
        logger = new SimulationLogger(new PrintStream(OutputStream.nullOutputStream()));
    }

    @AfterEach
    void stopLogger() {
        logger.shutdown();
    }

    @Test
    @Timeout(20)
    void twoCustomersInOppositeOrderDeadlockAndAreDetected() throws InterruptedException {
        DeadlockDemo.Result result = DeadlockDemo.run(DeadlockDemo.Mode.FAULTY, logger);
        DeadlockDetector.Report report = result.report();

        assertTrue(report.deadlocked());
        assertEquals(0, report.progressAtEnd(), "no counter may finish a customer");
        // cycle is [X, Y, X] and contains both customers
        assertEquals(3, report.waitForCycle().size());
        assertTrue(report.waitForCycle().stream().anyMatch(n -> n.startsWith("Customer#1@")));
        assertTrue(report.waitForCycle().stream().anyMatch(n -> n.startsWith("Customer#2@")));
        // the JVM's own detector cannot see a Semaphore/future cycle - that is why we need ours
        assertFalse(report.jvmSawIt());
        assertTrue(report.threadDump().contains("DeadlockCounter.serve"), report.threadDump());
        assertFalse(result.outcomes().containsValue(CustomerTask.Outcome.SUCCESS));
    }

    @Test
    @Timeout(20)
    void fixedVersionFinishes() throws InterruptedException {
        DeadlockDemo.Result result = DeadlockDemo.run(DeadlockDemo.Mode.FIXED, logger);

        assertFalse(result.report().deadlocked());
        assertTrue(result.outcomes().values().stream().allMatch(o -> o == CustomerTask.Outcome.SUCCESS),
                result.outcomes().toString());
    }

    @Test
    void waitForGraphFindsCycles() {
        DeadlockDetector.WaitForGraph graph = new DeadlockDetector.WaitForGraph()
                .addEdge("A", "B").addEdge("B", "C").addEdge("C", "A").addEdge("D", "A");
        assertEquals(List.of("A", "B", "C", "A"), graph.findCycle().orElseThrow());

        DeadlockDetector.WaitForGraph chain = new DeadlockDetector.WaitForGraph()
                .addEdge("A", "B").addEdge("B", "C");
        assertTrue(chain.findCycle().isEmpty());
    }

    @Test
    @Timeout(10)
    void jvmDetectorSeesClassicLockDeadlock() throws InterruptedException {
        Object first = new Object();
        Object second = new Object();
        java.util.concurrent.CountDownLatch bothHoldOne = new java.util.concurrent.CountDownLatch(2);
        Runnable ab = () -> lockBoth(first, second, bothHoldOne);
        Runnable ba = () -> lockBoth(second, first, bothHoldOne);
        Thread.ofPlatform().daemon(true).name("lock-test-1").start(ab);
        Thread.ofPlatform().daemon(true).name("lock-test-2").start(ba);

        DeadlockDetector detector = new DeadlockDetector(DeadlockDetector.WaitForGraph::new, () -> 0,
                List.of("lock-test-"));
        DeadlockDetector.Report report = detector.observe(3_000, 50);

        assertTrue(report.deadlocked());
        assertTrue(report.jvmSawIt());
        assertTrue(report.threadDump().contains("lock-test-1"));
    }

    private static void lockBoth(Object a, Object b, java.util.concurrent.CountDownLatch bothHoldOne) {
        synchronized (a) {
            bothHoldOne.countDown();
            try {
                bothHoldOne.await();
            } catch (InterruptedException e) {
                return;
            }
            synchronized (b) {
                // never reached
                bothHoldOne.countDown();
            }
        }
    }
}
