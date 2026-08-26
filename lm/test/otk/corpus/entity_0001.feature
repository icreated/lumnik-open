Feature: entity-apply reserved-column [seed=1]
  Scenario: apply manifest with reserved column _conflicts must be rejected with 400
    Given an entity manifest for variant "reserved-column" [seed=1]
    When I apply the entity manifest as lm_admin
    Then the entity apply response status is 400
