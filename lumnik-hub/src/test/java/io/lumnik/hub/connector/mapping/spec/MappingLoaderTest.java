package io.lumnik.hub.connector.mapping.spec;

import io.lumnik.hub.connector.mapping.MappingValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MappingLoaderTest {

  @Test
  void loads_minimal_valid_yaml() {
    String yaml = """
      id: test
      version: 1
      target: business_partner.BusinessPartner
      """;
    MappingSpec spec = MappingLoader.load(yaml);
    assertThat(spec.id()).isEqualTo("test");
    assertThat(spec.version()).isEqualTo(1);
    assertThat(spec.target()).isEqualTo("business_partner.BusinessPartner");
    assertThat(spec.mode()).isEqualTo(MappingSpec.StreamMode.STREAM);
  }

  @Test
  void rejects_a_malformed_hook_entry() {
    // A hook entry that is not a single-key object. MappingLoader always threw; the point of the
    // shared parser is that the csv loader now does too (it used to silently drop it).
    String yaml = "id: t\nversion: 1\ntarget: foo\nrow:\n  - [a, b]\n";
    assertThatThrownBy(() -> MappingLoader.load(yaml))
      .isInstanceOf(MappingValidationException.class)
      .hasMessageContaining("each hook must be an object with one key");
  }

  @Test
  void rejects_yaml_without_id() {
    String yaml = "version: 1\ntarget: foo";
    assertThatThrownBy(() -> MappingLoader.load(yaml))
      .isInstanceOf(MappingValidationException.class)
      .hasMessageContaining("id is required");
  }

  @Test
  void parses_three_tiers() {
    String yaml = """
      id: t
      version: 1
      target: foo
      dataset:
        - { skip-empty-rows: true }
      row:
        - { validate-row: { required: [email] } }
      cell:
        - { clean: { columns: ['*'], strip: true } }
      """;
    MappingSpec spec = MappingLoader.load(yaml);
    assertThat(spec.dataset()).hasSize(1);
    assertThat(spec.row()).hasSize(1);
    assertThat(spec.cell()).hasSize(1);
    assertThat(spec.dataset().get(0).name()).isEqualTo("skip-empty-rows");
  }

  @Test
  void parses_sorted_stream_mode() {
    String yaml = """
      id: t
      version: 1
      target: foo
      mode: sorted-stream
      """;
    MappingSpec spec = MappingLoader.load(yaml);
    assertThat(spec.mode()).isEqualTo(MappingSpec.StreamMode.SORTED_STREAM);
  }

  @Test
  void parses_error_policy() {
    String yaml = """
      id: t
      version: 1
      target: foo
      on-error:
        default: dlq
        fields:
          email: soft
      """;
    MappingSpec spec = MappingLoader.load(yaml);
    assertThat(spec.onError().defaultMode()).isEqualTo(ErrorPolicy.Mode.DLQ);
    assertThat(spec.onError().fields()).containsEntry("email", ErrorPolicy.Mode.SOFT);
  }
}
