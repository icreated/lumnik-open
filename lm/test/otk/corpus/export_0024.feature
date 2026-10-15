Feature: export oneshot/existing-file-refused [seed=24]
  Scenario: an existing file is never overwritten without --force
    Given the export case "oneshot/existing-file-refused" is prepared [seed=24]
    When I download entity "otk_customer" with lm to an existing file
    Then lm refused the download and the existing file is untouched
    And no lm output echoed the export sentinel
