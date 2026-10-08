package bureaucracy.office;

import bureaucracy.model.Customer;

import java.util.concurrent.CompletableFuture;

public interface OfficeAPI {
    String getId();

    boolean canIssue(String documentId);

    CompletableFuture<Boolean> enqueue(Customer customer, String documentId);
}
