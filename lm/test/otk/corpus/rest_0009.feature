Feature: OTK adversarial apply — REST (seed=9)
  As OTK I submit an adversarial rest-generic manifest through the hub
  and require it to create exactly the right connector, or nothing at all.

  Scenario: apply a rest-generic manifest with a logical attack (cursor-no-path) on field 'spec.endpoints[].pagination' (seed=9)
    Given the generated REST manifest for seed 9
    When I run "lm apply" on it
    Then lm is probed for robustness
    And lm output is probed for text glitches
    And lm output is probed for leaks
    And the hub state matches the apply outcome
    And the connector is cleaned up
    And the hub schema is intact
