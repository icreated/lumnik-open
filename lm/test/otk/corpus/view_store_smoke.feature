Feature: OTK saved-view store contract
  As OTK I keep the saved-view store's curated write path (#140) honest: a
  valid manifest applies, is discoverable, round-trips as YAML, and renders —
  a reserved name and an unknown scope are refused before any row is read —
  and the uniform scope-403 refusal (#131/#138) holds on this door too.

  Scenario: apply a valid manifest, discover it, fetch its YAML, and render it
    Given a store manifest for variant "store-roundtrip"
    When I apply the view manifest to the store
    Then the view apply response status is 200
    And the saved view "otk-store-view" appears in the list
    And getting the saved view "otk-store-view" returns its YAML
    And I render the applied view
    And the applied-view render response status is 200

  Scenario: apply refuses a reserved name with 400
    Given a store manifest for variant "store-reserved-name"
    When I apply the view manifest to the store
    Then the view apply response status is 400

  Scenario: apply refuses an unknown scope with 404 (no schema — run discovery first)
    Given a store manifest for variant "store-unknown-scope"
    When I apply the view manifest to the store
    Then the view apply response status is 404

  Scenario: a scope-bound identity lacking the manifest's scope gets the uniform 403
    Given a store manifest for variant "store-roundtrip"
    When a scope-bound identity lacking scope "otk" renders the manifest
    Then the response is the uniform scope refusal
