package bureaucracy.customer;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;
import bureaucracy.office.OfficeAPI;
import bureaucracy.office.StubOffice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CustomerDemo {

    private static final int CUSTOMERS = 6;
    private static final double TURN_AWAY_PROBABILITY = 0.2;

    public static void main(String[] args) throws InterruptedException {
        SimulationLogger logger = new SimulationLogger();

        // Hard-coded sample config until the config parser (#7) is ready
        Map<String, Document> documents = new LinkedHashMap<>();
        for (Document document : List.of(
                new Document("ID_CARD", "Identity card", List.of(), "POP", 300),
                new Document("BIRTH_CERT", "Birth certificate", List.of(), "CIV", 400),
                new Document("PROOF_OF_ADDRESS", "Proof of address", List.of("ID_CARD"), "TOWN", 250),
                new Document("TAX_CERT", "Tax certificate", List.of("ID_CARD"), "TAX", 350),
                new Document("BUILDING_PERMIT", "Building permit",
                        List.of("PROOF_OF_ADDRESS", "TAX_CERT", "BIRTH_CERT"), "TOWN", 500))) {
            documents.put(document.getId(), document);
        }

        // Hard-coded plans (prerequisites first) until the planner (#7) is ready
        Map<String, List<String>> plans = Map.of(
                "BUILDING_PERMIT", List.of("ID_CARD", "BIRTH_CERT", "PROOF_OF_ADDRESS", "TAX_CERT", "BUILDING_PERMIT"),
                "TAX_CERT", List.of("ID_CARD", "TAX_CERT"));

        // One stub office per office id, issuing the documents that name it
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

        // Start every customer on its own thread
        List<CustomerTask> tasks = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
                for (int i = 1; i <= CUSTOMERS; i++) {
        String target = (i % 3 == 0) ? "TAX_CERT" : "BUILDING_PERMIT";
        Customer customer = new Customer(i, target);
        List<Document> plan = plans.get(target).stream().map(documents::get).toList();

        CustomerTask task = new CustomerTask(customer, plan, offices, RetryPolicy.DEFAULT,
                logger, customer.toString());
                    tasks.add(task);
                    threads.add(Thread.ofPlatform().name("customer-" + i).start(task));
                }

                // Wait for all of them to finish
                for (Thread thread : threads) {
                thread.join();
                }

        // Report
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
