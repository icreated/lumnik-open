Feature: export managed/unknown-column-refused [seed=6]
  Scenario: a column the entity does not declare is refused at apply, not at run
    Given the export case "managed/unknown-column-refused" is prepared [seed=6]
    When I apply its export manifest with lm
    Then the export apply is refused and nothing is stored
    And no lm output echoed the export sentinel
