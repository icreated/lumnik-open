package io.lumnik.hub.export.destination;

public class FileDestinationException extends RuntimeException {
    public FileDestinationException(String message) { super(message); }
    public FileDestinationException(String message, Throwable cause) { super(message, cause); }
}
