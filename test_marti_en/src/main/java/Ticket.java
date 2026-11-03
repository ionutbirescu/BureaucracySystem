public class Ticket {
    private String id;
    private TicketStatus status;
    private int minutesParked;
    private boolean paid;

    public Ticket(String id, TicketStatus status, int minutesParked, boolean paid) {
        this.id = id;
        this.status = status;
        this.minutesParked = minutesParked;
        this.paid = paid;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public int getMinutesParked() {
        return minutesParked;
    }

    public void setMinutesParked(int minutesParked) {
        this.minutesParked = minutesParked;
    }

    public boolean isPaid() {
        return paid;
    }

    public void setPaid(boolean paid) {
        this.paid = paid;
    }
}
