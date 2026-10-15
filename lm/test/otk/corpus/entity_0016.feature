Feature: entity-apply no-match [seed=16]
  Scenario: apply manifest with empty match must be rejected with 400
    Given an entity manifest for variant "no-match" [seed=16]
    When I apply the entity manifest as lm_admin
    Then the entity apply response status is 400
