package io.lumnik.hub.fidelity;

import io.lumnik.extraction.spi.ask.SchemaCard;

/** A generated honesty probe: a SchemaCard (with a declared domain) + a SQL the model "emitted",
 *  and whether the guard must refuse it. */
public record Scenario(long seed, Kind kind, SchemaCard card, String sql, boolean expectRefusal) {}
