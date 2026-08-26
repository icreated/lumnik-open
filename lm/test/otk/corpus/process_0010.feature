Feature: process-inbox huge-limit [seed=10]
  Scenario: GET /api/inbox with a huge limit must return 200 and at most 200 rows (server clamp)
    Given the inbox variant "huge-limit" [seed=10]
    When I read the inbox with query "limit=999999"
    Then the inbox response status is 200
    And the inbox response has at most 200 rows
