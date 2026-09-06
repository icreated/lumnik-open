package io.lumnik.hub.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which shipped-realm CLIENTS emit a {@code user_id} claim — the axis {@link ShippedRealmUserSeedIT}
 * does not cover (it holds the USERS to the seed; this holds the CLIENTS to the claim).
 *
 * <p>The hub resolves the acting user from that claim, so the SAME human must reach the SAME row
 * from every client. {@code lm-cli} carried the mapper but {@code lumnik-spa} did not, so an
 * integrator whose {@code lm scope set} wrote row 1 reached a different (JIT) row from the PWA and
 * {@code /me} read the wrong preferences. Both interactive clients must carry it.
 *
 * <p>{@code lumnik-realm-native.json} is the deliberate opposite — the realm MINUS every
 * {@code user_id} mapper, so its users ride JIT ({@code make k8s-up-oidc-external} runs real JIT
 * against it). Pinning that too, a copy-paste of the fix into the native realm fails here.
 */
class ShippedRealmClientMappersTest {

  /** The interactive public clients a human logs in through; both must emit user_id. */
  private static final List<String> INTERACTIVE_CLIENTS = List.of("lm-cli", "lumnik-spa");

  @Test
  void every_interactive_client_emits_the_user_id_claim() {
    JsonNode realm = ShippedRealms.read(ShippedRealms.REALM);
    List<String> missing = new ArrayList<>();
    for (String clientId : INTERACTIVE_CLIENTS) {
      if (!emitsUserIdClaim(ShippedRealms.client(realm, clientId))) missing.add(clientId);
    }
    assertThat(missing)
        .as("these shipped-realm clients do not emit a user_id claim — the same human reaches a "
            + "different hub row from each, and the PWA reads the wrong user. Add a user_id "
            + "protocol mapper (claim.name=user_id) to the client in lumnik-realm.json")
        .isEmpty();
  }

  @Test
  void the_native_realm_ships_no_user_id_mapper_so_every_user_rides_jit() {
    JsonNode realm = ShippedRealms.read(ShippedRealms.NATIVE);
    List<String> carriers = new ArrayList<>();
    for (JsonNode client : realm.path("clients")) {
      if (emitsUserIdClaim(client)) carriers.add(client.path("clientId").asText());
    }
    assertThat(carriers)
        .as("lumnik-realm-native.json is the JIT stand-in — it must ship NO user_id mapper so tokens "
            + "never emit the claim and users ride JIT (make k8s-up-oidc-external asserts this). A "
            + "client here carrying one silently disables that e2e; drop it")
        .isEmpty();
  }

  /**
   * True if the client emits the {@code user_id} claim INTO THE ACCESS TOKEN — the token the hub
   * validates and reads it from ({@code TenantContextFilter.parseLongClaim(token, "user_id")}).
   * A mapper with {@code access.token.claim} unset (Keycloak's default is exclude) would put the
   * claim only in the id/userinfo token and satisfy nothing the hub sees — the same rigor
   * {@code RealmAudienceContractTest} applies to the audience mapper.
   */
  private static boolean emitsUserIdClaim(JsonNode client) {
    for (JsonNode m : client.path("protocolMappers")) {
      JsonNode config = m.path("config");
      if ("user_id".equals(config.path("claim.name").asText())
          && "true".equals(config.path("access.token.claim").asText())) {
        return true;
      }
    }
    return false;
  }
}
