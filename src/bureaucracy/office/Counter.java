package bureaucracy.office;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe bureau counter supporting concurrent customer servicing,
 * coffee break coordination, and clean lifecycle management.
 * Explicitly coordinated via ReentrantLock and Condition notOnBreak.
 */
public final class Counter {

    private final String id;
    private final String officeId;
    private final SimulationLogger logger;

    // Fair lock ensures FIFO queueing among waiting customers and break requests
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition notOnBreak = lock.newCondition();

    // Guarded by lock
    private boolean open = true;
    private boolean onBreak = false;
    private boolean breakPending = false;
    private boolean serving = false;
    private Customer heldBy;
    private Runnable availabilityListener = () -> {};

    // Lock-free performance metrics
    private final AtomicLong customersServed = new AtomicLong();
    private final AtomicLong totalServiceTimeMs = new AtomicLong();

    public Counter(String id, String officeId, SimulationLogger logger) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.officeId = Objects.requireNonNull(officeId, "officeId cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    /**
     * Attempts to serve a customer, waiting safely on notOnBreak if the counter is on break.
     *
     * @param customer customer to serve
     * @param document document being processed
     * @return true if document was successfully processed and issued; false if counter closed
     * @throws InterruptedException if interrupted while waiting or being served
     */
    public boolean serve(Customer customer, Document document) throws InterruptedException {
        return serve(customer, document, true);
    }

    /**
     * Serves a customer with optional redirection semantics.
     *
     * @param customer     customer to serve
     * @param document     document being processed
     * @param waitForBreak true to wait on notOnBreak condition; false to immediately redirect (return false)
     * @return true if served; false if closed or redirected
     * @throws InterruptedException if thread was interrupted
     */
    public boolean serve(Customer customer, Document document, boolean waitForBreak) throws InterruptedException {
        Objects.requireNonNull(customer, "customer cannot be null");
        Objects.requireNonNull(document, "document cannot be null");

        lock.lockInterruptibly();
        try {
            if (!open) {
                return false;
            }

            // If caller chooses not to wait during active/pending break or busy desk, deflect immediately
            if (!waitForBreak && (onBreak || breakPending || serving)) {
                logger.warn(id, customer + " found counter unavailable – redirecting/re-queueing");
                return false;
            }

            // Loop guards against spurious wakeups (only entered when waitForBreak is true)
            while (open && (serving || onBreak || breakPending)) {
                notOnBreak.await();
            }

            if (!open) {
                return false;
            }

            serving = true;
            heldBy = customer;
            logger.info(id, customer + " served for " + document.getName());
        } finally {
            lock.unlock();
        }

        // Processing occurs outside the lock so status queries and break requests are not blocked
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(document.getProcessingTimeMs());
            long elapsed = System.currentTimeMillis() - start;
            lock.lock();
            try {
                customersServed.incrementAndGet();
                totalServiceTimeMs.addAndGet(elapsed);
                logger.info(id, customer + " received " + document.getName()
                        + " (" + elapsed + " ms)");
            } finally {
                lock.unlock();
            }
            return true;
        } finally {
            // Always clean up the serving state and wake waiting threads, even on InterruptedException
            lock.lock();
            try {
                serving = false;
                heldBy = null;
                notOnBreak.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Non-blocking trial method for immediate redirection.
     */
    public boolean tryServe(Customer customer, Document document) throws InterruptedException {
        return serve(customer, document, false);
    }

    /**
     * Initiates a coffee break. If a customer is currently being served, waits cleanly
     * for that customer to finish before transitioning into break state.
     *
     * @param durationMs length of the coffee break in milliseconds
     * @throws InterruptedException if interrupted while waiting for customer or sleeping
     */
    public void startBreak(long durationMs) throws InterruptedException {
        if (durationMs <= 0) return;

        lock.lockInterruptibly();
        try {
            if (!open || onBreak || breakPending) {
                return;
            }

            breakPending = true;

            // Wait cleanly for any active customer to finish their paperwork
            while (open && serving) {
                logger.debug(id, "Break requested; waiting for active customer to finish");
                notOnBreak.await();
            }

            if (!open) {
                breakPending = false;
                return;
            }

            onBreak = true;
            breakPending = false;
            logger.info(id, "Coffee Break (" + durationMs + " ms)");
        } catch (Throwable t) {
            if (breakPending && !onBreak) {
                breakPending = false;
                notOnBreak.signalAll();
            }
            throw t;
        } finally {
            lock.unlock();
        }

        // Clerk takes break outside the state lock; finally block ensures break state always clears
        try {
            Thread.sleep(durationMs);
        } finally {
            endBreak();
        }
    }

    /**
     * Ends the coffee break and wakes all waiting customers immediately.
     */
    public void endBreak() {
        lock.lock();
        try {
            if (!onBreak) return;
            onBreak = false;
            notOnBreak.signalAll();
            logger.info(id, "Coffee Break ended, the counter is open!");
        } finally {
            lock.unlock();
        }

        // Listener invoked outside lock to avoid lock-order inversion
        availabilityListener.run();
    }

    /**
     * Closes the counter permanently (or for shutdown). Releases any blocked threads.
     */
    public void close() {
        lock.lock();
        try {
            if (!open) return;
            open = false;
            notOnBreak.signalAll();
            logger.info(id, "Counter closed!");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reopens a closed counter and resets operational states.
     */
    public void reopen() {
        lock.lock();
        try {
            if (open) return;
            open = true;
            onBreak = false;
            breakPending = false;
            serving = false;
            heldBy = null;
            notOnBreak.signalAll();
            logger.info(id, "Counter reopened!");
        } finally {
            lock.unlock();
        }

        availabilityListener.run();
    }

    public void setAvailabilityListener(Runnable listener) {
        this.availabilityListener = (listener != null) ? listener : () -> {};
    }

    // Atomic, consistent status queries
    public boolean isOpen() {
        lock.lock();
        try {
            return open;
        } finally {
            lock.unlock();
        }
    }

    public boolean isOnBreak() {
        lock.lock();
        try {
            return onBreak || breakPending;
        } finally {
            lock.unlock();
        }
    }

    public boolean isAvailable() {
        lock.lock();
        try {
            return open && !onBreak && !breakPending && !serving;
        } finally {
            lock.unlock();
        }
    }

    public boolean isBusy() {
        lock.lock();
        try {
            return serving;
        } finally {
            lock.unlock();
        }
    }

    public Customer getHeldBy() {
        lock.lock();
        try {
            return heldBy;
        } finally {
            lock.unlock();
        }
    }

    public long getCustomersServed()    { return customersServed.get(); }
    public long getTotalServiceTimeMs() { return totalServiceTimeMs.get(); }
    public String getId()               { return id; }
    public String getOfficeId()         { return officeId; }
}