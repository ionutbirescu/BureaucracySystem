package bureaucracy.model;

import java.util.Collections;
import java.util.List;

public final class Document{
    private final String id;
    private final String name;
    private final List<String> prerequisites;
    private final String officeID;
    private final long processingTimeMs;

    public Document(String id, String name, List<String> prerequisites, String officeID, long processingTimeMs) {
        this.id = id;
        this.name = name;
        this.officeID = officeID;
        this.prerequisites = Collections.unmodifiableList(List.copyOf(prerequisites));
        this.processingTimeMs = processingTimeMs;
    }

    public String getId()                   { return id; }
    public String getName()                 { return name; }
    public List<String> getPrerequisites()  { return prerequisites; }
    public String getOfficeId()             { return officeID; }
    public long getProcessingTimeMs()       { return processingTimeMs; }

    @Override
    public String toString() {
        return "Document{id='" + id + "', name='" + name + "'}";
    }
}