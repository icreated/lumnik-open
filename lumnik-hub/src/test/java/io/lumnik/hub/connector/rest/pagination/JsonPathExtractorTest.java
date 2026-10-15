package io.lumnik.hub.connector.rest.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #960: the watermark reads each item a pagination strategy already produced — json-smart output — so
 * it can read it as it is instead of serialising it to a {@code JsonNode} and parsing it back. The
 * read must be the same read: same path configuration (an absent path is {@code null}, never a throw)
 * and, for everything a manifest can name as a watermark, the same value.
 */
class JsonPathExtractorTest {

  private static final ObjectMapper M = new ObjectMapper();

  /** The item exactly as a strategy hands it over: one element of the list read at {@code response_path}. */
  private static Map<String, Object> item(String json) throws Exception {
    List<Map<String, Object>> items = PaginationSupport.items(M.readTree("{\"data\":[" + json + "]}"), "$.data");
    return items.get(0);
  }

  /** What the watermark did before: through a Jackson tree and a text round trip. */
  private static Object viaTree(Map<String, Object> item, String path) {
    JsonNode tree = M.valueToTree(item);
    return JsonPathExtractor.read(tree, path);
  }

  @Test
  void an_absent_path_reads_null_and_does_not_throw() throws Exception {
    Map<String, Object> it = item("{\"id\":1}");
    assertThat((Object) JsonPathExtractor.readParsed(it, "$.updated_at")).isNull();
    assertThat((Object) JsonPathExtractor.readParsed(it, "$.a.b.c")).isNull();
  }

  @Test
  void a_null_value_and_a_null_path_read_null() throws Exception {
    Map<String, Object> it = item("{\"updated_at\":null}");
    assertThat((Object) JsonPathExtractor.readParsed(it, "$.updated_at")).isNull();
    assertThat((Object) JsonPathExtractor.readParsed(it, null)).isNull();
  }

  @Test
  void a_nested_path_reads_through_the_item() throws Exception {
    Map<String, Object> it = item("{\"meta\":{\"changed\":\"2026-10-11T00:00:00Z\"}}");
    assertThat((String) JsonPathExtractor.readParsed(it, "$.meta.changed")).isEqualTo("2026-10-11T00:00:00Z");
  }

  @Test
  void every_value_a_watermark_is_made_of_reads_as_it_did_through_the_tree() throws Exception {
    for (String v : List.of("\"2024-01-02T03:04:05Z\"", "\"\"", "\"é\"", "100", "-7", "1.10", "1.0", "0.1",
                            "1e3", "1.5E-7", "12345678901234567890", "true", "null")) {
      Map<String, Object> it = item("{\"w\":" + v + "}");
      Object before = viaTree(it, "$.w");
      Object after = JsonPathExtractor.readParsed(it, "$.w");
      assertThat(String.valueOf(after)).as("watermark text of %s", v).isEqualTo(String.valueOf(before));
      assertThat(after == null ? null : after.getClass()).as("value class of %s", v)
          .isEqualTo(before == null ? null : before.getClass());
    }
    assertThat(JsonPathExtractor.<Object>readParsed(item("{\"w\":12345678901234567890}"), "$.w"))
        .isInstanceOf(BigInteger.class);
  }

  /**
   * The one place the two routes part: a decimal with more digits than a double holds. The tree route
   * squeezed it through a {@code double} (scientific notation, digits lost); read from the item it stays
   * the {@code BigDecimal} the page carried. Pinned so the change is a decision, not a surprise: such a
   * watermark is persisted once in its exact form.
   */
  @Test
  void a_decimal_beyond_double_precision_keeps_its_digits() throws Exception {
    Map<String, Object> it = item("{\"w\":123456789.123456789012345678}");

    assertThat(String.valueOf((Object) viaTree(it, "$.w"))).isEqualTo("1.2345678912345679E8");
    Object now = JsonPathExtractor.readParsed(it, "$.w");
    assertThat(now).isInstanceOf(BigDecimal.class);
    assertThat(String.valueOf(now)).isEqualTo("123456789.12345679");
  }
}
