package bureaucracy.config;

import bureaucracy.model.Document;

import java.util.Collections;
import java.util.Map;

public record SimulationConfig(
        Map<String, OfficeSpec> offices,
        Map<String, Document> documents
) {
    public SimulationConfig {
        offices = Collections.unmodifiableMap(offices);
        documents = Collections.unmodifiableMap(documents);
    }

    public record OfficeSpec(String id, String name, int counterCount) {}
}