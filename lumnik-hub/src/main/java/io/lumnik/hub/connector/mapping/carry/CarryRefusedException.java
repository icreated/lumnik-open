package io.lumnik.hub.connector.mapping.carry;

import io.lumnik.hub.connector.mapping.MappingValidationException;

/** A carry that cannot be stored without changing it: a class outside the codec's list, a key that is not text, a size. */
public class CarryRefusedException extends MappingValidationException {
  public CarryRefusedException(String message) { super(message); }
}
