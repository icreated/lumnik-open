package io.lumnik.hub.connector.mapping.spec;

import java.util.List;

public record MappingSpec(
    String id,
    int version,
    MappingTarget targets,
    StreamMode mode,
    String description,
    List<HookInvocation> dataset,
    List<HookInvocation> row,
    List<HookInvocation> cell,
    /** Parsed from templates but NOT enforced by any runtime consumer yet (tribunal B3). */
    ErrorPolicy onError
) {
  public enum StreamMode { STREAM, SORTED_STREAM }

  /** Legacy accessor — returns the JPA entity name if JPA is the only target, else null. */
  public String target() {
    if (targets != null && targets.jpa() != null && targets.rag() == null && targets.table() == null) {
      return targets.jpa().entity();
    }
    return null;
  }
}
