package io.lumnik.hub.connector.reference;

import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.registry.ConnectorTypeId;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

@ConnectorTypeId("hello-world")
@ApplicationScoped
public class HelloWorldConnectorType implements ConnectorType {

  @Override public String id() { return "hello-world"; }
  @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
  @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL, TriggerMode.POLL); }

  @Override
  public void validateConfig(Map<String, Object> config) {
    Object count = config.get("count");
    if (!(count instanceof Number n) || n.intValue() <= 0)
      throw new ConfigValidationException("Missing or invalid 'count' (positive integer)");
  }

  @Override public ItemReader<HelloRecord> reader(ConnectorContext ctx) {
    return new HelloReader(ctx.configValue("count", Integer.class));
  }

  @Override public ItemWriter<HelloRecord> writer(ConnectorContext ctx) {
    return new HelloLogWriter(ctx.logger());
  }

  public record HelloRecord(int seq, String message, Instant generatedAt) {}

  static class HelloReader implements ItemReader<HelloRecord> {
    private final int total;
    HelloReader(int total) { this.total = total; }

    @Override
    public ReadResult<HelloRecord> read(int chunkSize, ConnectorCursor cursor, ConnectorContext ctx) {
      long offset = cursor.isEmpty() ? 0L : Cursors.as(cursor, PaginationStrategy.Offset.class).offset();
      int remaining = total - (int) offset;
      if (remaining <= 0) return new ReadResult.EndOfStream<>(List.of());

      int take = Math.min(chunkSize, remaining);
      List<HelloRecord> records = IntStream.range((int) offset, (int) offset + take)
          .mapToObj(i -> new HelloRecord(i, "Hello #" + i, Instant.now()))
          .toList();

      // Every non-empty chunk is HasMore: the engine persists a cursor only from HasMore, a loaded
      // EndOfStream would forfeit its tail (see ReadResult). The stream ends on the read above.
      return new ReadResult.HasMore<>(records, Cursors.offset(offset + take, chunkSize));
    }
  }

  static class HelloLogWriter implements ItemWriter<HelloRecord> {
    private final Logger log;
    HelloLogWriter(Logger log) { this.log = log; }

    @Override
    public WriteOutcome write(List<HelloRecord> items, ConnectorContext ctx) {
      for (var r : items) log.infof("hello: %s @ %s", r.message(), r.generatedAt());
      return new WriteOutcome.AllWritten(items.size());
    }
  }
}
