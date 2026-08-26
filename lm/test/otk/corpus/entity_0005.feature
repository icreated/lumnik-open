Feature: entity-apply zero-width-name [seed=5]
  Scenario: apply manifest with zero-width space in name must never cause a 500
    Given an entity manifest for variant "zero-width-name" [seed=5]
    When I apply the entity manifest as lm_admin
    Then the entity apply response status is not 500
