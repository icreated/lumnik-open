Feature: view-render unknown-column [seed=3]
  Scenario: render a view naming a column not in the scope card must be refused with 400 (honesty line at the app layer)
    Given a view manifest for variant "unknown-column" [seed=3]
    When I render the view
    Then the view render response status is 400
