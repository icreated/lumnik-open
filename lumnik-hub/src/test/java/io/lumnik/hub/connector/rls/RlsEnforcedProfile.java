package io.lumnik.hub.connector.rls;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;

/** Boots the platform against the RLS-enforced Postgres (non-owner lumnik_app default DS). */
public class RlsEnforcedProfile implements QuarkusTestProfile {
  @Override
  public List<TestResourceEntry> testResources() {
    return List.of(new TestResourceEntry(RlsEnforcedPgResource.class));
  }
}
