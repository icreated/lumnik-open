Feature: process-apply emit-not-decision [seed=3]
  Scenario: apply a process emitting a non-decision.* type must be refused with 400 (governed taxonomy)
    Given a process manifest for variant "emit-not-decision" [seed=3]
    When I apply the process manifest as lm_admin
    Then the process apply response status is 400
