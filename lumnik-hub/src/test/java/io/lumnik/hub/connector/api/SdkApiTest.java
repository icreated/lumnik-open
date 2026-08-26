package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.*;
import org.junit.jupiter.api.Test;

import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SuppressWarnings("java:S2699") // some methods assert via interface contract / "does not throw" semantics
class SdkApiTest {
  @Test
  void item_processor_identity_passes_through() {
    ItemProcessor<String, String> id = ItemProcessor.identity();
    assertThat(id.process("hello", null)).isEqualTo("hello");
  }

  @Test
  void connector_context_config_value_retrieval() {
    ConnectorContext ctx = new ConnectorContext(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        "test-connector",
        "test-type",
        Map.of("host", "localhost", "port", 5432, "timeout", 30.0),
        new SyncMode.Incremental(),
        Logger.getLogger("test"),
        null,
        null,
        Clock.systemUTC()
    );

    assertThat(ctx.configValue("host", String.class)).isEqualTo("localhost");
    assertThat(ctx.configValue("port", Integer.class)).isEqualTo(5432);
    assertThat(ctx.configValue("timeout", Double.class)).isEqualTo(30.0);
  }

  @Test
  void connector_context_config_value_with_default() {
    ConnectorContext ctx = new ConnectorContext(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        "test-connector",
        "test-type",
        Map.of(),
        new SyncMode.Incremental(),
        Logger.getLogger("test"),
        null,
        null,
        Clock.systemUTC()
    );

    assertThat(ctx.configValue("missing", String.class, "default")).isEqualTo("default");
  }

  @Test
  void connector_type_is_implementable() {
    ConnectorType ct = new ConnectorType() {
      @Override public String id() { return "test"; }
      @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
      @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
      @Override public void validateConfig(Map<String, Object> config) {}
      @Override public ItemReader<?> reader(ConnectorContext ctx) { return null; }
    };
    assertThat(ct.id()).isEqualTo("test");
    assertThat(ct.defaultChunkSize()).isEqualTo(500);
    assertThat(ct.writer(null)).isNull();
  }

  @Test
  void item_reader_enforces_interface() {
    ItemReader<String> reader = new ItemReader<String>() {
      @Override
      public ReadResult<String> read(int chunkSize, ConnectorCursor cursor, ConnectorContext ctx) {
        return new ReadResult.EndOfStream<>(List.of("test"));
      }
    };
    assertThat(reader).isNotNull();
  }

  @Test
  void item_writer_enforces_interface() {
    ItemWriter<String> writer = new ItemWriter<String>() {
      @Override
      public WriteOutcome write(List<String> items, ConnectorContext ctx) {
        return new WriteOutcome.AllWritten(items.size());
      }
    };
    assertThat(writer).isNotNull();
  }

  @Test
  void connector_type_lifecycle_has_defaults() {
    ConnectorTypeLifecycle lifecycle = new ConnectorTypeLifecycle() {};
    lifecycle.onRunStart(null);
    lifecycle.onRunEnd(null, null);
    lifecycle.onChunkStart(null, 0);
    lifecycle.onChunkEnd(null, null);
  }
}
