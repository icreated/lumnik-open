Feature: OTK adversarial apply (seed=14)
  As OTK I persist an adversarial csv-file manifest through the hub
  and require it to create exactly the right connector, or nothing at all.

  Scenario: persist a csv-file manifest with a textual attack (null-byte) on field 'name' (seed=14)
    Given the generated manifest for seed 14
    When I run "lm apply" on it
    Then lm is probed for robustness
    And lm output is probed for text glitches
    And lm output is probed for leaks
    And the hub state matches the apply outcome
    And the connector is cleaned up
    And the hub schema is intact
