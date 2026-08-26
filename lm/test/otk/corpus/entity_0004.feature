Feature: entity-read sqli-in-filter [seed=4]
  Scenario: GET with SQL injection in canonical column filter must return 200 and safe rows (no injected content)
    Given the seed entity "otk_customer" exists for entity read [seed=4]
    When I read entity "otk_customer" with query "name=%27%3B+DROP+TABLE--"
    Then the entity read response status is 200
    And the entity read response is safe
