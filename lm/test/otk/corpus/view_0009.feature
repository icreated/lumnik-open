Feature: view-render unknown-scope [seed=9]
  Scenario: render a view on an unknown scope must return 404 (no card — run discovery first)
    Given a view manifest for variant "unknown-scope" [seed=9]
    When I render the view
    Then the view render response status is 404
