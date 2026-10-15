Feature: export managed/unknown-key-refused [seed=16]
  Scenario: an unknown spec key is refused, not ignored
    Given the export case "managed/unknown-key-refused" is prepared [seed=16]
    When I apply its export manifest with lm
    Then the export apply is refused and nothing is stored
    And no lm output echoed the export sentinel
