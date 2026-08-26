Feature: OTK adversarial auth (seed=1)
  As OTK I present an adversarial JWT to the platform API
  and require the hub to reject it (or isolate the tenant) — never to leak or escalate.

  Scenario: present a token with the bad-issuer defect (seed=1)
    Given the hub rejects the adversarial token for seed 1
