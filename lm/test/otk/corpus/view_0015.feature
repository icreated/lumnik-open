Feature: view-render filter-malformed [seed=15]
  Scenario: render with a malformed filter (no operator) must be refused with 400
    Given a view manifest for variant "filter-malformed" [seed=15]
    When I render the view with filter "no_operator_here"
    Then the view render response status is 400
