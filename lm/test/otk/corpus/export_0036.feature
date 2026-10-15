Feature: export oneshot/exact-bytes-to-file [seed=36]
  Scenario: the one-shot download lands the hub's exact bytes under the final name and nothing else
    Given the export case "oneshot/exact-bytes-to-file" is prepared [seed=36]
    When I download entity "otk_customer" with lm to a new file
    Then the download holds the hub's exact snapshot bytes
    And no lm output echoed the export sentinel
