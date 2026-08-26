Feature: view-render table-not-in-scope [seed=6]
  Scenario: render a view whose table is not in the scope must be refused with 400
    Given a view manifest for variant "table-not-in-scope" [seed=6]
    When I render the view
    Then the view render response status is 400
