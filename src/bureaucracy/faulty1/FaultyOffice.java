package bureaucracy.faulty1;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class FaultyOffice {

    private final String id;
    private final String name;
    private final Map<String, Document> issuableDocuments;
    private final SimulationLogger logger;

    // BUG 1: ArrayList is not thread-safe
    private final List<ServiceRequest> queue = new ArrayList<>();

    // BUG 3: plain int is not thread-safe (should be AtomicLong)
    private int servedCount = 0;

    private volatile boolean running = true;

    public FaultyOffice(String id, String name,
                        Map<String, Document> issuableDocuments,
                        SimulationLogger logger) {
        this.id = id;
        this.name = name;
        this.issuableDocuments = issuableDocuments;
        this.logger = logger;
    }

    public void start() {
        // Two dispatcher threads to amplify the race conditions
        for (int i = 0; i < 2; i++) {
            int idx = i;
            Thread t = new Thread(() -> dispatchLoop(idx),
                    "faulty-dispatcher-" + id + "-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    public void shutdown() {
        running = false;
    }

    // BUG 1 in action: concurrent ArrayList.add() with no synchronization
    public CompletableFuture<Boolean> enqueue(Customer customer, String documentId) {
        Document doc = issuableDocuments.get(documentId);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        // RACE: no lock around add()
        queue.add(new ServiceRequest(customer, doc, future));
        return future;
    }

    private void dispatchLoop(int idx) {
        while (running) {
            // BUG 2: check-then-act without a lock
            // Another thread can remove the element between size() and remove()
            if (queue.size() > 0) {
                ServiceRequest req = queue.remove(0); // IndexOutOfBoundsException possible!
                serve(req);
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void serve(ServiceRequest req) {
        try {
            Thread.sleep(req.document().getProcessingTimeMs());
            // BUG 3: lost updates under contention
            servedCount++;
            logger.info(id, req.customer() + " served (total so far: " + servedCount + ")");
            req.future().complete(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            req.future().complete(false);
        }
    }

    public boolean canIssue(String docId) {
        return issuableDocuments.containsKey(docId);
    }

    // package-visible so tests can read the racy counter
    int getServedCount() { return servedCount; }

    private record ServiceRequest(
            Customer customer,
            Document document,
            CompletableFuture<Boolean> future) {}
}