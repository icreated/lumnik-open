Feature: entity-apply sqli-in-table [seed=2]
  Scenario: apply manifest with SQL injection in table name must never cause a 500 (quoteIdent must be used)
    Given an entity manifest for variant "sqli-in-table" [seed=2]
    When I apply the entity manifest as lm_admin
    Then the entity apply response status is not 500
