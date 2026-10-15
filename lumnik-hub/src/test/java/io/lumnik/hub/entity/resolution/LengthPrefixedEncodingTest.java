package io.lumnik.hub.entity.resolution;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot #616 of the #613 entity-resolution arc (design doc §6): the fingerprint's encoding must be
 * unambiguous. Plain concatenation is not — {@code ("Martin","Jean")} and {@code ("Martin
 * Jean","")} would hash identically and manufacture a false merge. No CDI: pure function.
 */
class LengthPrefixedEncodingTest {

  @Test
  void composite_fields_do_not_collide_across_a_boundary_shift() {
    byte[] twoFields = LengthPrefixedEncoding.encode(List.of("Martin", "Jean"));
    byte[] oneFieldWithASpace = LengthPrefixedEncoding.encode(List.of("Martin Jean", ""));

    assertThat(twoFields).isNotEqualTo(oneFieldWithASpace);
  }

  @Test
  void a_null_field_and_an_empty_field_encode_differently() {
    byte[] withNull = LengthPrefixedEncoding.encode(Arrays.asList((String) null));
    byte[] withEmpty = LengthPrefixedEncoding.encode(List.of(""));

    assertThat(withNull).isNotEqualTo(withEmpty);
  }

  @Test
  void the_same_fields_encode_identically() {
    byte[] a = LengthPrefixedEncoding.encode(List.of("Martin", "Jean"));
    byte[] b = LengthPrefixedEncoding.encode(List.of("Martin", "Jean"));

    assertThat(a).isEqualTo(b);
  }

  @Test
  void field_order_is_part_of_the_encoding() {
    byte[] a = LengthPrefixedEncoding.encode(List.of("Martin", "Jean"));
    byte[] b = LengthPrefixedEncoding.encode(List.of("Jean", "Martin"));

    assertThat(a).isNotEqualTo(b);
  }
}
