Feature: process-apply blank-scope [seed=6]
  Scenario: apply a process with no scope must be refused with 400 (spec.scope is required)
    Given a process manifest for variant "blank-scope" [seed=6]
    When I apply the process manifest as lm_admin
    Then the process apply response status is 400
