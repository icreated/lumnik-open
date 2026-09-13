package io.lumnik.hub.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-drift contract for the IdP-verification knob.
 *
 * <p>The hub validates bearer JWTs locally against the issuer's keys and never asks the IdP whether
 * the account still exists — measured on the shipped stack: after {@code enabled=false} in Keycloak
 * the UserInfo endpoint answers 401 while the hub keeps answering 200 to the same token until it
 * expires. {@code LUMNIK_AUTH_VERIFY_WITH_IDP=true} closes that window through the one built-in
 * mechanism Quarkus offers for a JWT bearer: {@code quarkus.oidc.authentication.user-info-required}
 * makes every request call UserInfo and fail authentication on the IdP's refusal.
 *
 * <p>Three things must hold, and each one is a way the knob could rot: it must default OFF (one IdP
 * round trip per request is a cost the operator opts into); the Quarkus property must be bound to
 * the knob under {@code %prod} and {@code %staging} (the two profiles with real token validation);
 * and it must be bound nowhere else to a literal, which would turn the opt-in into a silent default.
 */
class IdpVerificationKnobContractTest {

  private static final String KNOB = "lumnik.auth.verify-with-idp";
  private static final String QUARKUS = "quarkus.oidc.authentication.user-info-required";

  private static List<String> declarations(String key) throws Exception {
    List<String> lines = Files.readAllLines(Path.of("src/main/resources/application.properties"));
    return lines.stream().map(String::strip)
        .filter(l -> !l.startsWith("#"))
        .filter(l -> l.startsWith(key + "=") || l.matches("%[^.]+\\." + Pattern.quote(key) + "=.*"))
        .toList();
  }

  @Test
  void the_knob_is_declared_once_from_its_env_var_and_defaults_off() throws Exception {
    assertThat(declarations(KNOB))
        .as("one unprofiled declaration, read from LUMNIK_AUTH_VERIFY_WITH_IDP, default false")
        .containsExactly(KNOB + "=${LUMNIK_AUTH_VERIFY_WITH_IDP:false}");
  }

  @Test
  void user_info_required_is_bound_to_the_knob_under_prod_and_staging_only() throws Exception {
    assertThat(declarations(QUARKUS))
        .as("bound to the knob in exactly the two profiles that validate real tokens; a literal "
            + "'true' anywhere would make every request pay the IdP round trip silently")
        .containsExactlyInAnyOrder(
            "%prod." + QUARKUS + "=${" + KNOB + "}",
            "%staging." + QUARKUS + "=${" + KNOB + "}");
  }
}
