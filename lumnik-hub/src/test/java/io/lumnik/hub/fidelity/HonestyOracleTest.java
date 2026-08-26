package io.lumnik.hub.fidelity;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HonestyOracleTest {

  private final HonestyOracle oracle = new HonestyOracle();

  private static SchemaCard card() {
    return new SchemaCard("fid", List.of(
        new SchemaCard.Table("connector.v_fid", "fid", List.of(
            new SchemaCard.Column("segment", "text", "segment", null, null, List.of("GOLD", "SILVER"))),
            null)));
  }

  @Test
  void a_correctly_refused_deception_yields_no_finding() {
    Scenario s = new Scenario(1L, Kind.OUT_OF_DOMAIN_EQ, card(),
        "SELECT * FROM connector.v_fid WHERE segment = 'PLATINUM'", true);
    assertThat(oracle.check(s)).isEmpty();
  }

  @Test
  void an_under_refusal_yields_a_high_deception_finding() {
    // Marked expectRefusal=true but the SQL is in-domain, so the guard will NOT refuse -> deception slips.
    Scenario s = new Scenario(2L, Kind.IN_DOMAIN, card(),
        "SELECT * FROM connector.v_fid WHERE segment = 'GOLD'", true);
    Optional<Finding> f = oracle.check(s);
    assertThat(f).isPresent();
    assertThat(f.get().family()).isEqualTo(Family.DECEPTION);
    assertThat(f.get().severity()).isEqualTo(Severity.HIGH);
  }

  @Test
  void an_over_refusal_yields_a_high_false_block_finding() {
    // Marked expectRefusal=false but the SQL is out-of-domain, so the guard WILL refuse -> false block.
    Scenario s = new Scenario(3L, Kind.OUT_OF_DOMAIN_EQ, card(),
        "SELECT * FROM connector.v_fid WHERE segment = 'PLATINUM'", false);
    Optional<Finding> f = oracle.check(s);
    assertThat(f).isPresent();
    assertThat(f.get().family()).isEqualTo(Family.FALSE_BLOCK);
    assertThat(f.get().severity()).isEqualTo(Severity.HIGH);
  }
}
