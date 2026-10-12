package bureaucracy.menu;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal menu that starts each case of the project.
 *
 * Every case runs in its own JVM, like in the Harness: most of them end with System.exit, and
 * Faulty 2 leaves its deadlocked threads stuck on purpose, so running them inside this JVM would
 * close the menu or leave stuck threads behind. The child JVM writes straight to this console.
 *
 * Usage: MainMenu   (from the project root, so config/simulation.properties is found)
 */
public final class MainMenu {

    /** Asks the user for the command-line arguments of a case. */
    @FunctionalInterface
    private interface ArgsPrompt {
        List<String> ask(BufferedReader in) throws IOException;
    }

    private record Case(String title, String description, String mainClass, ArgsPrompt args) {}

    private static final String DEFAULT_CONFIG = "config/simulation.properties";
    private static final int DEFAULT_CUSTOMERS = 10;

    private static final List<Case> CASES = List.of(
            new Case("Correct version", "all customers served, nobody twice or lost",
                    "bureaucracy.simulation.SimulationMain", in -> List.of(askConfig(in), askCustomers(in))),
            new Case("Faulty 1 - data race", "unsynchronized queue: lost / duplicate customers",
                    "bureaucracy.faulty1.Faulty1Demo", in -> List.of()),
            new Case("Faulty 2 - deadlock", "two customers hold a counter, wait for each other",
                    "bureaucracy.faulty2.DeadlockDemo", in -> List.of()),
            new Case("Faulty 2 - fixed", "same scenario, stamp fetched first: no deadlock",
                    "bureaucracy.faulty2.DeadlockDemo", in -> List.of("--fixed")),
            new Case("Coffee breaks", "a counter takes rapid breaks, nobody is dropped",
                    "bureaucracy.office.CoffeeBreakDemo", in -> List.of()),
            new Case("Customers", "customers follow their document plan (stub offices)",
                    "bureaucracy.customer.CustomerDemo", in -> List.of()),
            new Case("Harness", "runs versions 1-4 and prints a summary table",
                    "bureaucracy.harness.Harness", in -> List.of(askCustomers(in))));

    private MainMenu() {}

    static void main(String[] args) throws InterruptedException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        try {
            while (true) {
                printMenu();
                String choice = readLine(in, "choose [0-" + CASES.size() + "]: ");
                if (choice.equals("0") || choice.equalsIgnoreCase("q")) {
                    return;
                }
                if (choice.isEmpty()) {
                    continue;
                }
                Case selected = parseChoice(choice);
                if (selected == null) {
                    System.out.println("unknown option: " + choice);
                    continue;
                }
                run(selected, selected.args().ask(in));
                readLine(in, "press Enter to return to the menu ");
            }
        } catch (EOFException e) {
            // stdin closed (Ctrl+Z / Ctrl+D): leave quietly
        } catch (IOException e) {
            System.err.println("cannot read the console: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("=".repeat(76));
        System.out.println(" BUREAUCRACY MANAGER - choose a case to run");
        System.out.println("=".repeat(76));
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            System.out.printf(" %d) %-21s %s%n", i + 1, c.title(), c.description());
        }
        System.out.println(" 0) Exit");
    }

    private static Case parseChoice(String choice) {
        try {
            int index = Integer.parseInt(choice);
            return index >= 1 && index <= CASES.size() ? CASES.get(index - 1) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void run(Case selected, List<String> args) throws InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(selected.mainClass());
        command.addAll(args);

        System.out.println(">>> " + selected.title() + ": " + selected.mainClass() + " " + String.join(" ", args));
        long start = System.currentTimeMillis();
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            int exitCode = process.waitFor();
            System.out.printf("<<< %s finished in %d ms, exit code %d%n",
                    selected.title(), System.currentTimeMillis() - start, exitCode);
        } catch (IOException e) {
            System.out.println("cannot start " + selected.mainClass() + ": " + e.getMessage());
        }
    }

    private static String askConfig(BufferedReader in) throws IOException {
        while (true) {
            String answer = readLine(in, "config file [" + DEFAULT_CONFIG + "]: ");
            String config = answer.isEmpty() ? DEFAULT_CONFIG : answer;
            try {
                if (Files.isRegularFile(Path.of(config))) {
                    return config;
                }
            } catch (InvalidPathException e) {
                // reported below like a missing file
            }
            System.out.println("no such file: " + config + " (start the menu from the project root)");
        }
    }

    private static String askCustomers(BufferedReader in) throws IOException {
        while (true) {
            String answer = readLine(in, "number of customers [" + DEFAULT_CUSTOMERS + "]: ");
            if (answer.isEmpty()) {
                return Integer.toString(DEFAULT_CUSTOMERS);
            }
            try {
                if (Integer.parseInt(answer) > 0) {
                    return answer;
                }
            } catch (NumberFormatException e) {
                // reported below like a non-positive number
            }
            System.out.println("please enter a positive number");
        }
    }

    private static String readLine(BufferedReader in, String prompt) throws IOException {
        System.out.print(prompt);
        System.out.flush();
        String line = in.readLine();
        if (line == null) {
            throw new EOFException();
        }
        return line.strip();
    }
}
