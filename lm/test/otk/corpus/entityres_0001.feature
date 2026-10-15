Feature: entity-resolution distinct-assertion-survives-replay [seed=1]
  Scenario: a distinct assertion must not be undone by a later replay
    Given the resolution fixture "distinct-assertion-survives-replay" is seeded [seed=1]
    When I run a resolution pass on "otk_res_customer"
    And I assert distinct on the fixture's linked pair
    And I run a resolution pass on "otk_res_customer"
    And I run a resolution pass on "otk_res_customer"
    Then the distinct assertion still holds after the replay
