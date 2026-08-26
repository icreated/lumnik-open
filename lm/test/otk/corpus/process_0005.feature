Feature: process-apply unknown-event [seed=5]
  Scenario: apply a process on an unknown trigger event must be refused with 400 (data.row.changed is the only v1 trigger)
    Given a process manifest for variant "unknown-event" [seed=5]
    When I apply the process manifest as lm_admin
    Then the process apply response status is 400
