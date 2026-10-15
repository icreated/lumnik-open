package io.lumnik.hub.ask;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Enlists the readonly datasource in JTA, as production does (%test disables it). */
public class ReadonlyTransactionsProfile implements QuarkusTestProfile {
  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("quarkus.datasource.\"readonly\".jdbc.transactions", "enabled");
  }
}
