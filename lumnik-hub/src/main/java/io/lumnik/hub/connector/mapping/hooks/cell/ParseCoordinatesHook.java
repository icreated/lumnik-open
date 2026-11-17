package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Blank;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Box;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Point;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.Reason;
import io.lumnik.hub.connector.mapping.hooks.cell.CoordinatePair.SetAside;
import io.lumnik.hub.json.JsonNodes;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads two columns as one GPS point in decimal degrees (#1966): {@code lat} and {@code lon} name them. A readable
 * pair is written back as two numbers; a pair that cannot be trusted empties both cells and counts ONE cell error —
 * half a point is not a point. {@code within: [south, west, north, east]} says where the points are; {@code swapped:
 * fix} exchanges a swapped pair instead of setting it aside; {@code reason} names a column that says why a pair was
 * set aside or exchanged. The hook has no {@code column}, so an unreadable pair follows {@code on-error.default}.
 */
@ApplicationScoped
@HookId("parse-coordinates")
public class ParseCoordinatesHook implements CellHook {

  private static final String OWNER = "parse-coordinates hook";
  private static final String FIX = "fix";
  private static final String EXCHANGED = "swapped: exchanged";
  private static final String FOUR_NUMBERS = "'within' must be four numbers: south, west, north, east";

  @Override
  public void validateParams(JsonNode params) {
    String lat = ScalarParams.columnName(OWNER, "lat", params.path("lat"));
    String lon = ScalarParams.columnName(OWNER, "lon", params.path("lon"));
    if (lat.equals(lon)) throw refusal("'lat' and 'lon' must name two different columns");
    if (!JsonNodes.absent(params.path("reason"))) {
      String reason = ScalarParams.columnName(OWNER, "reason", params.path("reason"));
      if (reason.equals(lat) || reason.equals(lon)) throw refusal("'reason' must name a column of its own");
    }
    ScalarParams.optionalWord(OWNER, "swapped", params.path("swapped"), null, List.of(FIX));
    validateWithin(params.path("within"));
  }

  private static void validateWithin(JsonNode within) {
    if (JsonNodes.absent(within)) return;
    if (!within.isArray() || within.size() != 4) throw refusal(FOUR_NUMBERS);
    for (JsonNode bound : within) {
      if (!bound.isNumber() || !Double.isFinite(bound.asDouble())) throw refusal(FOUR_NUMBERS);
    }
    Box box = box(within);
    if (Math.abs(box.south()) > 90 || Math.abs(box.north()) > 90) {
      throw refusal("'within': a latitude is between -90 and 90");
    }
    if (Math.abs(box.west()) > 180 || Math.abs(box.east()) > 180) {
      throw refusal("'within': a longitude is between -180 and 180");
    }
    if (box.south() >= box.north()) throw refusal("'within': south must be below north");
    if (box.west() >= box.east()) {
      throw refusal("'within': west must be below east — an area across the antimeridian is not supported");
    }
  }

  private static MappingValidationException refusal(String what) {
    return new MappingValidationException(OWNER + ": " + what);
  }

  private static Box box(JsonNode within) {
    return new Box(within.get(0).asDouble(), within.get(1).asDouble(), within.get(2).asDouble(),
        within.get(3).asDouble());
  }

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState state) {
    String lat = params.path("lat").asText();
    String lon = params.path("lon").asText();
    JsonNode within = params.path("within");
    var outcome = CoordinatePair.repair(text(row.get(lat)), text(row.get(lon)),
        JsonNodes.absent(within) ? Box.WORLD : box(within), FIX.equals(params.path("swapped").asText()));

    switch (outcome) {
      case Blank blank -> {
        row.replace(lat, null);   // a blank cell is an empty one; a column the row does not have is not added
        row.replace(lon, null);
        declare(row, params);
      }
      case Point(BigDecimal latitude, BigDecimal longitude, boolean exchanged) -> {
        row.put(lat, latitude);
        row.put(lon, longitude);
        if (exchanged) say(row, params, EXCHANGED);
        else declare(row, params);
      }
      case SetAside(Reason reason) -> {
        row.put(lat, null);
        row.put(lon, null);
        say(row, params, reason.text());
        state.refuseCell(reason.text());   // one pair, one count
      }
    }
  }

  /** The reason column exists on the row, and keeps what it holds: another pair's reason is not erased. */
  private static void declare(Map<String, Object> row, JsonNode params) {
    if (!JsonNodes.absent(params.path("reason"))) row.putIfAbsent(params.path("reason").asText(), null);
  }

  private static void say(Map<String, Object> row, JsonNode params, String reason) {
    if (!JsonNodes.absent(params.path("reason"))) row.put(params.path("reason").asText(), reason);
  }

  /** A cell as its text; a number as it is written in full — {@code 1.0E-4} is {@code 0.0001}, east of Greenwich. */
  private static String text(Object cell) {
    if (cell instanceof Number number) {
      try {
        return new BigDecimal(number.toString()).toPlainString();
      } catch (NumberFormatException notANumber) {   // NaN, an infinity: read as text, and so not a coordinate
        return number.toString();
      }
    }
    return cell == null ? null : cell.toString();
  }

  @Override
  public Set<String> params() {
    return Set.of("lat", "lon", "within", "swapped", "reason");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.builder().reads("lat", params.path("lat")).reads("lon", params.path("lon"))
        .adds(params.path("reason")).build();
  }
}
