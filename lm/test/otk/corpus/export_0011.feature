Feature: export oneshot/scope-refusal-no-artifact [seed=11]
  Scenario: a scope refusal passes through lm and produces no artifact
    Given the export case "oneshot/scope-refusal-no-artifact" is prepared [seed=11]
    When a scope-bound identity lacking scope "otk" downloads entity "otk_customer" with lm to a new file
    Then lm reported the uniform scope refusal
    And lm refused the download and left no file behind
    And no lm output echoed the export sentinel
