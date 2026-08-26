package io.lumnik.hub.connector.mapping.spec;

import java.util.Map;

public record ErrorPolicy(
    Mode defaultMode,
    Map<String, Mode> fields  // override per column
) {
  public enum Mode { SOFT, DLQ, FAIL }
  public static ErrorPolicy soft() { return new ErrorPolicy(Mode.SOFT, Map.of()); }
}
