Feature: entity-resolution stale-resolution-swap-refused [seed=12]
  Scenario: re-evaluation must never silently move an already-resolved canonical id onto another
    Given the resolution fixture "stale-resolution-swap-refused" is seeded [seed=12]
    When I run a resolution pass on "otk_res_customer"
    And I corrupt the fixture's first row to collide with its second row's resolved key
    And I run a resolution pass on "otk_res_customer"
    Then the prior resolution is refused and recorded, not silently swapped
