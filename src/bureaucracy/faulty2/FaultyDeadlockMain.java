package bureaucracy.faulty2;

import bureaucracy.customer.CustomerTask;
import bureaucracy.customer.RetryPolicy;
import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;
import bureaucracy.office.OfficeAPI;

import java.util.List;
import java.util.Map;

public final class FaultyDeadlockMain {

    private static final long WATCHDOG_MS = 3_000;

    public static void main(String[] args) throws InterruptedException {
        SimulationLogger logger = new SimulationLogger();

        Document proofOfAddress = new Document("PROOF_OF_ADDRESS", "Proof of address", List.of(), "TOWN", 300);
        Document townStamp      = new Document("TOWN_STAMP", "Town hall stamp", List.of(), "TOWN", 100);
        Document taxCert        = new Document("TAX_CERT", "Tax certificate", List.of(), "TAX", 300);
        Document taxStamp       = new Document("TAX_STAMP", "Tax office stamp", List.of(), "TAX", 100);

        // One counter per office, so a single held counter blocks the whole office
        DeadlockOffice town = new DeadlockOffice("TOWN", "Town hall",
                List.of(new DeadlockCounter("TOWN-1", "TOWN", logger)),
                Map.of(proofOfAddress.getId(), proofOfAddress, townStamp.getId(), townStamp), logger);
        DeadlockOffice tax = new DeadlockOffice("TAX", "Tax office",
                List.of(new DeadlockCounter("TAX-1", "TAX", logger)),
                Map.of(taxCert.getId(), taxCert, taxStamp.getId(), taxStamp), logger);

        // BUG: circular wait – TOWN needs a stamp from TAX and TAX needs one from TOWN
        town.requireStamp("PROOF_OF_ADDRESS", tax, "TAX_STAMP");
        tax.requireStamp("TAX_CERT", town, "TOWN_STAMP");

        List<DeadlockOffice> allOffices = List.of(town, tax);
        Map<String, OfficeAPI> offices = Map.of(town.getId(), town, tax.getId(), tax);
        allOffices.forEach(DeadlockOffice::start);

        // Long timeout so the customers are still blocked when the watchdog looks
        RetryPolicy patient = new RetryPolicy(1, 0, 0, 60_000);

        Customer first = new Customer(1, "PROOF_OF_ADDRESS");
        Customer second = new Customer(2, "TAX_CERT");
        List<CustomerTask> tasks = List.of(
                new CustomerTask(first, List.of(proofOfAddress), offices, patient, logger, first.toString()),
                new CustomerTask(second, List.of(taxCert), offices, patient, logger, second.toString()));

        // Both start together: each one is at its counter before the other goes for its stamp
        for (CustomerTask task : tasks) {
            Thread.ofPlatform().daemon(true).name("customer-" + task.getCustomer().getId()).start(task);
        }

        Thread.sleep(WATCHDOG_MS);

        // Watchdog: report who holds which counter
        long served = 0;
        for (DeadlockOffice office : allOffices) {
            for (DeadlockCounter counter : office.getCounters()) {
                served += counter.getCustomersServed();
                logger.info("Watchdog", counter.getId() + " held by " + counter.getHeldBy()
                        + ", served=" + counter.getCustomersServed());
            }
        }
        for (CustomerTask task : tasks) {
            logger.info("Watchdog", task.getCustomer() + " is " + task.getOutcome());
        }

        boolean allStuck = tasks.stream().allMatch(t -> t.getOutcome() == CustomerTask.Outcome.RUNNING);
        if (served == 0 && allStuck) {
            logger.error("Watchdog", "DEADLOCK: no counter finished in " + WATCHDOG_MS
                    + " ms - each counter is held by a customer waiting on the other office");
        } else {
            logger.info("Watchdog", "no deadlock this run (served=" + served + ")");
        }

        allOffices.forEach(DeadlockOffice::shutdown);
        logger.shutdown();
    }
}