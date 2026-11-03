import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    // calculateParkingFee - exceptions
    // =====================================================================

    @Test
    void calculateParkingFee_nullVehicle_throwsException() {
        InvalidVehicleException ex = assertThrows(InvalidVehicleException.class,
                () -> parkingService.calculateParkingFee(null, 60, false));
        assertEquals("Vehicle type is missing.", ex.getMessage());
    }

    @Test
    void calculateParkingFee_negativeMinutes_throwsException() {
        InvalidDurationException ex = assertThrows(InvalidDurationException.class,
                () -> parkingService.calculateParkingFee(VehicleType.CAR, -1, false));
        assertEquals("Minutes should not be negative.", ex.getMessage());
    }

    @Test
    void calculateParkingFee_moreThan1440Minutes_throwsException() {
        InvalidDurationException ex = assertThrows(InvalidDurationException.class,
                () -> parkingService.calculateParkingFee(VehicleType.CAR, 1441, false));
        assertEquals("Minutes cannot be greater than 1440.", ex.getMessage());
    }

    // =====================================================================
    // calculateParkingFee - free period (0..30 minutes)
    // =====================================================================

    @Test
    void calculateParkingFee_0Minutes_isFree() {
        assertEquals(0, parkingService.calculateParkingFee(VehicleType.CAR, 0, false));
    }

    @Test
    void calculateParkingFee_30Minutes_isFree() {
        assertEquals(0, parkingService.calculateParkingFee(VehicleType.CAR, 30, false));
    }

    @Test
    void calculateParkingFee_30MinutesTruckOnWeekend_isStillFree() {
        assertEquals(0, parkingService.calculateParkingFee(VehicleType.TRUCK, 30, true));
    }

    // =====================================================================
    // calculateParkingFee - car, not weekend
    // =====================================================================

    @Test
    void calculateParkingFee_car31Minutes_costs5() {
        assertEquals(5, parkingService.calculateParkingFee(VehicleType.CAR, 31, false));
    }

    @Test
    void calculateParkingFee_car60Minutes_costs5() {
        assertEquals(5, parkingService.calculateParkingFee(VehicleType.CAR, 60, false));
    }

    @Test
    void calculateParkingFee_car61Minutes_costs10() {
        assertEquals(10, parkingService.calculateParkingFee(VehicleType.CAR, 61, false));
    }

    @Test
    void calculateParkingFee_car1440Minutes_costs120() {
        assertEquals(120, parkingService.calculateParkingFee(VehicleType.CAR, 1440, false));
    }

    // =====================================================================
    // calculateParkingFee - truck (price doubled)
    // =====================================================================

    @Test
    void calculateParkingFee_truck61Minutes_costs20() {
        assertEquals(20, parkingService.calculateParkingFee(VehicleType.TRUCK, 61, false));
    }

    // =====================================================================
    // calculateParkingFee - weekend (+10)
    // =====================================================================

    @Test
    void calculateParkingFee_car61MinutesWeekend_costs20() {
        assertEquals(20, parkingService.calculateParkingFee(VehicleType.CAR, 61, true));
    }

    @Test
    void calculateParkingFee_truck61MinutesWeekend_costs30() {
        // 2 hours * 5 = 10, doubled = 20, +10 weekend = 30
        assertEquals(30, parkingService.calculateParkingFee(VehicleType.TRUCK, 61, true));
    }

    // =====================================================================
    // exitParking - invalid ticket id (database must NOT be called)
    // =====================================================================

    @Test
    void exitParking_nullId_throwsException() {
        InvalidTicketIdException ex = assertThrows(InvalidTicketIdException.class,
                () -> parkingService.exitParking(null));
        assertEquals("Invalid ticket id.", ex.getMessage());

        verifyNoInteractions(ticketDatabase);
    }

    @Test
    void exitParking_emptyId_throwsException() {
        assertThrows(InvalidTicketIdException.class,
                () -> parkingService.exitParking(""));

        verifyNoInteractions(ticketDatabase);
    }

    // =====================================================================
    // exitParking - ticket not found
    // =====================================================================

    @Test
    void exitParking_ticketNotFound_throwsException() {
        when(ticketDatabase.getById("T1")).thenReturn(null);

        TicketNotFoundException ex = assertThrows(TicketNotFoundException.class,
                () -> parkingService.exitParking("T1"));
        assertEquals("Ticket has not been found.", ex.getMessage());

        verify(ticketDatabase).getById("T1");
        verify(ticketDatabase, never()).update(any());
    }

    // =====================================================================
    // exitParking - ticket becomes CLOSED
    // =====================================================================

    @Test
    void exitParking_paidTicket_isClosed() {
        Ticket ticket = new Ticket("T1", TicketStatus.OPEN, 120, true);
        when(ticketDatabase.getById("T1")).thenReturn(ticket);

        parkingService.exitParking("T1");

        assertEquals(TicketStatus.CLOSED, ticket.getStatus());
        verify(ticketDatabase).update(ticket);
    }

    @Test
    void exitParking_unpaidTicket15Minutes_isClosed() {
        Ticket ticket = new Ticket("T1", TicketStatus.OPEN, 15, false);
        when(ticketDatabase.getById("T1")).thenReturn(ticket);

        parkingService.exitParking("T1");

        assertEquals(TicketStatus.CLOSED, ticket.getStatus());
        verify(ticketDatabase).update(ticket);
    }

    // =====================================================================
    // exitParking - ticket becomes UNPAID
    // =====================================================================

    @Test
    void exitParking_unpaidTicket16Minutes_isUnpaid() {
        Ticket ticket = new Ticket("T1", TicketStatus.OPEN, 16, false);
        when(ticketDatabase.getById("T1")).thenReturn(ticket);

        parkingService.exitParking("T1");

        assertEquals(TicketStatus.UNPAID, ticket.getStatus());
        verify(ticketDatabase).update(ticket);
    }

    @Test
    void exitParking_unpaidTicket60Minutes_isUnpaid() {
        Ticket ticket = new Ticket("T1", TicketStatus.OPEN, 60, false);
        when(ticketDatabase.getById("T1")).thenReturn(ticket);

        parkingService.exitParking("T1");

        assertEquals(TicketStatus.UNPAID, ticket.getStatus());
        verify(ticketDatabase, times(1)).getById("T1");
        verify(ticketDatabase, times(1)).update(ticket);
    }
}
