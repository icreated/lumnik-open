Feature: process-inbox ack-wrong-role [seed=18]
  Scenario: POST /api/inbox/{id}/ack with a viewer-only token must be refused with 403 (lm_user required)
    Given the inbox variant "ack-wrong-role" [seed=18]
    When I ack inbox id "999999999" as viewer
    Then the inbox ack response status is 403
