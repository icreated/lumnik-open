package io.lumnik.hub.connector.reference;

import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.jboss.logging.Logger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class HelloWorldConnectorTypeTest {

  @Inject HelloWorldConnectorType type;

  private ConnectorContext ctx(int count) {
    return new ConnectorContext(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
        "test", "hello-world", Map.of("count", count),
        new SyncMode.Incremental(),
        Logger.getLogger("test"),
        null, null, Clock.systemUTC(), null, new OwnedRunScope("test"));
  }

  @Test
  void id_is_hello_world() {
    assertThat(type.id()).isEqualTo("hello-world");
  }

  @Test
  void supports_manual_and_poll() {
    assertThat(type.supportedTriggerModes()).containsExactlyInAnyOrder(TriggerMode.MANUAL, TriggerMode.POLL);
  }

  @Test
  void default_write_profile_is_safe() {
    assertThat(type.defaultWriteProfile()).isInstanceOf(WritePerformanceProfile.Safe.class);
  }

  @Test
  void validate_config_requires_count() {
    assertThatThrownBy(() -> type.validateConfig(Map.of()))
        .isInstanceOf(ConfigValidationException.class);
    assertThatThrownBy(() -> type.validateConfig(Map.of("count", -5)))
        .isInstanceOf(ConfigValidationException.class);
    type.validateConfig(Map.of("count", 100));  // OK — no exception
  }

  @Test
  void reader_paginates_until_end_of_stream() {
    @SuppressWarnings("unchecked")
    ItemReader<HelloWorldConnectorType.HelloRecord> reader =
        (ItemReader<HelloWorldConnectorType.HelloRecord>) type.reader(ctx(10));

    var first = reader.read(3, ConnectorCursor.empty(), ctx(10));
    assertThat(first).isInstanceOf(ReadResult.HasMore.class);
    var hm = (ReadResult.HasMore<HelloWorldConnectorType.HelloRecord>) first;
    assertThat(hm.records()).hasSize(3);
    assertThat(hm.records().get(0).seq()).isEqualTo(0);

    var second = reader.read(3, hm.nextCursor(), ctx(10));
    assertThat(((ReadResult.HasMore<?>) second).records()).hasSize(3);

    var third = reader.read(3, ((ReadResult.HasMore<?>) second).nextCursor(), ctx(10));
    assertThat(((ReadResult.HasMore<?>) third).records()).hasSize(3);

    var fourth = reader.read(3, ((ReadResult.HasMore<?>) third).nextCursor(), ctx(10));
    assertThat(fourth).as("the tail still carries its cursor").isInstanceOf(ReadResult.HasMore.class);
    var tail = (ReadResult.HasMore<?>) fourth;
    assertThat(tail.records()).hasSize(1);

    var fifth = reader.read(3, tail.nextCursor(), ctx(10));
    assertThat(fifth).isInstanceOf(ReadResult.EndOfStream.class);
    assertThat(((ReadResult.EndOfStream<?>) fifth).records()).isEmpty();
  }

  @Test
  void writer_logs_and_returns_all_written() {
    @SuppressWarnings("unchecked")
    ItemWriter<HelloWorldConnectorType.HelloRecord> writer =
        (ItemWriter<HelloWorldConnectorType.HelloRecord>) type.writer(ctx(10));
    var items = List.of(
        new HelloWorldConnectorType.HelloRecord(0, "Hello #0", Instant.now()),
        new HelloWorldConnectorType.HelloRecord(1, "Hello #1", Instant.now())
    );
    var outcome = writer.write(items, ctx(10));
    assertThat(outcome).isInstanceOf(WriteOutcome.AllWritten.class);
    assertThat(((WriteOutcome.AllWritten) outcome).count()).isEqualTo(2);
  }

  /** The engine persists a cursor only on HasMore: a loaded EndOfStream forfeits its tail (#437). */
  @Test
  void the_readers_own_cursors_reach_the_last_record() {
    var reader = new HelloWorldConnectorType.HelloReader(10);
    ConnectorCursor cursor = ConnectorCursor.empty();
    int seen = 0;
    ReadResult<HelloWorldConnectorType.HelloRecord> r;
    while ((r = reader.read(3, cursor, ctx(10))) instanceof ReadResult.HasMore<HelloWorldConnectorType.HelloRecord> hm) {
      seen += hm.records().size();
      cursor = hm.nextCursor();
    }
    assertThat(seen).as("the HasMore chunks alone carry every record").isEqualTo(10);
    assertThat(((ReadResult.EndOfStream<?>) r).records()).as("the stream ends on a read that finds nothing").isEmpty();
  }
}
