Feature: view-render filter-sqli [seed=11]
  Scenario: render with SQL injection in a contains-filter must return 200 and safe rows (bound param, no reflection)
    Given a view manifest for variant "filter-sqli" [seed=11]
    When I render the view with filter "name~'; DROP TABLE--"
    Then the view render response status is 200
    And the view render response is safe
