package bureaucracy.model;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Customer {
    private final int id;
    private final String targetDocumentId;
    private final Set<String> obtainedDocuments = ConcurrentHashMap.newKeySet();

    public Customer(int id, String targetDocumentId) {
        this.id = id;
        this.targetDocumentId = targetDocumentId;
    }

    public int getId() {
        return id;
    }

    public String getTargetDocumentId() {
        return targetDocumentId;
    }

    public Set<String> getObtainedDocuments() {
        return Collections.unmodifiableSet(obtainedDocuments);
    }

    public void addDocument(String documentId) {
        obtainedDocuments.add(documentId);
    }

    public boolean hasDocument(String documentId) {
        return obtainedDocuments.contains(documentId);
    }

    @Override
    public String toString() {
        return "Customer#" + id;

    }
}