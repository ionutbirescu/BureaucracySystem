package bureaucracy.customer;

import bureaucracy.config.ConfigLoader;
import bureaucracy.config.DocumentPlanner;
import bureaucracy.config.SimulationConfig;
import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;
import bureaucracy.office.OfficeAPI;
import bureaucracy.office.StubOffice;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CustomerDemo {

    private static final int CUSTOMERS = 6;
    private static final double TURN_AWAY_PROBABILITY = 0.2;

    public static void main(String[] args) throws InterruptedException, IOException {
        SimulationLogger logger = new SimulationLogger();

        // 1. Load config and validate DAG
        SimulationConfig config = ConfigLoader.load(Path.of("config/simulation.properties"));
        Map<String, Document> documents = config.documents();

        DocumentPlanner planner = new DocumentPlanner(documents);
        planner.validateAcyclic();

        // 2. Map documents by issuing office and build office stubs
        Map<String, Map<String, Document>> documentsByOffice = new LinkedHashMap<>();
        for (Document document : documents.values()) {
            documentsByOffice.computeIfAbsent(document.getOfficeId(), id -> new HashMap<>())
                    .put(document.getId(), document);
        }

        Map<String, OfficeAPI> offices = new LinkedHashMap<>();
        List<StubOffice> stubs = new ArrayList<>();
        documentsByOffice.forEach((officeId, issued) -> {
            StubOffice stub = new StubOffice(officeId, issued, TURN_AWAY_PROBABILITY);
            offices.put(officeId, stub);
            stubs.add(stub);
        });

        // 3. Start every customer thread with a dynamically generated topological plan
        List<CustomerTask> tasks = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= CUSTOMERS; i++) {
            String target = (i % 3 == 0) ? "TAX_CERT" : "BUILDING_PERMIT";
            Customer customer = new Customer(i, target);
            List<Document> plan = planner.createPlan(target);

            CustomerTask task = new CustomerTask(customer, plan, offices, RetryPolicy.DEFAULT,
                    logger, customer.toString());
            tasks.add(task);
            threads.add(Thread.ofPlatform().name("customer-" + i).start(task));
        }

        // 4. Wait for all customer threads to finish
        for (Thread thread : threads) {
            thread.join();
        }

        // 5. Report results
        long held = 0;
        for (CustomerTask task : tasks) {
            Customer customer = task.getCustomer();
            held += customer.getObtainedDocuments().size();
            logger.info("Demo", customer + " wanted " + customer.getTargetDocumentId() + ": "
                    + task.getOutcome() + " in " + task.getElapsedMs() + " ms, "
                    + task.getRequeueCount() + " re-queue(s)");
        }

        long issued = 0;
        for (StubOffice stub : stubs) {
            issued += stub.getIssuedCount();
        }
        logger.info("Demo", "documents issued by offices: " + issued + ", held by customers: " + held
                + (issued == held ? " -> no duplicates" : " -> MISMATCH"));

        logger.shutdown();
    }
}