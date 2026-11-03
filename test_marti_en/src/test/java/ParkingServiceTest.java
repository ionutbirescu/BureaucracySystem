import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParkingServiceTest {

    @Mock
    private TicketDatabase ticketDatabase;

    private ParkingService parkingService;

    @BeforeEach
    void setUp() {
        parkingService = new ParkingService();
        parkingService.setTicketDatabase(ticketDatabase);
    }

    // =====================================================================
    // calculateParkingFee  -> pure logic, no mocking needed
    // Technique: equivalence partitioning + boundary value analysis
    // =====================================================================
    @Nested
    @DisplayName("calculateParkingFee")
    class CalculateParkingFeeTests {

        // ---------- invalid inputs ----------

        @Test
        void nullVehicleType_throwsInvalidVehicleException() {
            InvalidVehicleException ex = assertThrows(InvalidVehicleException.class,
                    () -> parkingService.calculateParkingFee(null, 60, false));
            assertEquals("Vehicle type is missing.", ex.getMessage());
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -100, Integer.MIN_VALUE})
        void negativeMinutes_throwsInvalidDurationException(int minutes) {
            InvalidDurationException ex = assertThrows(InvalidDurationException.class,
                    () -> parkingService.calculateParkingFee(VehicleType.CAR, minutes, false));
            assertEquals("Minutes should not be negative.", ex.getMessage());
        }

        @ParameterizedTest
        @ValueSource(ints = {1441, 2000, Integer.MAX_VALUE})
        void moreThanOneDay_throwsInvalidDurationException(int minutes) {
            InvalidDurationException ex = assertThrows(InvalidDurationException.class,
                    () -> parkingService.calculateParkingFee(VehicleType.CAR, minutes, false));
            assertEquals("Minutes cannot be greater than 1440.", ex.getMessage());
        }

        @Test
        void nullVehicleIsCheckedBeforeMinutes() {
            // both inputs invalid -> vehicle check comes first
            assertThrows(InvalidVehicleException.class,
                    () -> parkingService.calculateParkingFee(null, -5, false));
        }

        // ---------- valid inputs ----------

        @ParameterizedTest(name = "{0}, {1} min, weekend={2} -> {3}")
        @CsvSource({
                // free period (0..30) - free even for trucks / weekend
                "CAR,   0,    false, 0",
                "CAR,   1,    false, 0",
                "CAR,   30,   false, 0",
                "TRUCK, 30,   false, 0",
                "CAR,   30,   true,  0",
                "TRUCK, 30,   true,  0",
                // first paid minute -> 1 started hour
                "CAR,   31,   false, 5",
                "CAR,   60,   false, 5",
                // started hour rounding
                "CAR,   61,   false, 10",
                "CAR,   120,  false, 10",
                "CAR,   121,  false, 15",
                // upper boundary (1440 = 24 h)
                "CAR,   1439, false, 120",
                "CAR,   1440, false, 120",
                // truck -> doubled
                "TRUCK, 31,   false, 10",
                "TRUCK, 61,   false, 20",
                "TRUCK, 1440, false, 240",
                // weekend -> +10 (added after doubling)
                "CAR,   31,   true,  15",
                "CAR,   61,   true,  20",
                "TRUCK, 31,   true,  20",
                "TRUCK, 61,   true,  30",
                "TRUCK, 1440, true,  250"
        })
        void validInputs_returnCorrectFee(VehicleType type, int minutes, boolean weekend, double expected) {
            assertEquals(expected, parkingService.calculateParkingFee(type, minutes, weekend), 0.001);
        }

        @Test
        void calculateParkingFee_doesNotTouchDatabase() {
            parkingService.calculateParkingFee(VehicleType.CAR, 100, true);
            verifyNoInteractions(ticketDatabase);
        }
    }

    // =====================================================================
    // exitParking  -> depends on TicketDatabase, so we MOCK it
    // =====================================================================
    @Nested
    @DisplayName("exitParking")
    class ExitParkingTests {

        // ---------- invalid id ----------

        @ParameterizedTest
        @NullAndEmptySource
        void invalidTicketId_throwsAndNeverCallsDatabase(String ticketId) {
            InvalidTicketIdException ex = assertThrows(InvalidTicketIdException.class,
                    () -> parkingService.exitParking(ticketId));
            assertEquals("Invalid ticket id.", ex.getMessage());

            verifyNoInteractions(ticketDatabase);
        }

        // ---------- ticket not found ----------

        @Test
        void ticketNotFound_throwsAndNeverUpdates() {
            when(ticketDatabase.getById("T1")).thenReturn(null);

            TicketNotFoundException ex = assertThrows(TicketNotFoundException.class,
                    () -> parkingService.exitParking("T1"));
            assertEquals("Ticket has not been found.", ex.getMessage());

            verify(ticketDatabase).getById("T1");
            verify(ticketDatabase, never()).update(any());
        }

        // ---------- CLOSED branch ----------

        @Test
        void paidTicket_isClosedAndUpdated() {
            Ticket ticket = new Ticket("T1", TicketStatus.OPEN, 120, true);
            when(ticketDatabase.getById("T1")).thenReturn(ticket);

            parkingService.exitParking("T1");

            assertEquals(TicketStatus.CLOSED, ticket.getStatus());
            verify(ticketDatabase).getById("T1");
            verify(ticketDatabase).update(ticket);
            verifyNoMoreInteractions(ticketDatabase);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 14, 15})
        void unpaidTicket_upTo15Minutes_isClosed(int minutes) {
            Ticket ticket = new Ticket("T1", TicketStatus.OPEN, minutes, false);
            when(ticketDatabase.getById("T1")).thenReturn(ticket);

            parkingService.exitParking("T1");

            assertEquals(TicketStatus.CLOSED, ticket.getStatus());
            verify(ticketDatabase).update(ticket);
        }

        @Test
        void paidTicket_under15Minutes_isClosed() {
            Ticket ticket = new Ticket("T1", TicketStatus.OPEN, 10, true);
            when(ticketDatabase.getById("T1")).thenReturn(ticket);

            parkingService.exitParking("T1");

            assertEquals(TicketStatus.CLOSED, ticket.getStatus());
            verify(ticketDatabase).update(ticket);
        }

        // ---------- UNPAID branch ----------

        @ParameterizedTest
        @ValueSource(ints = {16, 60, 1440})
        void unpaidTicket_over15Minutes_isMarkedUnpaid(int minutes) {
            Ticket ticket = new Ticket("T1", TicketStatus.OPEN, minutes, false);
            when(ticketDatabase.getById("T1")).thenReturn(ticket);

            parkingService.exitParking("T1");

            assertEquals(TicketStatus.UNPAID, ticket.getStatus());
            verify(ticketDatabase).update(ticket);
        }

        // ---------- ArgumentCaptor example ----------

        @Test
        void update_receivesTheSameTicketWithNewStatus() {
            Ticket ticket = new Ticket("ABC", TicketStatus.OPEN, 45, false);
            when(ticketDatabase.getById("ABC")).thenReturn(ticket);

            parkingService.exitParking("ABC");

            ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
            verify(ticketDatabase, times(1)).update(captor.capture());

            Ticket saved = captor.getValue();
            assertSame(ticket, saved);
            assertEquals("ABC", saved.getId());
            assertEquals(TicketStatus.UNPAID, saved.getStatus());
        }

        // ---------- order of calls ----------

        @Test
        void getById_isCalledBeforeUpdate() {
            Ticket ticket = new Ticket("T1", TicketStatus.OPEN, 5, false);
            when(ticketDatabase.getById("T1")).thenReturn(ticket);

            parkingService.exitParking("T1");

            var inOrder = inOrder(ticketDatabase);
            inOrder.verify(ticketDatabase).getById("T1");
            inOrder.verify(ticketDatabase).update(ticket);
        }

        // ---------- database failure propagates ----------

        @Test
        void databaseException_isPropagated() {
            when(ticketDatabase.getById("T1")).thenThrow(new RuntimeException("DB down"));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> parkingService.exitParking("T1"));
            assertEquals("DB down", ex.getMessage());
            verify(ticketDatabase, never()).update(any());
        }
    }
}
