package io.lumnik.hub.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bundled Keycloak realm exports, for the anti-drift tests that read them. Three tenant tests
 * had each carried their own realm {@code Path}, {@code ObjectMapper} and find-client-by-id loop
 * ({@link ShippedRealmUserSeedIT}, {@code RealmAudienceContractTest}, {@code ShippedRealmClientMappersTest});
 * at the rule of three the copies fold here. (A {@code @CsvSource} still names the files inline —
 * annotation values must be compile-time constants — so this is the single source for all but those.)
 */
final class ShippedRealms {

  /** The realm the hub ships — users claim {@code user_id}, interactive clients emit it. */
  static final Path REALM = Path.of("../infra/keycloak/lumnik-realm.json");

  /** The JIT stand-in: the realm MINUS every {@code user_id} mapper, so its users ride JIT. */
  static final Path NATIVE = Path.of("../infra/keycloak/lumnik-realm-native.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ShippedRealms() {}

  /** Parse a shipped realm; a missing or malformed file fails the test that asked for it. */
  static JsonNode read(Path realm) {
    try {
      return MAPPER.readTree(Files.readString(realm));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read shipped realm " + realm, e);
    }
  }

  /** The named client, or a failure that names it — a renamed client must not pass as absent. */
  static JsonNode client(JsonNode realm, String clientId) {
    for (JsonNode c : realm.path("clients")) {
      if (clientId.equals(c.path("clientId").asText())) return c;
    }
    throw new AssertionError("no client '" + clientId + "' in the realm — renamed or removed?");
  }
}
