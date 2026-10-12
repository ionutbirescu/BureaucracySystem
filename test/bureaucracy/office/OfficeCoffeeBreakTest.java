package bureaucracy.office;

import bureaucracy.logging.SimulationLogger;
import bureaucracy.model.Customer;
import bureaucracy.model.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration test of the correct office: customers are still served while counters take breaks. */
class OfficeCoffeeBreakTest {

    private SimulationLogger logger;

    @BeforeEach
    void quietLogger() {
        logger = new SimulationLogger(new PrintStream(OutputStream.nullOutputStream()));
    }

    @AfterEach
    void stopLogger() {
        logger.shutdown();
    }

    @Test
    @Timeout(30)
    void everyoneIsServedOnceDespiteCoffeeBreaks() throws Exception {
        Document stamp = new Document("STAMP", "Stamp", List.of(), "POP", 20);
        Counter first = new Counter("POP-1", "POP", logger);
        Counter second = new Counter("POP-2", "POP", logger);
        Office office = new Office("POP", "Population office", List.of(first, second),
                Map.of(stamp.getId(), stamp), logger);
        office.start();

        // Both counters take a break, one after the other, while the queue is full
        Thread breaks = Thread.ofPlatform().start(() -> {
            try {
                first.startBreak(300);
                second.startBreak(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        int customers = 30;
        List<CompletableFuture<Boolean>> tickets = new ArrayList<>();
        for (int i = 1; i <= customers; i++) {
            tickets.add(office.enqueue(new Customer(i, stamp.getId()), stamp.getId()));
        }
        for (CompletableFuture<Boolean> ticket : tickets) {
            assertTrue(ticket.get(20, TimeUnit.SECONDS));
        }
        breaks.join();
        office.shutdown();

        assertEquals(customers, first.getCustomersServed() + second.getCustomersServed(),
                "served must equal arrived: nobody lost, nobody served twice");
    }
}
