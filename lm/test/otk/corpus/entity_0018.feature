Feature: entity-apply wrong-role-apply [seed=18]
  Scenario: apply with lm_user role must be rejected with 403 (not 200)
    Given an entity manifest for variant "wrong-role-apply" [seed=18]
    When I apply the entity manifest as lm_user
    Then the entity apply response status is 403
    And wrong-role-apply must not return 200
