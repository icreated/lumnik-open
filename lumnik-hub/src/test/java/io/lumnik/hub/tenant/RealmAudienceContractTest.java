package io.lumnik.hub.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-drift contract on the bundled Keycloak realms: every public client whose tokens
 * call the hub MUST carry the {@code aud=lumnik-backend} audience mapper — the hub
 * (staging/prod) validates {@code quarkus.oidc.token.audience=lumnik-backend} and
 * rejects tokens without it. Found live by the mobile spike (2026-07-11): lumnik-spa
 * tokens were valid Keycloak tokens the hub could never accept.
 *
 * (Same scar family as the Helm script↔chart knob drift — config files drift apart
 * silently unless a test holds them together.)
 */
class RealmAudienceContractTest {

  @ParameterizedTest(name = "{0} — {1}")
  @CsvSource({
      "../infra/keycloak/lumnik-realm.json,        lm-cli",
      "../infra/keycloak/lumnik-realm.json,        lumnik-spa",
      "../infra/keycloak/lumnik-realm-native.json, lm-cli",
      "../infra/keycloak/lumnik-realm-native.json, lumnik-spa",
  })
  void hub_facing_client_carries_the_backend_audience(String realmFile, String clientId) {
    JsonNode client = ShippedRealms.client(ShippedRealms.read(Path.of(realmFile)), clientId);

    boolean hasBackendAudience = false;
    for (JsonNode m : client.path("protocolMappers")) {
      if ("oidc-audience-mapper".equals(m.path("protocolMapper").asText())
          && "lumnik-backend".equals(m.path("config").path("included.client.audience").asText())
          // Keycloak includes the audience in the ACCESS token only when this flag is
          // literally "true" (absent = excluded) — and the access token is what the hub
          // validates, so a mapper without it satisfies nothing.
          && "true".equals(m.path("config").path("access.token.claim").asText())) {
        hasBackendAudience = true;
      }
    }
    assertThat(hasBackendAudience)
        .as("%s in %s must map aud=lumnik-backend into the ACCESS token "
                + "(access.token.claim=true) — the hub rejects its tokens otherwise",
            clientId, realmFile)
        .isTrue();
  }

  /**
   * SUPERSEDES the C1 posture (tribunal 2026-07-25), which carved out {@code integrator}
   * as the one shipped user allowed a static password. That ruling predates the decision
   * to publish this repository: {@code infra/keycloak/*.json} is on the open-edition
   * allowlist, so a working {@code integrator}/{@code integrator} credential would become
   * world-readable the moment the repo goes public in September, handing anyone
   * {@code lm_admin} + {@code lm_integrator} on a fresh install. Ratified 2026-08-11 (see
   * {@code docs/superpowers/plans/2026-08-11-security-audit-pre-flip.md} task 9): go all
   * the way — NO shipped user, {@code integrator} included, carries a static credential.
   * Every one of them (both {@code hubadmin} and now {@code integrator}) gets its password
   * per install instead, set by the launchers via the Keycloak admin API — see
   * {@code deploy/selfhost/up.sh}'s {@code set_realm_user_password} and
   * {@code deploy/helm/up.sh}'s {@code set_kc_user_password}.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "../infra/keycloak/lumnik-realm.json",
      "../infra/keycloak/lumnik-realm-native.json",
  })
  void no_shipped_user_carries_a_static_password(String realmFile) {
    JsonNode realm = ShippedRealms.read(Path.of(realmFile));
    for (JsonNode user : realm.path("users")) {
      String name = user.path("username").asText();
      assertThat(user.path("credentials").isMissingNode() || user.path("credentials").isEmpty())
          .as("user %s in %s must ship WITHOUT a static credential — "
                  + "the launcher sets its password per install", name, realmFile)
          .isTrue();
    }
  }
}
