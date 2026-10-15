package io.lumnik.hub.entity;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.entity.resolution.CascadeResolver;
import io.lumnik.hub.entity.resolution.SameSourceAssertionException;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.lumnik.hub.testsupport.ResolvableManifests.registryServing;
import static io.lumnik.hub.testsupport.ResolvableManifests.resolvable;
import static io.lumnik.hub.testsupport.ResolvableManifests.withoutResolution;
import static io.lumnik.hub.testsupport.WebApplicationExceptions.assertRefusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #613 lot #622's write door into {@code CascadeResolver.assertSame}/{@code .assertDistinct}
 * (#619). Two things this resource must own, neither of which the service layer can: {@code
 * assertedBy} comes ONLY from the authenticated identity, never the request body; a ref that
 * does not belong to this tenant/entity type is refused with a clear 404 — translated from the
 * {@code fk_entity_assertion*} foreign-key violation {@code CascadeResolverTest
 * .an_assertion_on_a_pair_of_another_tenant_is_refused} pins at the service layer (that guard
 * does the real enforcement; this door only dresses its failure, same posture as {@code
 * WorkflowApplyResource.isUniqueViolation}).
 *
 * <p>The gate ({@code ResolutionGate.requireResolvable}) refuses by THROWING a {@code
 * WebApplicationException} — calling the method directly, as every test here does, sees the
 * raised exception itself rather than a returned {@code Response}.
 */
class EntityAssertionResourceTest {

  /** Throws a foreign-key violation the same shape {@code EntityIdentityRepository.run} wraps a
   *  real one in — the resolver never gets this far for real; a mismatched ref is caught here. */
  private static CascadeResolver resolverRefusingForeignRef() {
    return new CascadeResolver() {
      @Override public void assertSame(long tenantId, String entityType, UUID leftRef, UUID rightRef,
          String assertedBy, String reason) {
        throw fkViolation();
      }
      @Override public void assertDistinct(long tenantId, String entityType, UUID leftRef, UUID rightRef,
          String assertedBy, String reason) {
        throw fkViolation();
      }
      private RuntimeException fkViolation() {
        return new RuntimeException("EntityIdentityRepository.upsertAssertion: ...",
            new SQLException("insert or update on table \"entity_assertion\" violates foreign key "
                + "constraint \"fk_entity_assertion_left\"", "23503"));
      }
    };
  }

  private static EntityAssertionResource resourceServing(EntityManifest m, CascadeResolver resolver) {
    EntityAssertionResource res = new EntityAssertionResource();
    res.registry = registryServing(m);
    res.resolver = resolver;
    return res;
  }

  @BeforeEach
  void tenant() {
    CurrentTenantHolder.set(1L);
    TenantContextFilter.CURRENT_USER.set(42L);
  }

  @AfterEach
  void clear() {
    CurrentTenantHolder.clear();
    CurrentRolesHolder.clear();
    TenantContextFilter.CURRENT_USER.remove();
  }

  private static final EntityAssertionResource.AssertionRequest VALID_PAIR =
      new EntityAssertionResource.AssertionRequest(UUID.randomUUID(), UUID.randomUUID(), null, null, "shared mailbox");

  @Test
  void bound_user_foreign_entity_same_is_403() {
    CurrentRolesHolder.set(Set.of("lm_admin", "scope:stock"));
    EntityAssertionResource res = resourceServing(resolvable("customer", List.of("clients")), null);
    assertRefusal(() -> res.same("customer", VALID_PAIR), 403);
  }

  @Test
  void unknown_entity_same_is_404() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    EntityAssertionResource res = resourceServing(null, null);
    assertRefusal(() -> res.same("nope", VALID_PAIR), 404);
  }

  @Test
  void entity_without_resolution_policy_same_is_400() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    EntityAssertionResource res = resourceServing(withoutResolution("customer", List.of()), null);
    assertRefusal(() -> res.same("customer", VALID_PAIR), 400);
  }

  @Test
  void a_missing_ref_in_the_body_is_400() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    var body = new EntityAssertionResource.AssertionRequest(null, UUID.randomUUID(), null, null, "reason");
    Response r = resourceServing(resolvable("customer", List.of()), null).same("customer", body);
    assertThat(r.getStatus()).isEqualTo(400);
  }

  @Test
  void no_authenticated_user_in_context_is_401_and_never_reaches_the_resolver() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    TenantContextFilter.CURRENT_USER.remove();
    EntityAssertionResource res = resourceServing(resolvable("customer", List.of()), null);
    assertRefusal(() -> res.same("customer", VALID_PAIR), 401);
  }

  @Test
  void an_unknown_ref_for_this_tenant_and_type_is_404_not_a_raw_fk_violation() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    EntityAssertionResource res = resourceServing(resolvable("customer", List.of()), resolverRefusingForeignRef());
    Response r = res.same("customer", VALID_PAIR);
    assertThat(r.getStatus()).isEqualTo(404);
  }

  @Test
  void a_same_source_refusal_from_the_resolver_is_400_not_swallowed_or_a_500() {
    // icreated/lumnik#735: CascadeResolver.assertSame refuses two refs sharing one physical source
    // before the assertion is ever written — this door translates that refusal into a clear 400,
    // the same posture as an_unknown_ref_for_this_tenant_and_type_is_404_not_a_raw_fk_violation.
    CurrentRolesHolder.set(Set.of("lm_admin"));
    CascadeResolver sameSource = new CascadeResolver() {
      @Override public void assertSame(long tenantId, String entityType, UUID leftRef, UUID rightRef,
          String assertedBy, String reason) {
        throw new SameSourceAssertionException("erp");
      }
    };
    EntityAssertionResource res = resourceServing(resolvable("customer", List.of()), sameSource);
    Response r = res.same("customer", VALID_PAIR);
    assertThat(r.getStatus()).isEqualTo(400);
  }

  @Test
  void a_non_fk_failure_from_the_resolver_is_not_swallowed_as_a_404() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    CascadeResolver broken = new CascadeResolver() {
      @Override public void assertSame(long tenantId, String entityType, UUID leftRef, UUID rightRef,
          String assertedBy, String reason) {
        throw new IllegalStateException("boom");
      }
    };
    EntityAssertionResource res = resourceServing(resolvable("customer", List.of()), broken);
    assertThatThrownBy(() -> res.same("customer", VALID_PAIR)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void assertedby_never_comes_from_the_request_body() {
    // AssertionRequest has no assertedBy field at all — this is a compile-time guarantee, not a
    // runtime one; asserted here so the contract is pinned somewhere a reader will find it.
    assertThatThrownBy(() -> EntityAssertionResource.AssertionRequest.class.getDeclaredField("assertedBy"))
        .isInstanceOf(NoSuchFieldException.class);
  }

  @Test
  void bound_user_granted_entity_same_passes_the_gate_and_reaches_the_resolver() {
    CurrentRolesHolder.set(Set.of("lm_admin", "scope:stock"));
    EntityAssertionResource res = resourceServing(resolvable("articles", List.of("stock")), null);
    // past every gate and validation, the resolver write is reached — proven by the NPE this
    // deliberately-null field produces (a wrong short-circuit would return normally instead).
    assertThatThrownBy(() -> res.same("articles", VALID_PAIR))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("this.resolver");
  }

  @Test
  void bound_user_granted_entity_distinct_passes_the_gate_and_reaches_the_resolver() {
    CurrentRolesHolder.set(Set.of("lm_admin", "scope:stock"));
    EntityAssertionResource res = resourceServing(resolvable("articles", List.of("stock")), null);
    assertThatThrownBy(() -> res.distinct("articles", VALID_PAIR))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("this.resolver");
  }

  /** A resolver that knows one handle and records what reached assertSame. */
  private static class RecordingResolver extends CascadeResolver {
    final UUID known = UUID.randomUUID();
    UUID left;
    UUID right;
    @Override public Optional<UUID> identityRefOf(long tenantId, String entityType, String source,
        String sourceId) {
      return "t_erp".equals(source) && "C-003".equals(sourceId) ? Optional.of(known) : Optional.empty();
    }
    @Override public void assertSame(long tenantId, String entityType, UUID leftRef, UUID rightRef,
        String assertedBy, String reason) {
      left = leftRef;
      right = rightRef;
    }
  }

  private static EntityAssertionResource.AssertionRequest handles(UUID leftRef, String left, String right) {
    return new EntityAssertionResource.AssertionRequest(leftRef, null, left, right, null);
  }

  @Test
  void a_source_handle_reaches_the_resolver_as_its_identity_ref_and_mixes_with_a_ref() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    RecordingResolver resolver = new RecordingResolver();
    UUID other = UUID.randomUUID();
    var body = new EntityAssertionResource.AssertionRequest(null, other, "t_erp:C-003", null, null);

    Response r = resourceServing(resolvable("customer", List.of()), resolver).same("customer", body);

    assertThat(r.getStatus()).isEqualTo(204);
    assertThat(resolver.left).isEqualTo(resolver.known);
    assertThat(resolver.right).isEqualTo(other);
  }

  @Test
  void a_source_handle_the_crosswalk_never_saw_is_404_naming_it() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    Response r = resourceServing(resolvable("customer", List.of()), new RecordingResolver())
        .same("customer", handles(null, "t_erp:C-003", "t_crm:9999"));
    assertThat(r.getStatus()).isEqualTo(404);
    assertThat(r.getEntity().toString()).contains("t_crm:9999");
  }

  @Test
  void a_malformed_source_handle_is_400_naming_the_expected_form() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    for (String bad : List.of("t_crm", ":9003", "t_crm:", " ", " t_crm:9003", "t crm:9003", "t_crm:  ")) {
      Response r = resourceServing(resolvable("customer", List.of()), new RecordingResolver())
          .same("customer", handles(null, "t_erp:C-003", bad));
      assertThat(r.getStatus()).as(bad).isEqualTo(400);
      assertThat(r.getEntity().toString()).as(bad).contains("<source table>:<source id>");
    }
  }

  @Test
  void a_side_given_both_as_a_ref_and_as_a_handle_is_400() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    Response r = resourceServing(resolvable("customer", List.of()), new RecordingResolver())
        .same("customer", handles(UUID.randomUUID(), "t_erp:C-003", "t_erp:C-003"));
    assertThat(r.getStatus()).isEqualTo(400);
  }

  @Test
  void a_source_id_may_itself_contain_a_colon() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    RecordingResolver resolver = new RecordingResolver() {
      @Override public Optional<UUID> identityRefOf(long tenantId, String entityType, String source,
          String sourceId) {
        return "t_erp".equals(source) && "A:1".equals(sourceId) ? Optional.of(known) : Optional.empty();
      }
    };
    Response r = resourceServing(resolvable("customer", List.of()), resolver)
        .same("customer", new EntityAssertionResource.AssertionRequest(null, UUID.randomUUID(), "t_erp:A:1", null, null));
    assertThat(r.getStatus()).isEqualTo(204);
    assertThat(resolver.left).isEqualTo(resolver.known);
  }

  @Test
  void a_source_id_is_taken_verbatim_so_a_padded_one_is_looked_up_not_trimmed() {
    // A source id is compared as the source holds it — a CHAR-padded ERP key keeps its spaces —
    // so only a BLANK id is malformed; "C-003 " is a different row from "C-003", hence a 404.
    CurrentRolesHolder.set(Set.of("lm_admin"));
    Response r = resourceServing(resolvable("customer", List.of()), new RecordingResolver())
        .same("customer", handles(null, "t_erp:C-003 ", "t_erp:C-003"));
    assertThat(r.getStatus()).isEqualTo(404);
  }
}
