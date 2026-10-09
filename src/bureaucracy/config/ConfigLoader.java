package bureaucracy.config;

import bureaucracy.model.Document;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ConfigLoader {

    private ConfigLoader() {}

    public static SimulationConfig load(Path path) throws IOException {
        Map<String, SimulationConfig.OfficeSpec> offices = new LinkedHashMap<>();
        Map<String, Document> documents = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("=", 2);
                if (parts.length < 2) {
                    throw new IllegalArgumentException("Malformed line " + lineNumber + ": " + line);
                }

                String key = parts[0].trim();
                String value = parts[1].trim();

                if (key.startsWith("office.")) {
                    String officeId = key.substring("office.".length());
                    String[] tokens = value.split(":");
                    if (tokens.length < 2) {
                        throw new IllegalArgumentException("Invalid office definition line " + lineNumber);
                    }
                    String name = tokens[0].trim();
                    int counters = Integer.parseInt(tokens[1].trim());
                    offices.put(officeId, new SimulationConfig.OfficeSpec(officeId, name, counters));

                } else if (key.startsWith("doc.")) {
                    String docId = key.substring("doc.".length());
                    String[] tokens = value.split(":", -1);
                    if (tokens.length < 3) {
                        throw new IllegalArgumentException("Invalid document definition line " + lineNumber);
                    }
                    String name = tokens[0].trim();
                    String officeId = tokens[1].trim();
                    long processingTimeMs = Long.parseLong(tokens[2].trim());
                    List<String> prereqs = Collections.emptyList();
                    if (tokens.length >= 4 && !tokens[3].trim().isEmpty()) {
                        prereqs = Arrays.stream(tokens[3].split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
                    }
                    documents.put(docId, new Document(docId, name, prereqs, officeId, processingTimeMs));
                }
            }
        }

        return new SimulationConfig(offices, documents);
    }
}