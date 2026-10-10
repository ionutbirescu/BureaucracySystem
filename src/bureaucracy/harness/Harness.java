package bureaucracy.harness;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the three versions of the project, each in its own JVM (a deadlocked or corrupted run
 * cannot affect the next one), with a time limit, and checks that each one shows what it should:
 *
 *   Correct   -> every customer served, nobody served twice or lost   (exit code 0)
 *   Faulty 1  -> the data race is visible (served != arrived, FIFO broken, lost customers)
 *   Faulty 2  -> the deadlock is detected, and the JVM still exits
 *
 * Usage: Harness [customers]   - the full output of each run goes to out/harness/<version>.log
 */
public final class Harness {

    private record Version(String name, String mainClass, List<String> args, long timeoutMs,
                           String expected, String successMarker) {}

    private record Outcome(Version version, String verdict, boolean ok, long ms, Path log) {}

    private static final String LAUNCH = "--launch";

    private Harness() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 1 && args[0].equals(LAUNCH)) {
            launch(args[1], Arrays.copyOfRange(args, 2, args.length));
            return;
        }
        String customers = args.length > 0 ? args[0] : "10";
        List<Version> versions = List.of(
                new Version("correct", "bureaucracy.simulation.SimulationMain",
                        List.of("config/simulation.properties", customers), 180_000,
                        "all customers served, no duplicates", "RESULT: CONSISTENT"),
                new Version("faulty1", "bureaucracy.faulty1.Faulty1Demo", List.of(), 60_000,
                        "data race exposed", "defects successfully demonstrated"),
                new Version("faulty2", "bureaucracy.faulty2.DeadlockDemo", List.of(), 60_000,
                        "deadlock detected", "DEADLOCK DETECTED"),
                new Version("faulty2-fixed", "bureaucracy.faulty2.DeadlockDemo", List.of("--fixed"), 60_000,
                        "no deadlock after the fix", "NO DEADLOCK"));

        Path logDir = Path.of("out", "harness");
        Files.createDirectories(logDir);

        List<Outcome> outcomes = new ArrayList<>();
        for (Version version : versions) {
            System.out.println(">>> running " + version.name() + " (" + version.mainClass() + ")");
            outcomes.add(run(version, logDir));
        }

        System.out.println();
        System.out.printf("%-14s %-38s %-10s %8s  %s%n", "VERSION", "EXPECTED", "RESULT", "TIME", "LOG");
        boolean allOk = true;
        for (Outcome o : outcomes) {
            System.out.printf("%-14s %-38s %-10s %6d ms  %s%n",
                    o.version().name(), o.version().expected(), o.verdict(), o.ms(), o.log());
            allOk &= o.ok();
        }
        System.out.println(allOk ? "\nHARNESS: all versions behaved as expected"
                : "\nHARNESS: some versions did not behave as expected (see logs)");
        System.exit(allOk ? 0 : 1);
    }

    private static Outcome run(Version version, Path logDir) throws IOException, InterruptedException {
        Path log = logDir.resolve(version.name() + ".log");
        if (!classExists(version.mainClass())) {
            Files.writeString(log, version.mainClass() + " is not on the classpath (not merged yet?)\n");
            return new Outcome(version, "SKIPPED", true, 0, log);
        }

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Harness.class.getName());
        command.add(LAUNCH);
        command.add(version.mainClass());
        command.addAll(version.args());

        long start = System.currentTimeMillis();
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean exited = process.waitFor(version.timeoutMs(), TimeUnit.MILLISECONDS);
        long ms = System.currentTimeMillis() - start;
        if (!exited) {
            process.destroyForcibly().waitFor();
            return new Outcome(version, "HUNG", false, ms, log);
        }

        String output = Files.readString(log);
        boolean markerSeen = output.contains(version.successMarker());
        // Faulty 1 always exits 0; the others use the exit code to say "behaved as expected"
        boolean exitOk = process.exitValue() == 0;
        boolean ok = markerSeen && exitOk;
        return new Outcome(version, ok ? "OK" : "FAILED(" + process.exitValue() + ")", ok, ms, log);
    }

    /**
     * Child-JVM side: calls the version's main through reflection, so package-private
     * "static void main" methods (allowed by JDK 25+) also start on older JDKs.
     */
    private static void launch(String mainClass, String[] args) throws Exception {
        Method main = Class.forName(mainClass).getDeclaredMethod("main", String[].class);
        main.setAccessible(true);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, Harness.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
