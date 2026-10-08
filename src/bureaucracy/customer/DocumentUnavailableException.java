package bureaucracy.customer;

import bureaucracy.model.Document;

public class DocumentUnavailableException extends Exception {
    public DocumentUnavailableException(Document document, String s) {
        super(document.getName() + " " + s);
    }
}
