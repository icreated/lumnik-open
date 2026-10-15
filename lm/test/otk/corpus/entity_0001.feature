Feature: entity-cli resolve-ambiguous-assert-same-assert-distinct-history [seed=1]
  Scenario: lm entity resolve/ambiguous/assert-same/assert-distinct/history clear two genuine ambiguities end-to-end
    Given the entity CLI fixture is seeded with two genuine ambiguities [seed=1]
    When I drive the entity resolution CLI doors end-to-end
    Then lm entity ambiguous no longer lists either fixture identity
    And lm entity history shows both fixture identities were cleared by an assertion
