Feature: view-render wrong-role [seed=13]
  Scenario: render with a viewer-only token must be refused with 403 (lm_user required)
    Given a view manifest for variant "wrong-role" [seed=13]
    When I render the view as viewer
    Then the view render response status is 403
    And wrong-role-view-render must not return 200
