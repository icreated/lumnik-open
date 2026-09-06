package io.lumnik.hub.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.lumnik.identity.user.User;
import io.lumnik.identity.user.UserRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled realm and the hub ship one identity in two files; this test holds them together.
 *
 * <p><b>Every tenant a shipped-realm user claims exists and is live</b> (V13 seeds 1, V17 seeds
 * 11), and <b>every user that carries a {@code user_id} has a live hub row under that id, in that
 * tenant, with that login</b> (V16, V17). The filter binds a claim without requiring a row, so a
 * missing one is silent everywhere except the doors that load the user. Add a claiming user to
 * the realm and forget the seed, or renumber either side, and this fails naming the user.
 *
 * <p>Only {@code lumnik-realm.json} is on this axis: {@code lumnik-realm-native.json} has no
 * {@code user_id} protocol mapper, so its tokens never emit the claim and its users ride JIT.
 */
@QuarkusTest
class ShippedRealmUserSeedIT {

  @Inject UserRepository users;
  @Inject TenantRepository tenants;

  /** What one shipped user claims. {@code userId} is null for a JIT-shaped user. */
  record Claim(String username, long tenantId, Long userId) {}

  @Test
  void every_tenant_a_shipped_realm_user_claims_exists_and_is_live() {
    List<String> broken = new ArrayList<>();
    List<Claim> claims = shippedUsers();

    for (Claim c : claims) {
      Optional<Tenant> row = QuarkusTransaction.requiringNew().call(() -> tenants.findByIdOptional(c.tenantId()));
      if (row.isEmpty()) {
        broken.add(c.username() + " claims tenant_id=" + c.tenantId() + " — no such tenant");
      } else if (row.get().isArchived() || row.get().isDeleted()) {
        broken.add(c.username() + " claims tenant_id=" + c.tenantId() + " — tenant exists but is not live");
      }
    }

    assertThat(broken)
        .as("the bundled realm's users claim tenants the hub does not hold live — a JIT login "
            + "dies on fk_user_tenant, a claim login lands in a tenant with no rows. Seed the "
            + "tenant in an identity migration")
        .isEmpty();
    assertThat(claims).as("an empty sweep is not a clean one").isNotEmpty();
  }

  @Test
  void every_shipped_realm_user_that_claims_a_user_id_has_its_row() {
    List<String> broken = new ArrayList<>();
    int checked = 0;

    for (Claim c : shippedUsers()) {
      if (c.userId() == null) continue;   // JIT-shaped user: not on this axis
      checked++;

      Optional<User> row = QuarkusTransaction.requiringNew().call(() -> users.findByIdOptional(c.userId()));
      if (row.isEmpty()) {
        broken.add(c.username() + " claims user_id=" + c.userId() + " — no such row");
        continue;
      }
      User user = row.get();
      if (user.getTenantId() != c.tenantId())
        broken.add(c.username() + " claims tenant_id=" + c.tenantId() + " but row " + c.userId()
            + " belongs to tenant " + user.getTenantId());
      if (!c.username().equals(user.getLogin()))
        broken.add(c.username() + " — row " + c.userId() + " has login '" + user.getLogin() + "'");
      if (!user.isLoginActive())
        broken.add(c.username() + " — row " + c.userId() + " exists but is not login-active");
    }

    assertThat(broken)
        .as("the bundled realm asserts hub user ids the hub does not hold live — seed the row "
            + "in an identity migration, or drop the user_id attribute so the user rides JIT")
        .isEmpty();
    assertThat(checked).as("an empty sweep is not a clean one").isPositive();
  }

  /** The realm's users as claims. A malformed entry fails here, by name, before either axis runs. */
  private static List<Claim> shippedUsers() {
    List<Claim> out = new ArrayList<>();
    for (JsonNode u : ShippedRealms.read(ShippedRealms.REALM).path("users")) {
      String name = u.path("username").asText();
      String userId = attribute(u, "user_id");
      out.add(new Claim(name, integer(name, "tenant_id", attribute(u, "tenant_id")),
          userId == null ? null : integer(name, "user_id", userId)));
    }
    return out;
  }

  /** A user attribute's first value (Keycloak exports {@code ["11"]}); null when absent or blank. */
  private static String attribute(JsonNode user, String name) {
    String s = user.path("attributes").path(name).path(0).asText("");
    return s.isBlank() ? null : s.strip();
  }

  /** Jackson's asLong() renders "abc" as 0 — a malformed export must be named, not hidden as id 0. */
  private static long integer(String username, String attr, String raw) {
    assertThat(raw).as("%s: %s is missing — the filter cannot bind it", username, attr).isNotNull();
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new AssertionError(username + ": " + attr + " = '" + raw + "' is not an integer, the filter refuses it");
    }
  }
}
