package io.lumnik.hub.connector.rls;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

/** Boots the platform against the RLS-enforced Postgres (non-owner lumnik_app default DS). */
public class RlsEnforcedProfile implements QuarkusTestProfile {
  @Override
  public List<TestResourceEntry> testResources() {
    return List.of(new TestResourceEntry(RlsEnforcedPgResource.class));
  }

  /**
   * application.properties pins
   * {@code %test.quarkus.datasource."readonly".jdbc.transactions=DISABLED} deliberately (see its own comment: "%test ONLY, and deliberately so — do NOT promote this to
   * all profiles") because the plain {@code %test} DevServices container connects as a
   * BYPASSRLS-equivalent owner, so JTA enlistment never mattered there — {@code SET LOCAL
   * app.current_tenant} being silently discarded between statements on an autocommit connection
   * is invisible when RLS itself is bypassed by ownership.
   *
   * <p>This profile connects the readonly datasource as the genuine non-owner {@code
   * lumnik_readonly} role instead, so the same DISABLED setting is no longer harmless: without
   * enlistment, {@link io.lumnik.hub.sql.TenantRlsScope#applyTo} silently unbinds the tenant and
   * every RLS-scoped readonly read returns zero rows for every tenant (fails closed, not a leak —
   * but it also makes it impossible to prove real cross-tenant isolation through this datasource).
   * {@code jdbc.transactions} is build-time-fixed, so a {@code QuarkusTestResourceLifecycleManager}
   * map (runtime-only) cannot override it — confirmed by "is set to 'enabled' but it is build
   * time fixed to 'DISABLED'" when tried. {@code getConfigOverrides()} runs at augmentation time,
   * before that value is frozen, so re-enabling it ONLY here — one opt-in {@code @TestProfile},
   * not "all profiles" — is what actually closes this harness's blind spot for the readonly
   * datasource, the same way {@code RlsEnforcedPgResource} already does for admin/default.
   */
  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("quarkus.datasource.\"readonly\".jdbc.transactions", "enabled");
  }
}
