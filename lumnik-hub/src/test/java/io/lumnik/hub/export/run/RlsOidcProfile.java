package io.lumnik.hub.export.run;

import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link RlsEnforcedProfile} with real bearer JWTs (dev-bypass off): the one harness where a
 * role or scope refusal and a database-enforced tenant boundary are both real on the same run.
 */
public class RlsOidcProfile extends RlsEnforcedProfile {
  @Override
  public Map<String, String> getConfigOverrides() {
    Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
    overrides.putAll(new NoBypassOidcProfile().getConfigOverrides());
    return overrides;
  }
}
