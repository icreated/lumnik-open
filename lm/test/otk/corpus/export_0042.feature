Feature: export managed/destination-failure [seed=42]
  Scenario: a destination that refuses the upload ends the run Failed with a coded reason, never Completed, never echoing its credential
    Given the export case "managed/destination-failure" is prepared [seed=42]
    When I apply its export manifest with lm
    And I run the applied export with lm and wait for its outcome
    Then the export run ended Failed with failure code "publication"
    And no lm output echoed the export sentinel
