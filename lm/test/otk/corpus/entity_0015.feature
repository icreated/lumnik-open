Feature: entity-apply no-sources [seed=15]
  Scenario: apply manifest with empty sources must be rejected with 400
    Given an entity manifest for variant "no-sources" [seed=15]
    When I apply the entity manifest as lm_admin
    Then the entity apply response status is 400
