package io.lumnik.hub.connector.mapping.spec;

import com.fasterxml.jackson.databind.JsonNode;

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
    /** What a value a cell hook could not deliver does to its row (#1408). */
    ErrorPolicy onError
) {
  public enum StreamMode { STREAM, SORTED_STREAM }

  /**
   * The spec of a connector's {@code mapping} block — a csv-file connector's, a rest-generic endpoint's (#1617): its
   * dataset, row and cell hooks and its {@code on-error}. {@code block} null: no hook, every value soft. Hooks parse
   * through {@link HookInvocation#parseList}, as MappingLoader's do, so every door refuses a malformed hook alike.
   */
  public static MappingSpec fromBlock(String id, MappingTarget target, String description, JsonNode block) {
    if (block == null) {
      return new MappingSpec(id, 1, target, StreamMode.STREAM, description, List.of(), List.of(), List.of(),
          ErrorPolicy.soft());
    }
    return new MappingSpec(id, 1, target, StreamMode.STREAM, description,
        HookInvocation.parseList(block, "dataset"), HookInvocation.parseList(block, "row"),
        HookInvocation.parseList(block, "cell"), ErrorPolicy.parse(block.path("on-error")));
  }
}
