Feature: OTK adversarial ingestion (seed=9)
  As OTK I ingest an adversarial csv-file source through the hub
  and require every source row to be accounted for, the table to match
  the run's claim, and nothing to leak or corrupt.

  Scenario: ingest a csv-file source with a logical attack (type-confusion) in 'row' (seed=9)
    Given the generated manifest and adversarial source for seed 9
    When I apply and run the connector
    Then the run reaches a terminal status
    And every source row is accounted for
    And the landed rows match the run's reported output
    And lm output is probed for robustness, text glitches, and leaks
    And the ingested data is cleaned up
    And the hub schema is intact
