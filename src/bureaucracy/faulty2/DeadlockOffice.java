package bureaucracy.faulty2;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;
import bureaucracy.office.OfficeAPI;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeadlockOffice implements  OfficeAPI {
    // Before issuing a document, the counter needs documentId from office
    public record StampRequirement(OfficeAPI office, String documentId) {}

    private final String id;
    private final String name;
    private final Map<String, Document> issuableDocuments;
    private final List<DeadlockCounter> counters;
    private final SimulationLogger logger;

    public DeadlockOffice(String id, String name, List<DeadlockCounter> counters,
                          Map<String, Document> issuableDocuments, SimulationLogger logger) {
        this.id = id;
        this.name = name;
        this.counters = Collections.unmodifiableList(new ArrayList<>(counters));
        this.issuableDocuments = Collections.unmodifiableMap(new HashMap<>(issuableDocuments));
        this.freeCounters = new Semaphore(counters.size(), true);
        this.logger = logger;
    }

    @Override
    public String getId() { return id; }

    @Override
    public boolean canIssue(String documentId) {
        return issuableDocuments.containsKey(documentId);
    }

    @Override
    public CompletableFuture<Boolean> enqueue(Customer customer, String documentId) {
        Document doc = issuableDocuments.get(documentId);
        if (doc == null) {
            throw new IllegalArgumentException("Document " + documentId + " is not issuable by office" + id);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waitingQueue.offer(new ServiceRequest(customer, doc, future));
        logger.debug(id, customer + " in queue for " + doc.getName() + " (queue: ~" + waitingQueue.size() + ")");
        return future;
    }

    public String getName() { return name; }
    public Map<String, Document> getIssuableDocuments() { return issuableDocuments; }
    public List<DeadlockCounter> getCounters() { return counters; }
    public SimulationLogger getLogger() { return logger; }

    // BUG: circular wait - stamps are not document prerequisites
    // CustomerTask.validatePlan() can't see a cycle like TOWN -> TAX -> TOWN
    private final Map<String, StampRequirement> stampRequirements = new ConcurrentHashMap<>();

    private final BlockingQueue<ServiceRequest> waitingQueue = new LinkedBlockingQueue<>();
    private final Semaphore freeCounters;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread dispatcherThread;

    public void requireStamp(String documentId, OfficeAPI stampOffice, String stampDocumentId) {
        if (!issuableDocuments.containsKey(documentId)) {
            throw new IllegalArgumentException("Document " + documentId + " is not issuable by office" + id);
        }
        stampRequirements.put(documentId, new StampRequirement(stampOffice, stampDocumentId));
    }

    public void start() {
        running.set(true);
        dispatcherThread = new Thread(this::dispatchLoop, "office-"+id+"-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        logger.info(id, name+ " opened with "+counters.size()+" counters");
    }

    public void shutdown() {
        running.set(false);
        if(dispatcherThread != null) {
            dispatcherThread.interrupt();
            counters.forEach(DeadlockCounter::close);
            logger.info(id, name+ " closed");
        }
    }

    private void dispatchLoop(){
        logger.debug(id, "dispatch loop started");
        try {
            while(running.get() || !waitingQueue.isEmpty()) {
                ServiceRequest req = waitingQueue.poll(200, TimeUnit.MILLISECONDS);
                if(req == null) {
                    continue;
                }
                // Once every counter is held by a customer waiting elsewhere,
                // the dispatcher blocks here and the whole office stops
                freeCounters.acquire();
                DeadlockCounter counter = pickFreeCounter();
                if (counter == null) {
                    freeCounters.release();
                    waitingQueue.offer(req);
                    logger.warn(id, "No counter available, customer " + req.customer + " will wait");
                    Thread.sleep(100);
                    continue;
                }

                final DeadlockCounter c = counter;
                StampRequirement stamp = stampRequirements.get(req.document().getId());
                Thread.ofVirtual().name("serve-" + req.customer().getId()).start(() -> {
                    try {
                        boolean served = c.serve(req.customer(), req.document(), stamp);
                        if (served) {
                            req.future().complete(true);
                        } else {
                            waitingQueue.offer(req);
                            logger.warn(id, req.customer() + " re-queue (counter unavailable)");
                        }
                    }catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        req.future().complete(false);
                    } catch (ExecutionException e) {
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
        logger.info(id, "Dispatcher stopped");
    }

    private DeadlockCounter pickFreeCounter() {
        for (DeadlockCounter c: counters) {
            if (c.isAvailable()) return c;
        }
        return null;
    }

    private record ServiceRequest(
            Customer customer,
            Document document,
            CompletableFuture<Boolean> future) {}
}
