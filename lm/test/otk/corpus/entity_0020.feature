Feature: entity-apply sqli-in-name [seed=20]
  Scenario: apply manifest with SQL injection in name must never cause a 500 (quoteIdent must be used)
    Given an entity manifest for variant "sqli-in-name" [seed=20]
    When I apply the entity manifest as lm_admin
    Then the entity apply response status is not 500
