Feature: entity-read unknown-entity [seed=20]
  Scenario: GET unknown entity must return 404
    Given the seed entity "ghost_otk_seed" exists for entity read [seed=20]
    When I read entity "ghost_otk_seed" with query ""
    Then the entity read response status is 404
