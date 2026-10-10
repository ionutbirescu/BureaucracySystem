package bureaucracy.simulation;

import bureaucracy.config.ConfigLoader;
import bureaucracy.config.SimulationConfig;
import bureaucracy.customer.CustomerTask;
import bureaucracy.logging.SimulationLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationTest {

    private SimulationLogger logger;

    @BeforeEach
    void quietLogger() {
        logger = new SimulationLogger(new PrintStream(OutputStream.nullOutputStream()));
    }

    @AfterEach
    void stopLogger() {
        logger.shutdown();
    }

    private static SimulationConfig defaultConfig() throws IOException {
        return ConfigLoader.load(Path.of("config/simulation.properties"));
    }

    @Test
    @Timeout(60)
    void correctVersionServesEveryCustomerExactlyOnce() throws Exception {
        SimulationReport report = new Simulation(defaultConfig(),
                Simulation.Options.defaults(12), logger).run();

        assertTrue(report.isConsistent(), () -> "problems: " + report.problems());
        assertEquals(12, report.count(CustomerTask.Outcome.SUCCESS));
        assertEquals(report.documentsServed(), report.documentsHeld());
        assertFalse(report.timedOut());
    }

    @Test
    @Timeout(60)
    void simpleConfigWithoutBreaks() throws Exception {
        SimulationConfig config = new SimulationConfig(
                Map.of("A", new SimulationConfig.OfficeSpec("A", "Office A", 2)),
                Map.of("X", new bureaucracy.model.Document("X", "X", List.of(), "A", 20),
                       "Y", new bureaucracy.model.Document("Y", "Y", List.of("X"), "A", 20)));
        SimulationReport report = new Simulation(config,
                Simulation.Options.defaults(20).withoutCoffeeBreaks(), logger).run();

        assertTrue(report.isConsistent(), () -> "problems: " + report.problems());
        // 20 customers x (X + Y)
        assertEquals(40, report.documentsServed());
    }

    @Test
    @Timeout(30)
    void officesAreClosedAfterRun() throws Exception {
        Simulation simulation = new Simulation(defaultConfig(),
                Simulation.Options.defaults(3).withTargets(List.of("TAX_CERT")), logger);
        simulation.run();

        simulation.getOffices().forEach(office ->
                office.getCounters().forEach(counter -> assertFalse(counter.isOpen(), counter.getId())));
    }

    @Test
    @Timeout(30)
    void timeoutIsReportedAsInconsistent() throws Exception {
        SimulationReport report = new Simulation(defaultConfig(),
                Simulation.Options.defaults(4).withTargets(List.of("BUILDING_PERMIT")).withTimeoutMs(50),
                logger).run();

        assertTrue(report.timedOut());
        assertFalse(report.isConsistent());
    }

    @Test
    void cyclicConfigIsRejectedBeforeStarting() {
        SimulationConfig cyclic = new SimulationConfig(
                Map.of("A", new SimulationConfig.OfficeSpec("A", "Office A", 1)),
                Map.of("X", new bureaucracy.model.Document("X", "X", List.of("Y"), "A", 10),
                       "Y", new bureaucracy.model.Document("Y", "Y", List.of("X"), "A", 10)));
        assertThrows(IllegalStateException.class,
                () -> new Simulation(cyclic, Simulation.Options.defaults(1), logger));
    }

    @Test
    void documentFromUnknownOfficeIsRejected() {
        SimulationConfig broken = new SimulationConfig(
                Map.of("A", new SimulationConfig.OfficeSpec("A", "Office A", 1)),
                Map.of("X", new bureaucracy.model.Document("X", "X", List.of(), "NOPE", 10)));
        Simulation simulation = new Simulation(broken, Simulation.Options.defaults(1), logger);
        assertThrows(IllegalArgumentException.class, simulation::run);
    }

    @Test
    void reportFlagsServedTwiceOrLost() {
        SimulationReport report = new SimulationReport(2, 2, Map.of(CustomerTask.Outcome.SUCCESS, 2L),
                10, 5, 15, 0, 5, 4, 0, List.of(), 100, false);
        assertFalse(report.isConsistent());
        assertTrue(report.problems().getFirst().contains("served twice or lost"));
    }
}
