package io.lumnik.hub.connector.exceptions;

public class UnknownConnectorTypeException extends ConnectorException {
  public UnknownConnectorTypeException(String typeId) {
    super("Unknown connector type: " + typeId);
  }
}
