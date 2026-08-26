Feature: view-render cross-tenant-no-card [seed=1]
  Scenario: render with a tenant-B token must return 404 — the SchemaCard is tenant-scoped, so a foreign tenant cannot even resolve the scope (isolation at the card layer, before any row is read)
    Given a view manifest for variant "cross-tenant-no-card" [seed=1]
    When I render the view as cross-tenant
    Then the view render response status is 404
