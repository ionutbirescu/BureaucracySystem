package bureaucracy.office;

import bureaucracy.logging.SimulationLogger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages periodic, randomized coffee breaks across counters without blocking
 * the scheduling engine or deadlocking customer dispatchers.
 */
public final class BreakScheduler {

    public static final long DEFAULT_MIN_BREAK_MS    = 500;
    public static final long DEFAULT_MAX_BREAK_MS    = 2_000;
    public static final long DEFAULT_MIN_INTERVAL_MS = 3_000;
    public static final long DEFAULT_MAX_INTERVAL_MS = 8_000;

    private final List<Counter> counters;
    private final SimulationLogger logger;
    private final long minIntervalMs;
    private final long maxIntervalMs;
    private final long minBreakMs;
    private final long maxBreakMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong breaksTriggered = new AtomicLong();
    private final AtomicLong breaksCompleted = new AtomicLong();

    private final ScheduledExecutorService scheduler;
    private final ExecutorService breakWorkers;

    public BreakScheduler(List<Counter> counters, SimulationLogger logger) {
        this(counters, logger, DEFAULT_MIN_INTERVAL_MS, DEFAULT_MAX_INTERVAL_MS, DEFAULT_MIN_BREAK_MS, DEFAULT_MAX_BREAK_MS);
    }

    public BreakScheduler(List<Counter> counters, SimulationLogger logger,
                          long minIntervalMs, long maxIntervalMs,
                          long minBreakMs, long maxBreakMs) {
        if (minIntervalMs <= 0 || maxIntervalMs < minIntervalMs) {
            throw new IllegalArgumentException("Invalid interval range: " + minIntervalMs + " - " + maxIntervalMs);
        }
        if (minBreakMs <= 0 || maxBreakMs < minBreakMs) {
            throw new IllegalArgumentException("Invalid break duration range: " + minBreakMs + " - " + maxBreakMs);
        }

        this.counters = List.copyOf(Objects.requireNonNull(counters, "counters cannot be null"));
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.minIntervalMs = minIntervalMs;
        this.maxIntervalMs = maxIntervalMs;
        this.minBreakMs = minBreakMs;
        this.maxBreakMs = maxBreakMs;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "break-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Virtual threads execute individual breaks concurrently across counters
        this.breakWorkers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("break-worker-", 0).factory()
        );
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (Counter counter : counters) {
            scheduleNextBreak(counter);
        }
        logger.info("BreakScheduler", "Started – managing " + counters.size() + " counter(s) "
                + "[interval: " + minIntervalMs + "-" + maxIntervalMs + " ms, break: " + minBreakMs + "-" + maxBreakMs + " ms]");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdownNow();
        breakWorkers.shutdownNow();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.warn("BreakScheduler", "Scheduler did not terminate cleanly");
            }
            if (!breakWorkers.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.warn("BreakScheduler", "Break workers did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("BreakScheduler", "Stopped – triggered " + breaksTriggered.get()
                + " break(s), completed " + breaksCompleted.get());
    }

    private void scheduleNextBreak(Counter counter) {
        if (!running.get() || !counter.isOpen()) return;

        long delay = minIntervalMs == maxIntervalMs
                ? minIntervalMs
                : ThreadLocalRandom.current().nextLong(minIntervalMs, maxIntervalMs + 1);

        try {
            scheduler.schedule(() -> triggerBreak(counter), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Handled during stop()
        }
    }

    private void triggerBreak(Counter counter) {
        if (!running.get() || !counter.isOpen()) return;

        long duration = minBreakMs == maxBreakMs
                ? minBreakMs
                : ThreadLocalRandom.current().nextLong(minBreakMs, maxBreakMs + 1);

        breaksTriggered.incrementAndGet();

        try {
            breakWorkers.submit(() -> {
                try {
                    counter.startBreak(duration);
                    breaksCompleted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("BreakScheduler", "Break interrupted for " + counter.getId());
                } finally {
                    if (running.get() && counter.isOpen()) {
                        scheduleNextBreak(counter);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Handled during stop()
        }
    }

    public long getBreaksTriggered() { return breaksTriggered.get(); }
    public long getBreaksCompleted() { return breaksCompleted.get(); }
    public boolean isRunning()       { return running.get(); }
}