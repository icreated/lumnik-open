package io.lumnik.hub.connector.mapping.writer;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChunkTemplateRendererTest {

  @Inject ChunkTemplateRenderer renderer;

  @Test
  void renders_simple_placeholders() {
    var row = Map.<String,Object>of("name","Alice","country","FR");
    var sys = Map.<String,Object>of("_tenant", 1L, "_row_hash", "abc");
    String out = renderer.render("Customer ${name} from ${country} (t=${_tenant})", row, sys);
    assertThat(out).isEqualTo("Customer Alice from FR (t=1)");
  }

  @Test
  void missing_placeholder_is_empty() {
    String out = renderer.render("Hello ${missing}", Map.of("name","X"), Map.of());
    assertThat(out).isEqualTo("Hello ");
  }

  @Test
  void null_value_is_empty() {
    var row = new HashMap<String,Object>();
    row.put("name", null);
    String out = renderer.render("Hello ${name}", row, Map.of());
    assertThat(out).isEqualTo("Hello ");
  }

  @Test
  void system_field_with_underscore_prefix() {
    var sys = Map.<String,Object>of("_row_hash", "abc123");
    String out = renderer.render("hash=${_row_hash}", Map.of(), sys);
    assertThat(out).isEqualTo("hash=abc123");
  }

  @Test
  void no_placeholders_returns_template_unchanged() {
    String out = renderer.render("static text", Map.of(), Map.of());
    assertThat(out).isEqualTo("static text");
  }
}
