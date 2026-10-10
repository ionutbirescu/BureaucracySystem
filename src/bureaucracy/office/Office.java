package bureaucracy.office;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Office implements OfficeAPI {

    private final String id;
    private final String name;
    private final Map<String, Document> issuableDocuments;
    private final List<Counter> counters;
    private final SimulationLogger logger;

    private final BlockingQueue<ServiceRequest> waitingQueue = new LinkedBlockingQueue<>();
    private final Semaphore freeCounters;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread dispatcherThread;

    public Office(String id, String name, List<Counter> counters,
                  Map<String, Document> issuableDocuments, SimulationLogger logger) {
        this.id = id;
        this.name = name;
        this.counters = Collections.unmodifiableList(new ArrayList<>(counters));
        this.issuableDocuments = Collections.unmodifiableMap(new HashMap<>(issuableDocuments));
        this.freeCounters = new Semaphore(counters.size(), true);
        this.logger = logger;
    }

    public void start() {
        running.set(true);
        dispatcherThread = new Thread(this::dispatchLoop, "office-" + id + "-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        logger.info(id, name + " opened with " + counters.size() + " counter(s)");
    }

    public void shutdown() {
        running.set(false);
        if (dispatcherThread != null) dispatcherThread.interrupt();
        counters.forEach(Counter::close);
        logger.info(id, name + " closed");
    }

    @Override
    public CompletableFuture<Boolean> enqueue(Customer customer, String documentId) {
        Document doc = issuableDocuments.get(documentId);
        if (doc == null) {
            throw new IllegalArgumentException(
                    "Office " + id + " can't issue document " + documentId);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waitingQueue.offer(new ServiceRequest(customer, doc, future));
        logger.debug(id, customer + " in queue for " + doc.getName()
                + " (queue: ~" + waitingQueue.size() + ")");
        return future;
    }

    @Override
    public boolean canIssue(String documentId) {
        return issuableDocuments.containsKey(documentId);
    }

    private void dispatchLoop() {
        logger.debug(id, "Dispacher started");
        try {
            while (running.get() || !waitingQueue.isEmpty()) {
                ServiceRequest req = waitingQueue.poll(200, TimeUnit.MILLISECONDS);
                if (req == null) continue;

                freeCounters.acquire();

                Counter counter = pickFreeCounter();
                if (counter == null) {
                    freeCounters.release();
                    waitingQueue.offer(req);
                    logger.warn(id, "No counter available for "
                            + req.customer() + ", re-queue");
                    Thread.sleep(100);
                    continue;
                }

                final Counter c = counter;
                Thread.ofVirtual().name("serve-" + req.customer().getId()).start(() -> {
                    try {
                        boolean served = c.serve(req.customer(), req.document());
                        if (served) {
                            req.future().complete(true);
                        } else {
                            waitingQueue.offer(req);
                            logger.warn(id, req.customer() + " re-queue (counter unavailable)");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        req.future().complete(false);
                    } finally {
                        freeCounters.release();
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ServiceRequest leftover;
        while ((leftover = waitingQueue.poll()) != null) {
            leftover.future().complete(false);
        }
        logger.debug(id, "Dispatcher stopped");
    }

    private Counter pickFreeCounter() {
        for (Counter c : counters) {
            if (c.isAvailable()) return c;
        }
        return null;
    }

    @Override
    public String getId()              { return id; }
    public String getName()            { return name; }
    public List<Counter> getCounters() { return counters; }
    public int getQueueLength()        { return waitingQueue.size(); }

    public void printStats() {
        logger.info(id, "=== " + name + " statistics ===");
        for (Counter c : counters) {
            logger.info(id, "  " + c.getId()
                    + ": served=" + c.getCustomersServed()
                    + " total time=" + c.getTotalServiceTimeMs() + "ms");
        }
    }

    private record ServiceRequest(
            Customer customer,
            Document document,
            CompletableFuture<Boolean> future) {}
}