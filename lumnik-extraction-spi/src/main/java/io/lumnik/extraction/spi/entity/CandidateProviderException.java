package io.lumnik.extraction.spi.entity;

public class CandidateProviderException extends RuntimeException {
    public CandidateProviderException(String message, Throwable cause) { super(message, cause); }
    public CandidateProviderException(String message) { super(message); }
}
