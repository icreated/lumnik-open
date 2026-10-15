Feature: export oneshot/exact-bytes-to-stdout [seed=10]
  Scenario: -o - streams the hub's exact bytes, unfiltered
    Given the export case "oneshot/exact-bytes-to-stdout" is prepared [seed=10]
    When I download entity "otk_customer" with lm to stdout
    Then the download holds the hub's exact snapshot bytes
    And no lm output echoed the export sentinel
