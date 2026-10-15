Feature: entity-resolution ambiguous-mailbox-never-merges [seed=16]
  Scenario: two independently-known identities sharing a mailbox must never silently merge into one
    Given the resolution fixture "ambiguous-mailbox-never-merges" is seeded [seed=16]
    When I run a resolution pass on "otk_res_customer"
    Then the fixture's outcome is ambiguous, never merged
