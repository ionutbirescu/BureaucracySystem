package bureaucracy.config;

import bureaucracy.model.Document;

import java.util.*;

public final class DocumentPlanner {

    private final Map<String, Document> documents;

    public DocumentPlanner(Map<String, Document> documents) {
        this.documents = Objects.requireNonNull(documents, "documents cannot be null");
    }

    /**
     * Validates that all documents form a Directed Acyclic Graph (DAG)
     * and that all prerequisites exist in the document catalog.
     */
    public void validateAcyclic() {
        for (Document doc : documents.values()) {
            for (String prereqId : doc.getPrerequisites()) {
                if (!documents.containsKey(prereqId)) {
                    throw new IllegalArgumentException(
                            "Document " + doc.getId() + " requires non-existent prerequisite: " + prereqId);
                }
            }
        }

        // DFS cycle check: 0 = unvisited, 1 = visiting (recursion stack), 2 = visited
        Map<String, Integer> state = new HashMap<>();
        List<String> cyclePath = new ArrayList<>();

        for (String docId : documents.keySet()) {
            if (state.getOrDefault(docId, 0) == 0) {
                if (hasCycleDfs(docId, state, cyclePath)) {
                    Collections.reverse(cyclePath);
                    throw new IllegalStateException("Cyclic dependency detected: " + String.join(" -> ", cyclePath));
                }
            }
        }
    }

    private boolean hasCycleDfs(String current, Map<String, Integer> state, List<String> cyclePath) {
        state.put(current, 1);
        cyclePath.add(current);

        Document doc = documents.get(current);
        if (doc != null) {
            for (String prereqId : doc.getPrerequisites()) {
                int prereqState = state.getOrDefault(prereqId, 0);
                if (prereqState == 1) {
                    cyclePath.add(prereqId);
                    return true;
                }
                if (prereqState == 0 && hasCycleDfs(prereqId, state, cyclePath)) {
                    return true;
                }
            }
        }

        state.put(current, 2);
        cyclePath.remove(cyclePath.size() - 1);
        return false;
    }

    /**
     * Creates a step-by-step plan (topologically sorted) to obtain the target document,
     * ensuring prerequisites always precede documents that require them.
     */
    public List<Document> createPlan(String targetDocumentId) {
        if (!documents.containsKey(targetDocumentId)) {
            throw new IllegalArgumentException("Target document not found in config: " + targetDocumentId);
        }

        // 1. Collect all transitive prerequisites for targetDocumentId
        Set<String> needed = new HashSet<>();
        collectPrerequisites(targetDocumentId, needed);
        needed.add(targetDocumentId);

        // 2. Compute in-degrees within the subgraph of needed documents
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (String docId : needed) {
            inDegree.put(docId, 0);
            dependents.put(docId, new ArrayList<>());
        }

        for (String docId : needed) {
            Document doc = documents.get(docId);
            for (String prereq : doc.getPrerequisites()) {
                if (needed.contains(prereq)) {
                    dependents.get(prereq).add(docId);
                    inDegree.put(docId, inDegree.get(docId) + 1);
                }
            }
        }

        // 3. Kahn's Algorithm
        Queue<String> ready = new ArrayDeque<>();
        for (String docId : needed) {
            if (inDegree.get(docId) == 0) {
                ready.add(docId);
            }
        }

        List<Document> plan = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            plan.add(documents.get(current));

            for (String dependent : dependents.get(current)) {
                int remaining = inDegree.get(dependent) - 1;
                inDegree.put(dependent, remaining);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (plan.size() != needed.size()) {
            throw new IllegalStateException("Dependency cycle detected while building plan for: " + targetDocumentId);
        }

        return plan;
    }

    private void collectPrerequisites(String docId, Set<String> collected) {
        Document doc = documents.get(docId);
        if (doc == null) return;
        for (String prereq : doc.getPrerequisites()) {
            if (collected.add(prereq)) {
                collectPrerequisites(prereq, collected);
            }
        }
    }
}