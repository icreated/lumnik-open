Feature: export managed/roundtrip [seed=1]
  Scenario: a valid export applies, is listed, and reads back as its stored manifest
    Given the export case "managed/roundtrip" is prepared [seed=1]
    When I apply its export manifest with lm
    Then the export apply succeeds and lm lists and gets it
    And no lm output echoed the export sentinel
