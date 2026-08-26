package io.lumnik.hub.connector.types;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.types.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class SealedTypesRoundTripIT {

  @Inject ObjectMapper mapper;

  @Test
  void skip_policy_round_trip() throws Exception {
    SkipPolicy sp = new SkipPolicy.SkipUpTo(50);
    String json = mapper.writeValueAsString(sp);
    var back = mapper.readValue(json, SkipPolicy.class);
    assertThat(back).isInstanceOf(SkipPolicy.SkipUpTo.class);
    assertThat(((SkipPolicy.SkipUpTo) back).maxSkips()).isEqualTo(50);
  }

  @Test
  void cursor_offset_round_trip() {
    var c = Cursors.offset(100, 50);
    assertThat(c.isEmpty()).isFalse();
    var parsed = Cursors.as(c, PaginationStrategy.Offset.class);
    assertThat(parsed.offset()).isEqualTo(100);
    assertThat(parsed.pageSize()).isEqualTo(50);
  }

  @Test
  void cursor_keyset_round_trip() {
    var c = Cursors.keyset("id", 12345);
    var parsed = Cursors.as(c, PaginationStrategy.Keyset.class);
    assertThat(parsed.keyField()).isEqualTo("id");
    assertThat(((Number) parsed.lastKey()).intValue()).isEqualTo(12345);
  }

  @Test
  void cursor_timestamp_round_trip() {
    var ts = Instant.parse("2026-05-01T00:00:00Z");
    var c = Cursors.timestamp(ts);
    var parsed = Cursors.as(c, PaginationStrategy.Timestamp.class);
    assertThat(parsed.watermark()).isEqualTo(ts);
  }
}
