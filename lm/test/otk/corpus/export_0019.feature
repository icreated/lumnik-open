Feature: export managed/reserved-column-refused [seed=19]
  Scenario: an internal column cannot be exported
    Given the export case "managed/reserved-column-refused" is prepared [seed=19]
    When I apply its export manifest with lm
    Then the export apply is refused and nothing is stored
    And no lm output echoed the export sentinel
