package bureaucracy.simulation;

import bureaucracy.config.ConfigLoader;
import bureaucracy.config.SimulationConfig;
import bureaucracy.logging.SimulationLogger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point of the correct solution.
 *
 * Usage: SimulationMain [configFile] [customers]
 *   configFile defaults to config/simulation.properties, customers to 10.
 * Exit code 0 = consistent run, 1 = an invariant was broken, 2 = bad arguments/config.
 */
public final class SimulationMain {

    private SimulationMain() {}

    public static void main(String[] args) throws InterruptedException {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config/simulation.properties");
        int customers;
        SimulationConfig config;
        try {
            customers = args.length > 1 ? Integer.parseInt(args[1]) : 10;
            config = ConfigLoader.load(configPath);
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot start: " + e.getMessage());
            System.err.println("Usage: SimulationMain [configFile] [customers]");
            System.exit(2);
            return;
        }

        SimulationLogger logger = new SimulationLogger();
        logger.info("Main", "config " + configPath + ": " + config.offices().size() + " office(s), "
                + config.documents().size() + " document(s)");

        Simulation simulation;
        try {
            simulation = new Simulation(config, Simulation.Options.defaults(customers), logger);
        } catch (RuntimeException e) {
            logger.error("Main", "invalid configuration: " + e.getMessage());
            logger.shutdown();
            System.exit(2);
            return;
        }

        // Ctrl+C: interrupt the customers and let run() close the offices before the JVM halts
        Thread hook = new Thread(() -> {
            try {
                simulation.stop(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);

        SimulationReport report = simulation.run();
        report.print(logger);
        logger.shutdown();

        Runtime.getRuntime().removeShutdownHook(hook);
        System.exit(report.isConsistent() ? 0 : 1);
    }
}
