package bureaucracy.office;

import bureaucracy.logging.SimulationLogger;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BreakScheduler {

    private static final long MIN_BREAK_MS    = 500;
    private static final long MAX_BREAK_MS    = 2_000;
    private static final long MIN_INTERVAL_MS = 3_000;
    private static final long MAX_INTERVAL_MS = 8_000;

    private final List<Counter> counters;
    private final SimulationLogger logger;
    private final Random rng;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "break-scheduler");
                t.setDaemon(true);
                return t;
            });

    public BreakScheduler(List<Counter> counters, SimulationLogger logger) {
        this.counters = List.copyOf(counters);
        this.logger   = logger;
        this.rng      = new Random();
    }

    public void start() {
        for (Counter counter : counters) {
            scheduleNextBreak(counter);
        }
        logger.info("BreakScheduler", "Started – managing " + counters.size() + " counter(s)");
    }

    public void stop() {
        scheduler.shutdownNow();
        logger.info("BreakScheduler", "Stopped");
    }

    private void scheduleNextBreak(Counter counter) {
        long delay = MIN_INTERVAL_MS +
                (long)(rng.nextDouble() * (MAX_INTERVAL_MS - MIN_INTERVAL_MS));
        scheduler.schedule(() -> triggerBreak(counter), delay, TimeUnit.MILLISECONDS);
    }

    private void triggerBreak(Counter counter) {
        if (!counter.isOpen()) return;

        long duration = MIN_BREAK_MS +
                (long)(rng.nextDouble() * (MAX_BREAK_MS - MIN_BREAK_MS));
        try {
            counter.startBreak(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        scheduleNextBreak(counter);
    }
}