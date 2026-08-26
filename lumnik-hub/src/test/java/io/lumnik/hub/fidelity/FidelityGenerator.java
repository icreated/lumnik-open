package io.lumnik.hub.fidelity;

import io.lumnik.extraction.spi.ask.SchemaCard;

import java.util.List;
import java.util.Locale;

/** Deterministic adversarial scenario generator. Mirrors the lm OTK splitmix64 PRNG so the
 *  seed -> scenario mapping is stable. Pure; same seed -> same scenario. */
public final class FidelityGenerator {

  private FidelityGenerator() {}

  private static final List<String> COLUMNS = List.of("segment", "status", "tier", "region");
  private static final List<List<String>> DOMAINS = List.of(
      List.of("GOLD", "SILVER", "BRONZE"),
      List.of("OPEN", "CLOSED"),
      List.of("A", "B", "C", "D"));
  private static final List<String> OUT_VALUES = List.of("PLATINUM", "UNKNOWN", "ZZZ");
  private static final List<String> UNDECLARED = List.of("loyalty_tier", "rank_band", "vip");
  private static final String VIEW = "connector.v_fid";
  private static final String LEAK_TABLE = "connector.t_leak"; // never declared in oneTable's card

  public static Scenario generate(long seed) {
    long h = OtkRng.mix(seed);
    Kind kind = Kind.values()[(int) Math.floorMod(h, Kind.values().length)];
    h = OtkRng.mix(h);
    String col = COLUMNS.get((int) Math.floorMod(h, COLUMNS.size()));
    h = OtkRng.mix(h);
    List<String> domain = DOMAINS.get((int) Math.floorMod(h, DOMAINS.size()));
    h = OtkRng.mix(h);
    String inVal = domain.get((int) Math.floorMod(h, domain.size()));
    h = OtkRng.mix(h);
    String outVal = OUT_VALUES.get((int) Math.floorMod(h, OUT_VALUES.size()));
    h = OtkRng.mix(h);
    String undeclared = UNDECLARED.get((int) Math.floorMod(h, UNDECLARED.size()));

    SchemaCard.Column c = new SchemaCard.Column(col, "text", col, null, null, domain);
    SchemaCard oneTable = new SchemaCard("fid", List.of(
        new SchemaCard.Table(VIEW, "fid", List.of(c), null)));

    return switch (kind) {
      case OUT_OF_DOMAIN_EQ -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE " + col + " = '" + outVal + "'", true);
      case OUT_OF_DOMAIN_IN -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE " + col + " IN ('" + inVal + "', '" + outVal + "')", true);
      case IN_DOMAIN -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE " + col + " = '" + inVal + "'", false);
      case FUNCTION_WRAPPED -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE lower(" + col + ") = '" + outVal.toLowerCase(Locale.ROOT) + "'", false);
      case QUOTED_IDENT -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE \"" + col + "\" = '" + outVal + "'", false);
      case NON_CLEAN_IN -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE " + col + " IN ('" + inVal + "', upper('" + outVal + "'))", false);
      case CROSS_TABLE_DIVERGENT -> {
        // Same column name in two tables with DIFFERENT domains -> ambiguous -> must NOT enforce.
        SchemaCard.Column other = new SchemaCard.Column(col, "text", col, null, null, OUT_VALUES);
        SchemaCard twoTables = new SchemaCard("fid", List.of(
            new SchemaCard.Table(VIEW, "a", List.of(c), null),
            new SchemaCard.Table("connector.v_fid2", "b", List.of(other), null)));
        yield new Scenario(seed, kind, twoTables,
            "SELECT * FROM " + VIEW + " WHERE " + col + " = '" + inVal + "'", false);
      }
      case UNDECLARED_COLUMN_EQ -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE " + undeclared + " = '" + inVal + "'", true);
      case UNDECLARED_COLUMN_NUM -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE " + undeclared + " = 42", false);
      case INTERNAL_COLUMN -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE tenant_id = 7", false);
      case QUALIFIED_UNDECLARED -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " t WHERE t." + undeclared + " = '" + inVal + "'", false);
      case FUNCTION_UNDECLARED -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW + " WHERE lower(" + undeclared + ") = '" + inVal.toLowerCase(Locale.ROOT) + "'", false);
      case LEAK_UNDECLARED_TABLE -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + LEAK_TABLE, true);
      case LEAK_IN_SUBQUERY -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM (SELECT * FROM " + LEAK_TABLE + ") x", true);
      case DECLARED_TABLE -> new Scenario(seed, kind, oneTable,
          "SELECT * FROM " + VIEW, false);
      case CTE_ALIAS -> new Scenario(seed, kind, oneTable,
          "WITH r AS (SELECT * FROM " + VIEW + ") SELECT * FROM r", false);
    };
  }
}
