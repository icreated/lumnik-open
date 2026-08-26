Feature: OTK adversarial apply (seed=17)
  As OTK I persist an adversarial csv-file manifest through the hub
  and require it to create exactly the right connector, or nothing at all.

  Scenario: persist a csv-file manifest with a hack attack (env-template-in-path) on field 'spec.transport.path' (seed=17)
    Given the generated manifest for seed 17
    When I run "lm apply" on it
    Then lm is probed for robustness
    And lm output is probed for text glitches
    And lm output is probed for leaks
    And the hub state matches the apply outcome
    And the connector is cleaned up
    And the hub schema is intact
