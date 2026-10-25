package io.lumnik.hub.connector.mapping.carry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #1452: a carry is stored under its mapping's fingerprint, so another mapping never reuses it. */
class MappingFingerprintTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static MappingSpec spec(String cellParams) throws Exception {
    return new MappingSpec("csv:t", 1, null, MappingSpec.StreamMode.STREAM, "d", List.of(), List.of(),
        List.of(new HookInvocation("test-carry", M.readTree(cellParams))), ErrorPolicy.soft());
  }

  @Test
  void the_same_mapping_has_the_same_fingerprint_whatever_its_key_order() throws Exception {
    assertThat(MappingFingerprint.of(spec("{\"column\":\"d\",\"x\":1}")))
        .isEqualTo(MappingFingerprint.of(spec("{\"x\":1,\"column\":\"d\"}")))
        .hasSize(64);
  }

  @Test
  void a_changed_parameter_changes_the_fingerprint() throws Exception {
    assertThat(MappingFingerprint.of(spec("{\"column\":\"d\"}")))
        .isNotEqualTo(MappingFingerprint.of(spec("{\"column\":\"e\"}")));
  }

  /** A bare hook (`- skip-empty-rows:`) has null params: it is fingerprinted, not refused. */
  @Test
  void a_hook_without_params_is_fingerprinted() throws Exception {
    assertThat(MappingFingerprint.of(spec("null"))).hasSize(64);
  }
}
