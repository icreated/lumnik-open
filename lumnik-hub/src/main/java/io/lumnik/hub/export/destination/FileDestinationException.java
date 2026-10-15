package io.lumnik.hub.export.destination;

import io.lumnik.hub.export.AuthoredFailure;

public class FileDestinationException extends RuntimeException implements AuthoredFailure {
    public FileDestinationException(String message) { super(message); }
    public FileDestinationException(String message, Throwable cause) { super(message, cause); }
}
