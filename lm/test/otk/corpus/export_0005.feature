Feature: export managed/url-credential-redacted [seed=5]
  Scenario: credentials in a destination URL never reach the terminal when the manifest is read back
    Given the export case "managed/url-credential-redacted" is prepared [seed=5]
    When I apply its export manifest with lm
    Then lm export get masks the endpoint credential
    And no lm output echoed the export sentinel
