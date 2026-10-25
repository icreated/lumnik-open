package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
@HookId("parse-phone")
public class ParsePhoneHook implements CellHook {

  private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

  @Override
  public void apply(Map<String, Object> row, JsonNode params, MappingState s) {
    String col = params.path("column").asText();
    String region = params.path("default-region").asText("US");
    Object v = row.get(col);
    if (v == null) return;

    try {
      var num = util.parse(v.toString(), region);
      row.put(col, util.format(num, PhoneNumberUtil.PhoneNumberFormat.E164));
    } catch (NumberParseException e) {
      s.counters().cellErrors().incrementAndGet();
      row.put(col, null);
    }
  }

  @Override
  public Set<String> params() {
    return Set.of("column", "default-region");
  }

  @Override
  public ColumnEffect columns(JsonNode params) {
    return ColumnEffect.readsColumn(params);
  }
}
