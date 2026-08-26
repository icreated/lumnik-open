Feature: OTK adversarial auth (seed=17)
  As OTK I present an adversarial JWT to the platform API
  and require the hub to reject it (or isolate the tenant) — never to leak or escalate.

  Scenario: present a token with the wrong-role defect (seed=17)
    Given the hub rejects the adversarial token for seed 17
