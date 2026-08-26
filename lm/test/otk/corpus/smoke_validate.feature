Feature: OTK validate smoke
  As OTK I keep one non-mutating validate path alive as a sanity anchor.

  Scenario: validate keeps the non-mutating contract (seed=1)
    Given the generated manifest for seed 1
    When I run "lm validate" on it
    Then lm is probed for robustness
    And lm output is probed for text glitches
    And lm output is probed for leaks
    And the hub gains no connector
