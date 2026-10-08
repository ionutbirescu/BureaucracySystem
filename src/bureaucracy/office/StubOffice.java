package bureaucracy.office;

import bureaucracy.model.Customer;
import bureaucracy.model.Document;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class StubOffice implements OfficeAPI {

    private final String id;
    private final Map<String, Document> issuableDocuments;
    private final double turnAwayProbability;
    private final AtomicLong issuedCount = new AtomicLong();

    public StubOffice(String id, Map<String, Document> issuableDocuments, double turnAwayProbability) {
        this.id = id;
        this.issuableDocuments = issuableDocuments;
        this.turnAwayProbability = turnAwayProbability;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean canIssue(String documentId) {
        return issuableDocuments.containsKey(documentId);
    }

    @Override
    public CompletableFuture<Boolean> enqueue(Customer customer, String documentId) {
        Document doc = issuableDocuments.get(documentId);
        if(doc == null){
            throw new IllegalArgumentException("Office "+ id + " does not issue document "+ documentId);
        }

        CompletableFuture<Boolean> ticket = new CompletableFuture<>();

        Thread.ofPlatform().name("stub-" + id + "-" + customer.getId()).start(() -> {
                    try {
                        Thread.sleep(doc.getProcessingTimeMs());
                    } catch (InterruptedException e) {
                        ticket.complete(false);
                        return;
                    }
                    if (ThreadLocalRandom.current().nextDouble() < turnAwayProbability) {
                        ticket.complete(false);
                    } else {
                        ticket.complete(true);
                        issuedCount.incrementAndGet();
                    }
                }
        );
        return ticket;
    }

    public long getIssuedCount(){
        return issuedCount.get();
    }
}
