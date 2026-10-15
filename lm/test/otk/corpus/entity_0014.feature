Feature: entity-read cross-tenant-read [seed=14]
  Scenario: GET entity with tenant-B token must return 0 rows (RLS isolation enforced)
    Given the seed entity "otk_customer" exists for entity read [seed=14]
    When I read entity "otk_customer" with query "" as cross-tenant
    Then the entity read response status is 200
    And the entity read response contains no rows (RLS)
