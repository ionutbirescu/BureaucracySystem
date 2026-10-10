package bureaucracy.diagnostics;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Deadlock detector with three independent signals, from the JVM's view to the domain's view:
 *
 * 1. ThreadMXBean.findDeadlockedThreads() - what jstack prints as "Found one Java-level deadlock".
 *    It only sees cycles of monitors (synchronized) and ownable synchronizers (ReentrantLock...).
 * 2. A wait-for graph built from the domain ("customer A waits for customer B") - this sees
 *    deadlocks made of Semaphores, queues and futures, which the JVM cannot attribute to an owner.
 * 3. No progress: the progress counter did not move during the whole observation window.
 *
 * A deadlock is reported when the JVM finds a lock cycle, or when the wait-for graph has a cycle
 * AND nothing progressed for the whole window (so a short, transient cycle is not a false alarm).
 */
public final class DeadlockDetector {

    public record Report(boolean deadlocked,
                         List<String> waitForCycle,
                         List<String> jvmDeadlockedThreads,
                         long progressAtStart,
                         long progressAtEnd,
                         String threadDump) {

        public boolean jvmSawIt() {
            return !jvmDeadlockedThreads.isEmpty();
        }
    }

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final Supplier<WaitForGraph> graphSupplier;
    private final LongSupplier progress;
    private final List<String> threadNamePrefixes;

    /**
     * @param graphSupplier      builds a fresh wait-for graph from the current state of the system
     * @param progress           monotonic counter of useful work (e.g. customers served)
     * @param threadNamePrefixes threads to include in the jstack-like dump of the report
     */
    public DeadlockDetector(Supplier<WaitForGraph> graphSupplier, LongSupplier progress,
                            List<String> threadNamePrefixes) {
        this.graphSupplier = graphSupplier;
        this.progress = progress;
        this.threadNamePrefixes = List.copyOf(threadNamePrefixes);
    }

    /** Observes the system for windowMs, sampling every periodMs, and reports what it saw. */
    public Report observe(long windowMs, long periodMs) throws InterruptedException {
        long before = progress.getAsLong();
        Sampler sampler = new Sampler();
        boolean jvmFoundOne;
        try (ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deadlock-detector");
            t.setDaemon(true);
            return t;
        })) {
            timer.scheduleAtFixedRate(sampler, 0, periodMs, TimeUnit.MILLISECONDS);
            // returns early only if the JVM itself found a lock cycle
            jvmFoundOne = sampler.jvmFoundOne.await(windowMs, TimeUnit.MILLISECONDS);
            timer.shutdownNow();
        } // close() waits for the sampler to stop, so its fields are safe to read below

        long after = progress.getAsLong();
        boolean stalled = after == before;
        boolean deadlocked = jvmFoundOne || !sampler.jvmThreads.isEmpty()
                || (sampler.cycleEverySample && !sampler.cycle.isEmpty() && stalled);
        return new Report(deadlocked, sampler.cycle, sampler.jvmThreads, before, after,
                deadlocked ? threadDump() : "");
    }

    /** One sample per tick; runs on the single timer thread, read after the timer has terminated. */
    private final class Sampler implements Runnable {
        final CountDownLatch jvmFoundOne = new CountDownLatch(1);
        volatile boolean cycleEverySample = true;
        volatile List<String> cycle = List.of();
        volatile List<String> jvmThreads = List.of();

        @Override
        public void run() {
            List<String> found = jvmDeadlockedThreads();
            Optional<List<String>> graphCycle = graphSupplier.get().findCycle();
            cycle = graphCycle.orElse(List.of());
            if (graphCycle.isEmpty()) {
                cycleEverySample = false;
            }
            if (!found.isEmpty()) {
                jvmThreads = found;
                jvmFoundOne.countDown();
            }
        }
    }

    /** Watched threads the JVM itself considers deadlocked (empty if none). */
    public List<String> jvmDeadlockedThreads() {
        long[] ids = threads.findDeadlockedThreads();
        if (ids == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (ThreadInfo info : threads.getThreadInfo(ids, true, true)) {
            // the JVM reports every deadlock in the process; only ours count
            if (info != null && matches(info.getThreadName())) {
                names.add(info.getThreadName() + " waiting for " + info.getLockName()
                        + " held by " + info.getLockOwnerName());
            }
        }
        return names;
    }

    /**
     * jstack-like dump of the interesting threads. ThreadMXBean only knows platform threads;
     * the virtual threads (where the counters are actually held) come from the HotSpot
     * thread dump, the same one "jcmd PID Thread.dump_to_file" produces.
     */
    public String threadDump() {
        StringBuilder out = new StringBuilder();
        for (ThreadInfo info : threads.dumpAllThreads(true, true)) {
            if (matches(info.getThreadName())) {
                out.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
                if (info.getLockName() != null) {
                    out.append(" on ").append(info.getLockName());
                }
                out.append('\n');
                interesting(Arrays.stream(info.getStackTrace()).map(String::valueOf).toList())
                        .forEach(frame -> out.append("    at ").append(frame).append('\n'));
            }
        }
        for (Map.Entry<String, List<String>> vt : virtualThreadStacks().entrySet()) {
            out.append('"').append(vt.getKey()).append("\" virtual\n");
            interesting(vt.getValue()).forEach(frame -> out.append("    at ").append(frame).append('\n'));
        }
        return out.toString();
    }

    private Map<String, List<String>> virtualThreadStacks() {
        Map<String, List<String>> stacks = new LinkedHashMap<>();
        Path file = null;
        try {
            file = Files.createTempFile("bureaucracy-threads-", ".txt");
            Files.delete(file); // dumpThreads refuses to overwrite an existing file
            HotSpotDiagnosticMXBean hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            hotspot.dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);

            String current = null;
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                // thread header: #123 "name" virtual [STATE timestamp]  (the tail varies by JDK)
                if (trimmed.startsWith("#") && trimmed.contains("\"")) {
                    int first = trimmed.indexOf('"');
                    int last = trimmed.lastIndexOf('"');
                    String name = last > first ? trimmed.substring(first + 1, last) : "";
                    boolean virtual = trimmed.substring(last + 1).trim().startsWith("virtual");
                    current = virtual && matches(name) ? name : null;
                    if (current != null) {
                        stacks.put(current, new ArrayList<>());
                    }
                } else if (current != null && !trimmed.isEmpty()) {
                    stacks.get(current).add(trimmed);
                } else if (trimmed.isEmpty()) {
                    current = null;
                }
            }
        } catch (IOException | RuntimeException e) {
            stacks.put("(virtual thread dump unavailable: " + e.getMessage() + ")", List.of());
        } finally {
            if (file != null) {
                try {
                    if (Files.exists(file)) {
                        Files.delete(file);
                    }
                } catch (IOException ignored) {
                    // a leftover temp file is harmless
                }
            }
        }
        return stacks;
    }

    /** The frame the thread is parked in, followed by the project's own frames (skips JDK plumbing). */
    private static List<String> interesting(List<String> frames) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            String frame = frames.get(i);
            if (frame.contains("bureaucracy.")
                    || (i + 1 < frames.size() && frames.get(i + 1).contains("bureaucracy."))) {
                kept.add(frame);
            }
        }
        return kept.isEmpty() ? frames.stream().limit(5).toList() : kept.stream().limit(6).toList();
    }

    private boolean matches(String threadName) {
        return threadNamePrefixes.stream().anyMatch(threadName::startsWith);
    }

    /** Directed "waits for" graph: an edge A -> B means A cannot continue until B does something. */
    public static final class WaitForGraph {
        private final Map<String, Set<String>> edges = new LinkedHashMap<>();

        public WaitForGraph addEdge(String waiter, String holder) {
            edges.computeIfAbsent(waiter, k -> new LinkedHashSet<>()).add(holder);
            edges.computeIfAbsent(holder, k -> new LinkedHashSet<>());
            return this;
        }

        public Map<String, Set<String>> edges() {
            return Collections.unmodifiableMap(edges);
        }

        /** Returns one cycle as [A, B, ..., A], or empty if the graph is acyclic. */
        public Optional<List<String>> findCycle() {
            Map<String, Integer> state = new LinkedHashMap<>(); // 1 = on stack, 2 = done
            List<String> path = new ArrayList<>();
            for (String node : edges.keySet()) {
                if (!state.containsKey(node)) {
                    Optional<List<String>> cycle = dfs(node, state, path);
                    if (cycle.isPresent()) {
                        return cycle;
                    }
                }
            }
            return Optional.empty();
        }

        private Optional<List<String>> dfs(String node, Map<String, Integer> state, List<String> path) {
            state.put(node, 1);
            path.add(node);
            for (String next : edges.getOrDefault(node, Set.of())) {
                Integer s = state.get(next);
                if (s == null) {
                    Optional<List<String>> cycle = dfs(next, state, path);
                    if (cycle.isPresent()) {
                        return cycle;
                    }
                } else if (s == 1) {
                    List<String> cycle = new ArrayList<>(path.subList(path.indexOf(next), path.size()));
                    cycle.add(next);
                    return Optional.of(cycle);
                }
            }
            path.remove(path.size() - 1);
            state.put(node, 2);
            return Optional.empty();
        }
    }
}
