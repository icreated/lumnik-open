Feature: export managed/inline-credential-refused [seed=3]
  Scenario: an inline credential is refused by field name, and the refusal never echoes its value
    Given the export case "managed/inline-credential-refused" is prepared [seed=3]
    When I apply its export manifest with lm
    Then the export apply is refused and nothing is stored
    And no lm output echoed the export sentinel
