package io.lumnik.hub.entity;

/**
 * A point's column is not a number in the compiled view (#1974): the distance function refuses text, and the repair
 * belongs to the door. Raised inside the apply transaction, so nothing is stored.
 */
public class PointNotANumberException extends RuntimeException {

  public PointNotANumberException(String at, String column, String type) {
    super(at + ": column '" + column + "' is " + type + ", a point needs a number — parse it at the door "
        + "(parse-coordinates)");
  }
}
