package io.lumnik.hub.connector.exceptions;

public class TransientFailureException extends ConnectorException {
  public TransientFailureException(String message, Throwable cause) { super(message, cause); }
  public boolean isRetryable() { return true; }
}
