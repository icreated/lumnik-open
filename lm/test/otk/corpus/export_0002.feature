Feature: export managed/bad-schedule-refused [seed=2]
  Scenario: an impossible cron schedule is refused at apply
    Given the export case "managed/bad-schedule-refused" is prepared [seed=2]
    When I apply its export manifest with lm
    Then the export apply is refused and nothing is stored
    And no lm output echoed the export sentinel
