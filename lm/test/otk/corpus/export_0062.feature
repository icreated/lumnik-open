Feature: export oneshot/unknown-entity-no-artifact [seed=62]
  Scenario: a refused download leaves no file and no partial file
    Given the export case "oneshot/unknown-entity-no-artifact" is prepared [seed=62]
    When I download entity "otk_ghost_entity" with lm to a new file
    Then lm refused the download and left no file behind
    And no lm output echoed the export sentinel
