Feature: process-apply wrong-role [seed=14]
  Scenario: apply a valid process as lm_user (not lm_admin) must be refused with 403 (no role escalation)
    Given a process manifest for variant "wrong-role" [seed=14]
    When I apply the process manifest as lm_user
    Then the process apply response status is 403
    And wrong-role-process-apply must not return 200
