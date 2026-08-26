Feature: view-render filter-unknown-column [seed=5]
  Scenario: render with a filter on a column the view does not show must be refused with 400
    Given a view manifest for variant "filter-unknown-column" [seed=5]
    When I render the view with filter "zzz_not_a_column=1"
    Then the view render response status is 400
