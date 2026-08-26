Feature: process-inbox ack-foreign [seed=11]
  Scenario: POST /api/inbox/{id}/ack for an id that is not the caller-tenant's decision must return 404 and leak nothing
    Given the inbox variant "ack-foreign" [seed=11]
    When I ack inbox id "999999999"
    Then the inbox ack response status is 404
