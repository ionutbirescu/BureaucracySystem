package bureaucracy.simulation;

import bureaucracy.customer.CustomerTask;
import bureaucracy.logging.SimulationLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable result of a simulation run, plus the consistency checks of the correct solution:
 * every customer finished, nobody failed, every document issued by a counter is held by
 * exactly one customer (nobody served twice, nobody lost).
 */
public record SimulationReport(
        int customersStarted,
        long customersFinished,
        Map<CustomerTask.Outcome, Long> outcomes,
        long avgCustomerMs,
        long minCustomerMs,
        long maxCustomerMs,
        long requeues,
        long documentsServed,
        long documentsHeld,
        long successWithoutTarget,
        List<CounterStats> counters,
        long wallTimeMs,
        boolean timedOut) {

    public record CounterStats(String officeId, String counterId, long served, long busyMs) {}

    public SimulationReport {
        outcomes = Map.copyOf(outcomes);
        counters = List.copyOf(counters);
    }

    public long count(CustomerTask.Outcome outcome) {
        return outcomes.getOrDefault(outcome, 0L);
    }

    /** Human-readable list of every broken invariant; empty means the run was correct. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (timedOut) {
            problems.add("simulation timed out before every customer finished");
        }
        if (customersFinished != customersStarted) {
            problems.add("finished customers (" + customersFinished + ") != started (" + customersStarted + ")");
        }
        if (count(CustomerTask.Outcome.SUCCESS) != customersStarted) {
            problems.add("only " + count(CustomerTask.Outcome.SUCCESS) + "/" + customersStarted
                    + " customers got their document " + outcomes);
        }
        if (documentsServed != documentsHeld) {
            problems.add("documents served by counters (" + documentsServed + ") != held by customers ("
                    + documentsHeld + ") -> someone was served twice or lost");
        }
        if (successWithoutTarget > 0) {
            problems.add(successWithoutTarget + " customer(s) reported success without the target document");
        }
        return problems;
    }

    public boolean isConsistent() {
        return problems().isEmpty();
    }

    public void print(SimulationLogger logger) {
        String src = "Metrics";
        logger.info(src, "================ SIMULATION REPORT ================");
        logger.info(src, "customers started/finished : " + customersStarted + " / " + customersFinished);
        logger.info(src, "outcomes                   : " + outcomes);
        logger.info(src, "time per customer (ms)     : min=" + minCustomerMs + " avg=" + avgCustomerMs
                + " max=" + maxCustomerMs);
        logger.info(src, "re-queues                  : " + requeues);
        logger.info(src, "documents served / held    : " + documentsServed + " / " + documentsHeld);
        for (CounterStats c : counters) {
            logger.info(src, String.format("  %-6s %-8s served=%-3d busy=%d ms",
                    c.officeId(), c.counterId(), c.served(), c.busyMs()));
        }
        logger.info(src, "wall time                  : " + wallTimeMs + " ms");
        List<String> problems = problems();
        if (problems.isEmpty()) {
            logger.info(src, "RESULT: CONSISTENT - all customers served, nobody served twice or lost");
        } else {
            problems.forEach(p -> logger.error(src, "  " + p));
            logger.error(src, "RESULT: INCONSISTENT");
        }
        logger.info(src, "===================================================");
    }
}
