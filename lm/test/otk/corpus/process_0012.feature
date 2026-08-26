Feature: process-apply empty-when [seed=12]
  Scenario: apply a process with an empty when: must be refused with 400 (a condition is required)
    Given a process manifest for variant "empty-when" [seed=12]
    When I apply the process manifest as lm_admin
    Then the process apply response status is 400
