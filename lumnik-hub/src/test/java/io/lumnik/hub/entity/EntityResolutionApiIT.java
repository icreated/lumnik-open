package io.lumnik.hub.entity;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.entity.resolution.CascadeResolver;
import io.lumnik.hub.entity.resolution.EntityFingerprintService;
import io.lumnik.hub.entity.resolution.EntityIdentityRepository;
import io.lumnik.hub.entity.resolution.EntityResolutionState;
import io.lumnik.hub.entity.resolution.ResolutionResult;
import io.lumnik.hub.entity.resolution.RuleIdentity;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #613 lot #622, the ambiguity/assertion doors driven end to end against real Postgres — the
 * unit tests in {@code EntityResolutionReadResourceTest}/{@code EntityAssertionResourceTest} pin
 * the gate order with fakes; these prove the actual queries and the real cross-tenant refusal —
 * a genuine {@code fk_entity_assertion*} foreign-key violation, translated to 404, that a resolver
 * fake could never exercise for real.
 */
@QuarkusTest
class EntityResolutionApiIT {

  private static final String ENTITY_TYPE = "customer";
  private static final AtomicLong NEXT_TENANT = new AtomicLong(220_000);

  @Inject EntityIdentityRepository repository;
  @Inject CascadeResolver resolver;
  @Inject EntityFingerprintService fingerprintService;

  @BeforeEach
  void context() {
    CurrentRolesHolder.set(Set.of("lm_user", "lm_admin"));
  }

  @AfterEach
  void clear() {
    CurrentTenantHolder.clear();
    CurrentRolesHolder.clear();
    TenantContextFilter.CURRENT_USER.remove();
  }

  private long freshTenant() {
    return NEXT_TENANT.getAndIncrement();
  }

  private static EntityManifest resolvableCustomer() {
    return new EntityManifest("Entity", "customer", null, List.of("code"),
        List.of(new EntityManifest.Source("connector.t_src", Map.of("code", "code"))),
        List.of(), null, Map.of(),
        new EntityManifest.Resolution(Map.of("connector.t_src", "code"), List.of()));
  }

  private EntityResolutionReadResource readResource() {
    EntityResolutionReadResource res = new EntityResolutionReadResource();
    res.registry = new EntityRegistry() {
      @Override public EntityManifest findReloadingIfMissing(String name) { return resolvableCustomer(); }
    };
    res.repository = repository;
    return res;
  }

  private EntityAssertionResource assertionResource() {
    EntityAssertionResource res = new EntityAssertionResource();
    res.registry = new EntityRegistry() {
      @Override public EntityManifest findReloadingIfMissing(String name) { return resolvableCustomer(); }
    };
    res.resolver = resolver;
    return res;
  }

  private void asTenant(long tenantId) {
    CurrentTenantHolder.set(tenantId);
    TenantContextFilter.CURRENT_USER.set(99L);
  }

  /** Direct-SQL seed of an already-{@code resolved} row AND its matching fingerprint key —
   *  mirrors {@code CascadeResolverTest.seedResolvedRow}: the one way to put two DIFFERENT
   *  entities behind the SAME fingerprint, which the resolver's own accept-on-one-candidate rule
   *  can never produce by itself, on purpose. */
  private void seedResolvedRow(long tenant, String source, String sourceId, UUID entityId,
      EntityManifest.CascadeRule rule, Map<String, String> rawFieldValues) {
    repository.inTransaction(c -> {
      repository.upsertIdentity(c, tenant, ENTITY_TYPE, source, sourceId,
          EntityResolutionState.RESOLVED, entityId, "automatic");
      return null;
    });
    EntityManifest.Exact ex = rule.exact();
    RuleIdentity identity = fingerprintService.ruleIdentity(rule.id(), ex.fields(), ex.normalize());
    byte[] fp = fingerprintService.fingerprint(tenant, ENTITY_TYPE, rule.id(), ex.fields(), ex.normalize(),
        rawFieldValues).orElseThrow();
    repository.inTransaction(c -> {
      repository.upsertKey(c, tenant, ENTITY_TYPE, source, sourceId, rule.id(), identity.ruleVersion(), fp);
      return null;
    });
  }

  @Test
  void the_ambiguous_door_lists_a_row_the_cascade_actually_produced() {
    long tenant = freshTenant();
    asTenant(tenant);
    EntityManifest.CascadeRule email = new EntityManifest.CascadeRule(
        "email", new EntityManifest.Exact(List.of("email"), List.of("clean", "lower")), null);
    UUID entityA = UUID.randomUUID();
    UUID entityB = UUID.randomUUID();
    seedResolvedRow(tenant, "erp", "cust-a", entityA, email, Map.of("email", "shared@example.com"));
    seedResolvedRow(tenant, "crm", "contact-b", entityB, email, Map.of("email", "shared@example.com"));

    ResolutionResult result = resolver.resolve(tenant, ENTITY_TYPE, "web", "lead-c",
        List.of(email), Map.of("email", "shared@example.com"), null);
    assertThat(result.state()).isEqualTo(EntityResolutionState.AMBIGUOUS);

    Response r = readResource().ambiguous("customer", 100, 0);

    assertThat(r.getStatus()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    List<EntityIdentityRepository.AmbiguousIdentity> rows =
        (List<EntityIdentityRepository.AmbiguousIdentity>) r.getEntity();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).source()).isEqualTo("web");
    assertThat(rows.get(0).sourceId()).isEqualTo("lead-c");
    assertThat(rows.get(0).candidates()).containsExactlyInAnyOrder(entityA, entityB);
  }

  @Test
  void the_history_door_returns_the_full_ledger_for_one_identity() {
    long tenant = freshTenant();
    asTenant(tenant);
    EntityManifest.CascadeRule email = new EntityManifest.CascadeRule(
        "email", new EntityManifest.Exact(List.of("email"), List.of("clean", "lower")), null);
    resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-a",
        List.of(email), Map.of("email", "alice@example.com"), null);

    Response r = readResource().history("customer", "erp", "cust-a");

    assertThat(r.getStatus()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    List<EntityIdentityRepository.EvidenceEvent> events =
        (List<EntityIdentityRepository.EvidenceEvent>) r.getEntity();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).outcome()).isEqualTo(EntityResolutionState.RESOLVED);
  }

  @Test
  void the_history_door_is_404_for_an_identity_never_seen() {
    long tenant = freshTenant();
    asTenant(tenant);

    Response r = readResource().history("customer", "erp", "never-seen");

    assertThat(r.getStatus()).isEqualTo(404);
  }

  @Test
  void the_assertion_door_records_a_same_assertion_between_two_real_refs() {
    long tenant = freshTenant();
    asTenant(tenant);
    EntityManifest.CascadeRule email = new EntityManifest.CascadeRule(
        "email", new EntityManifest.Exact(List.of("email"), List.of("clean", "lower")), null);
    ResolutionResult a = resolver.resolve(tenant, ENTITY_TYPE, "erp", "cust-a",
        List.of(email), Map.of("email", "a@example.com"), null);
    ResolutionResult b = resolver.resolve(tenant, ENTITY_TYPE, "crm", "contact-b",
        List.of(email), Map.of("email", "b@example.com"), null);
    // Two unrelated emails both fresh-mint immediately (RESOLVED, each its own new entity) — so
    // the assertion below links two ALREADY-resolved sides, not two still-open ones.
    assertThat(a.state()).isEqualTo(EntityResolutionState.RESOLVED);
    assertThat(b.state()).isEqualTo(EntityResolutionState.RESOLVED);
    UUID refA = repository.inTransaction(c ->
        repository.find(c, tenant, ENTITY_TYPE, "erp", "cust-a").orElseThrow().identityRef());
    UUID refB = repository.inTransaction(c ->
        repository.find(c, tenant, ENTITY_TYPE, "crm", "contact-b").orElseThrow().identityRef());

    Response r = assertionResource().same("customer",
        new EntityAssertionResource.AssertionRequest(refA, refB, null, null, "same person, two mailboxes"));

    assertThat(r.getStatus()).isEqualTo(204);
    List<UUID> partners = repository.inTransaction(c ->
        repository.assertedSameEntityIds(c, tenant, ENTITY_TYPE, refA).stream()
            .map(EntityIdentityRepository.AssertedPartner::entityId).toList());
    assertThat(partners).containsExactly(b.entityId());
  }

  @Test
  void the_assertion_door_refuses_a_cross_tenant_ref_with_404_not_a_raw_fk_violation() {
    long tenantA = freshTenant();
    long tenantB = freshTenant();
    asTenant(tenantB);
    UUID refB = repository.inTransaction(c -> {
      repository.upsertIdentity(c, tenantB, ENTITY_TYPE, "crm", "contact-b",
          EntityResolutionState.UNRESOLVED_NO_RULE, null, "automatic");
      return repository.find(c, tenantB, ENTITY_TYPE, "crm", "contact-b").orElseThrow().identityRef();
    });
    asTenant(tenantA);
    UUID refA = repository.inTransaction(c -> {
      repository.upsertIdentity(c, tenantA, ENTITY_TYPE, "erp", "cust-a",
          EntityResolutionState.UNRESOLVED_NO_RULE, null, "automatic");
      return repository.find(c, tenantA, ENTITY_TYPE, "erp", "cust-a").orElseThrow().identityRef();
    });

    Response r = assertionResource().same("customer",
        new EntityAssertionResource.AssertionRequest(refA, refB, null, null, "cross-tenant mistake"));

    assertThat(r.getStatus()).isEqualTo(404);
  }

  @Test
  void the_assertion_door_names_both_rows_by_their_source_handle() {
    // #1087: no door hands out a resolved row's identity_ref, so an integrator names the row the
    // way the entity view's _handle and `lm entity history` do — <source>:<source id>.
    long tenant = freshTenant();
    asTenant(tenant);
    EntityManifest.CascadeRule email = new EntityManifest.CascadeRule(
        "email", new EntityManifest.Exact(List.of("email"), List.of("lower")), null);
    resolver.resolve(tenant, ENTITY_TYPE, "erp", "C-003", List.of(email), Map.of("email", "a@example.com"), null);
    ResolutionResult b = resolver.resolve(tenant, ENTITY_TYPE, "crm", "9003",
        List.of(email), Map.of("email", "b@example.com"), null);
    UUID refA = repository.inTransaction(c ->
        repository.find(c, tenant, ENTITY_TYPE, "erp", "C-003").orElseThrow().identityRef());

    Response r = assertionResource().same("customer",
        new EntityAssertionResource.AssertionRequest(null, null, "erp:C-003", "crm:9003", "shared mailbox"));

    assertThat(r.getStatus()).isEqualTo(204);
    List<UUID> partners = repository.inTransaction(c ->
        repository.assertedSameEntityIds(c, tenant, ENTITY_TYPE, refA).stream()
            .map(EntityIdentityRepository.AssertedPartner::entityId).toList());
    assertThat(partners).containsExactly(b.entityId());
  }

  @Test
  void a_source_handle_of_another_tenant_is_404_naming_the_handle() {
    long tenantA = freshTenant();
    long tenantB = freshTenant();
    asTenant(tenantB);
    repository.inTransaction(c -> repository.upsertIdentity(c, tenantB, ENTITY_TYPE, "crm", "9003",
        EntityResolutionState.UNRESOLVED_NO_RULE, null, "automatic"));
    asTenant(tenantA);
    repository.inTransaction(c -> repository.upsertIdentity(c, tenantA, ENTITY_TYPE, "erp", "C-003",
        EntityResolutionState.UNRESOLVED_NO_RULE, null, "automatic"));

    Response r = assertionResource().same("customer",
        new EntityAssertionResource.AssertionRequest(null, null, "erp:C-003", "crm:9003", null));

    assertThat(r.getStatus()).isEqualTo(404);
    assertThat(r.getEntity().toString()).contains("crm:9003");
  }
}
