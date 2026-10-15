Feature: export managed/delivers [seed=13]
  Scenario: a run to a reachable destination ends Completed with its receipt
    Given the export case "managed/delivers" is prepared [seed=13]
    When I apply its export manifest with lm
    And I run the applied export with lm and wait for its outcome
    Then the export run ended Completed with a receipt
    And no lm output echoed the export sentinel
