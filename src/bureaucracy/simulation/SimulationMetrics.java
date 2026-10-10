package bureaucracy.simulation;

import bureaucracy.customer.CustomerTask;
import bureaucracy.model.Customer;
import bureaucracy.office.Counter;
import bureaucracy.office.Office;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metrics for one simulation run.
 * Customer threads record themselves when they finish (LongAdder / LongAccumulator: no lock,
 * no lost updates); counters are read from the offices once everything has stopped.
 */
public final class SimulationMetrics {

    private final Map<CustomerTask.Outcome, LongAdder> outcomes = new ConcurrentHashMap<>();
    private final LongAdder finishedCustomers = new LongAdder();
    private final LongAdder totalElapsedMs = new LongAdder();
    private final LongAccumulator minElapsedMs = new LongAccumulator(Math::min, Long.MAX_VALUE);
    private final LongAccumulator maxElapsedMs = new LongAccumulator(Math::max, 0);
    private final LongAdder requeues = new LongAdder();
    private final LongAdder documentsHeld = new LongAdder();
    private final LongAdder targetMissing = new LongAdder();

    /** Called by each customer thread when its task is over (success or not). */
    public void recordCustomer(CustomerTask task) {
        Customer customer = task.getCustomer();
        outcomes.computeIfAbsent(task.getOutcome(), o -> new LongAdder()).increment();
        finishedCustomers.increment();
        totalElapsedMs.add(task.getElapsedMs());
        minElapsedMs.accumulate(task.getElapsedMs());
        maxElapsedMs.accumulate(task.getElapsedMs());
        requeues.add(task.getRequeueCount());
        documentsHeld.add(customer.getObtainedDocuments().size());
        if (task.getOutcome() == CustomerTask.Outcome.SUCCESS
                && !customer.hasDocument(customer.getTargetDocumentId())) {
            targetMissing.increment();
        }
    }

    /** Builds the final report; call it only after all offices have been shut down. */
    public SimulationReport report(int customersStarted, List<Office> offices, long wallTimeMs, boolean timedOut) {
        Map<CustomerTask.Outcome, Long> outcomeCounts = new EnumMap<>(CustomerTask.Outcome.class);
        outcomes.forEach((outcome, count) -> outcomeCounts.put(outcome, count.sum()));

        List<SimulationReport.CounterStats> counters = new ArrayList<>();
        long served = 0;
        for (Office office : offices) {
            for (Counter counter : office.getCounters()) {
                served += counter.getCustomersServed();
                counters.add(new SimulationReport.CounterStats(office.getId(), counter.getId(),
                        counter.getCustomersServed(), counter.getTotalServiceTimeMs()));
            }
        }

        long finished = finishedCustomers.sum();
        return new SimulationReport(
                customersStarted,
                finished,
                outcomeCounts,
                finished == 0 ? 0 : totalElapsedMs.sum() / finished,
                finished == 0 ? 0 : minElapsedMs.get(),
                maxElapsedMs.get(),
                requeues.sum(),
                served,
                documentsHeld.sum(),
                targetMissing.sum(),
                counters,
                wallTimeMs,
                timedOut);
    }
}
