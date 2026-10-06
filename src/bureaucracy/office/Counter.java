package bureaucracy.office;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class Counter {

    private final String id;
    private final String officeId;
    private final SimulationLogger logger;

    private final Semaphore serviceSemaphore = new Semaphore(1, true);

    private final ReentrantLock breakLock = new ReentrantLock();
    private final Condition notOnBreak = breakLock.newCondition();
    private volatile boolean onBreak = false;

    private final AtomicBoolean open = new AtomicBoolean(true);

    private final AtomicLong customersServed = new AtomicLong();
    private final AtomicLong totalServiceTimeMs = new AtomicLong();

    public Counter(String id, String officeId, SimulationLogger logger) {
        this.id = id;
        this.officeId = officeId;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return open.get() && !onBreak;
    }

    public boolean serve(Customer customer, Document document) throws InterruptedException {
        if (!open.get()) return false;

        serviceSemaphore.acquire();
        try {
            if (!open.get() || onBreak) {
                logger.warn(id, customer + " found the unavailable counter – re-queue");
                return false;
            }

            logger.info(id, customer + " served for " + document.getName());
            long start = System.currentTimeMillis();

            Thread.sleep(document.getProcessingTimeMs());

            long elapsed = System.currentTimeMillis() - start;
            customersServed.incrementAndGet();
            totalServiceTimeMs.addAndGet(elapsed);

            logger.info(id, customer + " received " + document.getName()
                    + " (" + elapsed + " ms)");
            return true;

        } finally {
            serviceSemaphore.release();
        }
    }

    public void startBreak(long durationMs) throws InterruptedException {
        breakLock.lock();
        try {
            if (!open.get() || onBreak) return;
            serviceSemaphore.acquire();
            onBreak = true;
            serviceSemaphore.release();
            logger.info(id, "Coffee Break (" + durationMs + " ms)");
        } finally {
            breakLock.unlock();
        }

        Thread.sleep(durationMs);
        endBreak();
    }

    private void endBreak() {
        breakLock.lock();
        try {
            onBreak = false;
            notOnBreak.signalAll();
            logger.info(id, " Coffee Break ended, the counter is open!");
        } finally {
            breakLock.unlock();
        }
    }

    public void close() {
        open.set(false);
        breakLock.lock();
        try {
            notOnBreak.signalAll();
        } finally {
            breakLock.unlock();
        }
        logger.info(id, "Counter closed!");
    }

    public long getCustomersServed()    { return customersServed.get(); }
    public long getTotalServiceTimeMs() { return totalServiceTimeMs.get(); }
    public String getId()               { return id; }
    public String getOfficeId()         { return officeId; }
    public boolean isOpen()             { return open.get(); }
    public boolean isOnBreak()          { return onBreak; }
}