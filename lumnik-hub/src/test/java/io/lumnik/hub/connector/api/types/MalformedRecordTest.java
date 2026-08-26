package io.lumnik.hub.connector.api.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MalformedRecordTest {

  @Test
  void per_row_carries_raw_values() {
    var m = new MalformedRecord("/imports/x.csv", 3L, List.of("ONLY_ONE"), "col count");
    assertThat(m.file()).isEqualTo("/imports/x.csv");
    assertThat(m.line()).isEqualTo(3L);
    assertThat(m.rawValues()).containsExactly("ONLY_ONE");
    assertThat(m.message()).isEqualTo("col count");
  }

  @Test
  void file_fatal_has_null_raw_values() {
    var m = new MalformedRecord("/imports/x.csv", 5L, null, "EOF reached");
    assertThat(m.rawValues()).isNull();
  }
}
