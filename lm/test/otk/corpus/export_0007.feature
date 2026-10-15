Feature: export managed/foreign-destination-field-refused [seed=7]
  Scenario: an S3 field on an SFTP destination is refused, not silently dropped
    Given the export case "managed/foreign-destination-field-refused" is prepared [seed=7]
    When I apply its export manifest with lm
    Then the export apply is refused and nothing is stored
    And no lm output echoed the export sentinel
