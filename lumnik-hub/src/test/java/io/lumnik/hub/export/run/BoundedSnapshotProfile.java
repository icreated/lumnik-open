package io.lumnik.hub.export.run;

import io.lumnik.hub.connector.rls.RlsEnforcedProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link RlsEnforcedProfile} with the snapshot-read transaction bounded at 120 s instead of the
 * shipped 1800 s, for {@code ExportAcceptanceS3IT} only. At 1800 s one stall in that IT cost the
 * nightly half an hour (#755); a stalled run's read staying open until this timeout is the leading
 * hypothesis, not an established cause. Harness containment: it bounds what a stall costs, it does
 * not explain it (#761). The shared profile and the production default are untouched.
 */
public class BoundedSnapshotProfile extends RlsEnforcedProfile {
  static final String SNAPSHOT_TIMEOUT_PROPERTY = "lumnik.export.snapshot.timeout-seconds";
  static final int SNAPSHOT_TIMEOUT_SECONDS = 120;

  @Override
  public Map<String, String> getConfigOverrides() {
    Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
    overrides.put(SNAPSHOT_TIMEOUT_PROPERTY, String.valueOf(SNAPSHOT_TIMEOUT_SECONDS));
    return overrides;
  }
}
