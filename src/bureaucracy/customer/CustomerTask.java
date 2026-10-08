package bureaucracy.customer;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;
import bureaucracy.office.OfficeAPI;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class CustomerTask implements Runnable {

    public CustomerTask(Customer customer, List<Document> plan, Map<String, OfficeAPI> offices, RetryPolicy retryPolicy, SimulationLogger logger, String source) {
        this.customer = customer;
        this.plan = plan;
        this.offices = offices;
        this.retryPolicy = retryPolicy;
        this.logger = logger;
        this.source = source;
        validatePlan();
    }

    @Override
    public void run() {
        outcome = Outcome.RUNNING;
        long start = System.currentTimeMillis();
        logger.info(source, "wants " + plan.stream().map(Document::getName).collect(Collectors.joining(", ")));

        Outcome ticket = Outcome.FAILED;
        try {
            followPlan();
            ticket = Outcome.SUCCESS;
        } catch (DocumentUnavailableException e) {
            logger.error(source, "failed to obtain " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ticket = Outcome.INTERRUPTED;
            logger.warn(source, "interrupted while obtaining documents");
        } finally {
            outcome = ticket;
            elapsedMs = System.currentTimeMillis() - start;
        }
    }

    private void followPlan() throws DocumentUnavailableException, InterruptedException {
        Map<String, Document> pending = new LinkedHashMap<>();
        for (Document document : plan) {
            if(!customer.hasDocument(document.getId())) {
                pending.putIfAbsent(document.getId(), document);
            }
        }

        try (ExecutorService errands = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(source + "-errand-", 0).factory())) {
            CompletionService<String> finished = new ExecutorCompletionService<>(errands);
            int inFlight = 0;

            try {
                while (!pending.isEmpty() || inFlight > 0){
                    Iterator<Document> it = pending.values().iterator();
                    while (it.hasNext()) {
                        Document document = it.next();
                        if(customer.getObtainedDocuments().containsAll(document.getPrerequisites())) {
                            it.remove();
                            finished.submit(() -> fetch(document));
                            inFlight++;
                        }
                    }

                    finished.take().get();
                    inFlight--;
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof DocumentUnavailableException unavailable) {
                    throw unavailable;
                }
                throw new IllegalStateException(source + ": errand crashed", e.getCause());
            } finally {
                errands.shutdownNow();
            }
        }
    }

    public enum Outcome { NOT_STARTED, RUNNING, SUCCESS, FAILED, INTERRUPTED}

    private final Customer customer;
    private final List<Document> plan;
    private final Map<String, OfficeAPI> offices;
    private final RetryPolicy retryPolicy;
    private final SimulationLogger logger;
    private final String source;

    private final AtomicInteger requeueCount = new AtomicInteger();
    private volatile Outcome outcome = Outcome.NOT_STARTED;
    private volatile long elapsedMs;

    private void validatePlan() {
        Set<String> available = new HashSet<>(customer.getObtainedDocuments());
        for (Document document : plan) {
            if (available.contains(document.getId())) continue;

            for (String prerequisite : document.getPrerequisites()) {
                if (!available.contains(prerequisite))
                    throw new IllegalArgumentException("Document " + document.getId() + " requires prerequisite " + prerequisite + " which is not available");
            }

            OfficeAPI office = offices.get(document.getOfficeId());
            if(office == null || !office.canIssue(document.getId())) throw new IllegalArgumentException("Office " + document.getOfficeId() + " cannot issue document " + document.getId());
            available.add(document.getId());
        }

        if (!available.contains(customer.getTargetDocumentId())) throw new IllegalArgumentException(customer+ ": plan never reaches " + customer.getTargetDocumentId());
    }

    public Customer getCustomer() {
        return customer;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public int getRequeueCount() {
        return requeueCount.get();
    }

    private String fetch(Document document) throws InterruptedException, DocumentUnavailableException, ExecutionException {
        OfficeAPI office = offices.get(document.getOfficeId());

        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            if (attempt > 1) {
                long backoff = retryPolicy.backoffMs(attempt - 1);
                requeueCount.incrementAndGet();
                logger.info(source, "re-queues for " + document.getName() + " at " + office.getId()
                        + " in " + backoff + " ms (attempt " + attempt + "/" + retryPolicy.maxAttempts() + ")");
                Thread.sleep(backoff);
            }

            logger.info(source, "queues at " + office.getId() + " for " + document.getName());
            CompletableFuture<Boolean> ticket = office.enqueue(customer, document.getId());

            try {
                if (ticket.get(retryPolicy.attemptTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    customer.addDocument(document.getId());
                    logger.info(source, "obtained ticket for " + document.getName());
                    return document.getId();
                }
                logger.warn(source, "sent away from " + office.getId() + " without " + document.getName());
            } catch (TimeoutException e) {
                ticket.cancel(false);
                logger.warn(source, "waited too long at " + office.getId() + " for " + document.getName()
                        + ", leaving the queue");
            } catch (ExecutionException e) {
                logger.warn(source, office.getId() + " failed to issue " + document.getName() + ": " + e.getCause());
            } catch (InterruptedException e) {
                ticket.cancel(false);
                throw e;
            }
        }
            throw new DocumentUnavailableException(document,
                    "not issued after " + retryPolicy.maxAttempts() + " attempts");
        }
    }