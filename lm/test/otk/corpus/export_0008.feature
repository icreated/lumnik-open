Feature: export managed/wrong-api-version-refused [seed=8]
  Scenario: kind:Export enforces its apiVersion
    Given the export case "managed/wrong-api-version-refused" is prepared [seed=8]
    When I apply its export manifest with lm
    Then the export apply is refused and nothing is stored
    And no lm output echoed the export sentinel
