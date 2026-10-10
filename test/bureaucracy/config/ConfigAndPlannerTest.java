package bureaucracy.config;

import bureaucracy.model.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAndPlannerTest {

    @Test
    void loadsTheShippedConfig() throws IOException {
        SimulationConfig config = ConfigLoader.load(Path.of("config/simulation.properties"));

        assertEquals(4, config.offices().size());
        assertEquals(3, config.offices().get("TOWN").counterCount());
        assertEquals(List.of("PROOF_OF_ADDRESS", "TAX_CERT", "BIRTH_CERT"),
                config.documents().get("BUILDING_PERMIT").getPrerequisites());
    }

    @Test
    void malformedLineIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.properties");
        Files.writeString(file, "office.A = Office A : 1\nthis line has no equals sign\n");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(file));
    }

    @Test
    void planPutsPrerequisitesFirst() throws IOException {
        SimulationConfig config = ConfigLoader.load(Path.of("config/simulation.properties"));
        List<String> plan = new DocumentPlanner(config.documents()).createPlan("BUILDING_PERMIT")
                .stream().map(Document::getId).toList();

        assertEquals("BUILDING_PERMIT", plan.getLast());
        assertEquals(5, plan.size());
        for (String id : plan) {
            for (String prerequisite : config.documents().get(id).getPrerequisites()) {
                assertTrue(plan.indexOf(prerequisite) < plan.indexOf(id), prerequisite + " before " + id);
            }
        }
    }

    @Test
    void cycleIsDetected() {
        Map<String, Document> docs = Map.of(
                "A", new Document("A", "A", List.of("B"), "O", 1),
                "B", new Document("B", "B", List.of("C"), "O", 1),
                "C", new Document("C", "C", List.of("A"), "O", 1));
        assertThrows(IllegalStateException.class, () -> new DocumentPlanner(docs).validateAcyclic());
    }

    @Test
    void missingPrerequisiteIsDetected() {
        Map<String, Document> docs = Map.of("A", new Document("A", "A", List.of("GHOST"), "O", 1));
        assertThrows(IllegalArgumentException.class, () -> new DocumentPlanner(docs).validateAcyclic());
    }
}
