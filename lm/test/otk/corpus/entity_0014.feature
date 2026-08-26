Feature: entity-apply huge-name [seed=14]
  Scenario: apply manifest with 5000-char name must never cause a 500
    Given an entity manifest for variant "huge-name" [seed=14]
    When I apply the entity manifest as lm_admin
    Then the entity apply response status is not 500
