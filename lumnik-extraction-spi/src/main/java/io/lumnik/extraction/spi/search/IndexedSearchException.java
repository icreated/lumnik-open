package io.lumnik.extraction.spi.search;

public class IndexedSearchException extends RuntimeException {
    public IndexedSearchException(String message, Throwable cause) { super(message, cause); }
    public IndexedSearchException(String message) { super(message); }
}
