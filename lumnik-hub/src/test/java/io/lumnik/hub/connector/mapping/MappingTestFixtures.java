package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the carry tests build (#1452): a context with a tenant and a run, a cell-tier spec, a hook invocation. */
final class MappingTestFixtures {

  private static final ObjectMapper M = new ObjectMapper();

  private MappingTestFixtures() {}

  /** A context that carries only what the mapping writer's prepare reads: a tenant and a run. */
  static ConnectorContext run() {
    return new ConnectorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, "erp", "csv-file",
        Map.of(), new SyncMode.Incremental(), null, null, Clock.systemUTC(), null, null);
  }

  /** A spec with no target and only {@code cell} hooks. */
  static MappingSpec spec(ErrorPolicy onError, HookInvocation... cell) {
    return new MappingSpec("csv:t", 1, null, MappingSpec.StreamMode.STREAM, "d", List.of(), List.of(),
        List.of(cell), onError);
  }

  static HookInvocation hook(String name, String params) {
    try {
      return new HookInvocation(name, M.readTree(params));
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }
}
