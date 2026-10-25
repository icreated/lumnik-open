package io.lumnik.hub.connector.mapping.carry;

import io.lumnik.hub.audit.CanonicalHash;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;

import java.util.Map;

/**
 * Which mapping a carry belongs to (#1452): the hook lists and the on-error policy, hashed canonically. The cursor
 * survives a re-apply and {@code DefinitionHash} only guards a queued run, so a carry records this, and a different
 * mapping never reuses it.
 */
public final class MappingFingerprint {

  private MappingFingerprint() {}

  public static String of(MappingSpec spec) {
    return CanonicalHash.of(Map.of("dataset", spec.dataset(), "row", spec.row(), "cell", spec.cell(),
        "onError", spec.onError()));
  }
}
