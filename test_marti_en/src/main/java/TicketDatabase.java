public interface TicketDatabase {
    Ticket getById(String ticketId);
    void update(Ticket ticket);
}
