Feature: entity-read cross-tenant-read [seed=14]
  Scenario: GET another tenant's entity with a tenant-B token must return 404: tenant B sees neither its rows nor its manifest
    Given the seed entity "otk_customer" exists for entity read [seed=14]
    When I read entity "otk_customer" with query "" as cross-tenant
    Then the entity read response status is 404
