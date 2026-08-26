Feature: process-inbox wrong-role-read [seed=20]
  Scenario: GET /api/inbox with a viewer-only token must be refused with 403 (lm_user required)
    Given the inbox variant "wrong-role-read" [seed=20]
    When I read the inbox as viewer
    Then the inbox response status is 403
