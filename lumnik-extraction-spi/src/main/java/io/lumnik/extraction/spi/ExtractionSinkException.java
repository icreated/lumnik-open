package io.lumnik.extraction.spi;

public class ExtractionSinkException extends RuntimeException {
    public ExtractionSinkException(String message, Throwable cause) { super(message, cause); }
    public ExtractionSinkException(String message) { super(message); }
}
