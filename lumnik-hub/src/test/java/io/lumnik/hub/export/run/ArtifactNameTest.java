package io.lumnik.hub.export.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactNameTest {

  static final Instant AT = Instant.parse("2026-09-24T04:05:06Z");

  @Test
  void dateToken() {
    assertThat(ArtifactName.resolve("customers-${date}.csv", AT)).isEqualTo("customers-2026-09-24.csv");
  }

  @Test
  void timestampToken() {
    assertThat(ArtifactName.resolve("c-${timestamp}.csv", AT)).isEqualTo("c-20260924T040506Z.csv");
  }

  @Test
  void bothTokens() {
    assertThat(ArtifactName.resolve("${date}/c-${timestamp}.csv", AT))
        .isEqualTo("2026-09-24/c-20260924T040506Z.csv");
  }

  @Test
  void noTokenIsVerbatim() {
    assertThat(ArtifactName.resolve("customers.csv", AT)).isEqualTo("customers.csv");
  }

  @Test
  void utcAroundMidnight() {
    Instant late = Instant.parse("2026-09-24T23:59:59Z");
    Instant early = Instant.parse("2026-09-25T00:00:00Z");
    assertThat(ArtifactName.resolve("${date}", late)).isEqualTo("2026-09-24");
    assertThat(ArtifactName.resolve("${date}", early)).isEqualTo("2026-09-25");
    assertThat(ArtifactName.resolve("${timestamp}", early)).isEqualTo("20260925T000000Z");
  }

  @Test
  void anUnknownTokenIsABug() {
    assertThatThrownBy(() -> ArtifactName.resolve("${user}.csv", AT))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void everyTokenTheRuleAcceptsResolves() {
    for (String token : ArtifactName.TOKENS) {
      String template = "c-${" + token + "}.csv";
      assertThat(ArtifactName.problem(template)).isEmpty();
      assertThat(ArtifactName.resolve(template, AT)).doesNotContain("${");
    }
  }

  @Test
  void theRuleRefusesUnknownAndUnterminatedTokens() {
    assertThat(ArtifactName.problem("${user}.csv")).contains("unrecognized substitution token"
        + " '${user}' — only ${date} and ${timestamp} are supported");
    assertThat(ArtifactName.problem("c-${date.csv")).contains("unterminated '${' — only"
        + " ${date} and ${timestamp} are supported substitution tokens");
    assertThat(ArtifactName.problem("plain.csv")).isEmpty();
  }
}
