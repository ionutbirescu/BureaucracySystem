package bureaucracy.logging;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SimulationLogger {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final String POISON = "__SHUTDOWN__";

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writerThread;
    private final PrintStream out;

    public SimulationLogger(PrintStream out) {
        this.out = out;
        this.writerThread = new Thread(this::drainLoop, "logger-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    public SimulationLogger() {
        this(System.out);
    }

    public void log(String level, String source, String message) {
        if (!running.get()) return;
        String entry = String.format("[%s] [%-5s] [%s] %s",
                LocalTime.now().format(TIME_FMT), level, source, message);
        queue.offer(entry);
    }

    public void info(String source, String message)  { log("INFO",  source, message); }
    public void warn(String source, String message)  { log("WARN",  source, message); }
    public void error(String source, String message) { log("ERROR", source, message); }
    public void debug(String source, String message) { log("DEBUG", source, message); }

    public void shutdown() {
        running.set(false);
        queue.offer(POISON);
        try {
            writerThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainLoop() {
        try {
            while (true) {
                String entry = queue.take();
                if (POISON.equals(entry)) {
                    String leftover;
                    while ((leftover = queue.poll()) != null) {
                        if (!POISON.equals(leftover)) out.println(leftover);
                    }
                    return;
                }
                out.println(entry);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}