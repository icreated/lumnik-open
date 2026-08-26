Feature: view-render wrong-kind [seed=10]
  Scenario: render a manifest whose kind is not View must be refused with 400
    Given a view manifest for variant "wrong-kind" [seed=10]
    When I render the view
    Then the view render response status is 400
