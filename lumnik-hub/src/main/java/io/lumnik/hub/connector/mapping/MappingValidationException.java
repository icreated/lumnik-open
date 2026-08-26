package io.lumnik.hub.connector.mapping;

public class MappingValidationException extends RuntimeException {
  public MappingValidationException(String message) { super(message); }
  public MappingValidationException(String message, Throwable cause) { super(message, cause); }
}
