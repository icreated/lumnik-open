package io.lumnik.hub.entity.resolution;

/**
 * Thrown by {@link CascadeResolver#assertSame} when honouring the assertion would put two rows of one
 * physical {@code source} on one {@code entity_id}. {@code core.entity_identity}'s own {@code
 * uq_entity_identity_cardinality} constraint ("at most one identity per source") makes that
 * structurally impossible — recording the assertion anyway would return {@code 204} while the merge
 * silently never takes effect, surfacing only later as an unhandled {@code PSQLException} the next
 * time either row is reprocessed (icreated/lumnik#735). Refused upfront here instead, at the one door
 * that sees both refs before the assertion is ever written, in two cases: both refs share one source
 * (#735), or each ref's entity already holds another row of the other ref's source, so neither row
 * can ever join the other (#889). Caught by {@link io.lumnik.hub.entity.EntityAssertionResource} and
 * translated to a 400.
 */
public class SameSourceAssertionException extends RuntimeException {

  public SameSourceAssertionException(String source) {
    super("leftRef and rightRef belong to the same source '" + source
        + "' — an identity can hold at most one entry per source");
  }

  /** Neither row can join the other's entity (#889). */
  SameSourceAssertionException(String leftSource, String rightSource) {
    super("neither identity can join the other's entity: rightRef's entity already holds another '"
        + leftSource + "' row and leftRef's entity another '" + rightSource
        + "' row — an entity can hold at most one entry per source");
  }
}
