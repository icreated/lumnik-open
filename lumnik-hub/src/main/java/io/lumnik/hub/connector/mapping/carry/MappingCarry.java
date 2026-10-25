package io.lumnik.hub.connector.mapping.carry;

import java.util.Map;

/**
 * What a mapping's carrying cell hooks hold after the last source row a chunk consumed (#1452): one map of
 * column → value per declaration ({@code cell[<index>]}), under the fingerprint of the mapping that produced it.
 */
public record MappingCarry(String fingerprint, Map<String, Map<String, Object>> declarations) {}
