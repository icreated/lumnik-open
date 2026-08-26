Feature: entity-read huge-limit [seed=11]
  Scenario: GET with huge limit must return 200 and at most 1000 rows
    Given the seed entity "otk_customer" exists for entity read [seed=11]
    When I read entity "otk_customer" with query "limit=999999"
    Then the entity read response status is 200
    And the entity read response has at most 1000 rows
