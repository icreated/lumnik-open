package io.lumnik.hub.connector.mapping.hooks;

import io.lumnik.hub.connector.mapping.MappingValidationException;

import java.util.Set;
import java.util.TreeSet;

/**
 * A key the hook does not read (#1487): named, with the keys it does read — never a value. {@link #key()} is the key's
 * path inside the hook's parameters ({@code merge.total.colect}), so a validator can name it in its own path.
 */
public class UnknownHookParameterException extends MappingValidationException {

  private final String key;

  public UnknownHookParameterException(String hook, String key, Set<String> accepted) {
    super(hook + ": unknown parameter '" + key + "' — "
        + (accepted.isEmpty() ? "it reads none" : "accepted: " + String.join(", ", new TreeSet<>(accepted))));
    this.key = key;
  }

  public String key() {
    return key;
  }
}
