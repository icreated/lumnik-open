package io.lumnik.extraction.spi.entity;

import java.util.List;

/**
 * Proposes candidate entities for a {@code propose:} cascade rule (#613/#621 design doc §9). A
 * provider never decides: the hub's cascade applies {@code acceptAbove}/{@code reviewAbove} and the
 * crosswalk to every proposal, exactly as it does for a native {@code exact:} rule's candidates.
 */
public interface CandidateProvider {

  /** Matches the manifest's {@code propose.provider} string (design doc §4). */
  String name();

  /** @throws CandidateProviderException if this provider cannot answer right now. */
  List<ProposedCandidate> propose(CandidateQuery query) throws CandidateProviderException;
}
