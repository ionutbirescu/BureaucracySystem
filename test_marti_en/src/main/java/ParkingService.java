public class ParkingService {
    private TicketDatabase ticketDatabase;

    public void setTicketDatabase(TicketDatabase ticketDatabase) {
        this.ticketDatabase = ticketDatabase;
    }

    /**
     * Implement a method that returns the parking fee for a vehicle, given how long it was parked.
     * - If the vehicle type is missing (null), throw an appropriate exception
     * - If the number of minutes is negative, throw an appropriate exception
     * - If the number of minutes is greater than 1440 (one day), throw an appropriate exception
     * - The first 30 minutes are free (30 minutes or less means a fee of 0)
     * - Otherwise, every started hour costs 5 (e.g. 61 minutes = 2 hours = 10)
     * - If the vehicle is a truck, the price is doubled
     * - If it is weekend, an extra 10 is added to the final calculated price
     *
     * @param vehicleType The type of the vehicle, either car or truck
     * @param minutes     The number of minutes the vehicle was parked
     * @param isWeekend   Whether the vehicle was parked during the weekend
     * @return The parking fee
     */
    public double calculateParkingFee(VehicleType vehicleType, int minutes, boolean isWeekend) {
        if (vehicleType == null) {
            throw new InvalidVehicleException("Vehicle type is missing.");
        }

        if (minutes < 0) {
            throw new InvalidDurationException("Minutes should not be negative.");
        }

        if (minutes > 1440) {
            throw new InvalidDurationException("Minutes cannot be greater than 1440.");
        }

        if (minutes <= 30) {
            return 0;
        }

        int hours = (minutes + 59) / 60;
        double price = hours * 5;

        if (vehicleType.equals(VehicleType.TRUCK)) {
            price *= 2;
        }

        if (isWeekend) {
            price += 10;
        }

        return price;
    }

    public void exitParking(String ticketId) {
        if (ticketId == null || ticketId.isEmpty()) {
            throw new InvalidTicketIdException("Invalid ticket id.");
        }

        Ticket ticket = ticketDatabase.getById(ticketId);
        if (ticket == null) {
            throw new TicketNotFoundException("Ticket has not been found.");
        }

        if (ticket.isPaid() || ticket.getMinutesParked() <= 15) {
            ticket.setStatus(TicketStatus.CLOSED);
        } else {
            ticket.setStatus(TicketStatus.UNPAID);
        }

        ticketDatabase.update(ticket);
    }
}
