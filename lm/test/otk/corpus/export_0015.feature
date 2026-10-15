Feature: export managed/scope-refusal [seed=15]
  Scenario: an identity bound to another scope can neither read nor run the export, and starts nothing
    Given the export case "managed/scope-refusal" is prepared [seed=15]
    When I apply its export manifest with lm
    And a scope-bound identity lacking scope "otk" reads and runs the applied export with lm
    Then lm reported the uniform scope refusal
    And no export run was recorded by the refused identity
    And no lm output echoed the export sentinel
