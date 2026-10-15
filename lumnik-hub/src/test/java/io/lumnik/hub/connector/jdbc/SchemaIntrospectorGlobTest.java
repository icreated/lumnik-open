package io.lumnik.hub.connector.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A table-exclusion glob is literal text plus the two wildcards {@code *} and {@code ?} — nothing else is special. */
class SchemaIntrospectorGlobTest {

  private static boolean matches(String glob, String name) {
    return SchemaIntrospector.globToPattern(glob).matcher(name).matches();
  }

  @Test
  void a_dollar_sign_is_literal_so_oracle_recycle_bin_and_log_tables_match() {
    assertThat(matches("BIN$*", "BIN$AbC==$0")).isTrue();
    assertThat(matches("MLOG$_*", "MLOG$_ORDERS")).isTrue();
    assertThat(matches("RUPD$_*", "RUPD$_X")).isTrue();
    assertThat(matches("BIN$*", "BINX")).isFalse();
  }

  @Test
  void the_three_postgresql_defaults_keep_their_meaning() {
    assertThat(matches("pg_*", "pg_catalog")).isTrue();
    assertThat(matches("pg_*", "xpg_catalog")).isFalse();
    assertThat(matches("information_schema*", "information_schema")).isTrue();
    assertThat(matches("t_*", "t_temp")).isTrue();
    assertThat(matches("t_*", "st_temp")).isFalse();
  }

  @Test
  void a_dot_is_literal_and_a_question_mark_is_exactly_one_character() {
    assertThat(matches("a.b", "a.b")).isTrue();
    assertThat(matches("a.b", "axb")).isFalse();
    assertThat(matches("c?", "cx")).isTrue();
    assertThat(matches("c?", "c")).isFalse();
  }

  @Test
  void other_regex_metacharacters_are_literal() {
    assertThat(matches("[ab]*", "[ab]x")).isTrue();
    assertThat(matches("[ab]*", "ax")).isFalse();
    assertThat(matches("a+b", "a+b")).isTrue();
    assertThat(matches("a+b", "aab")).isFalse();
  }
}
