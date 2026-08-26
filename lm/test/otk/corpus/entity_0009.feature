Feature: entity-apply wrong-kind [seed=9]
  Scenario: apply wrong-kind manifest must be rejected with 400
    Given an entity manifest for variant "wrong-kind" [seed=9]
    When I apply the entity manifest as lm_admin
    Then the entity apply response status is 400
