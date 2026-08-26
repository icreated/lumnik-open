// Package otk is the lumnik OTK ("Отдел Технического Контроля") harness:
// it generates adversarial lm scenarios, replays them via godog against a
// running hub, and grades survival on a SIEM-style severity scale gated by
// Bronze/Silver/Gold tiers.
//
// Run from the lm/ directory: `cd lm && make otk` (the Makefile lives at
// lm/Makefile, not the repo root). The make target runs the hub-free unit tests
// first, then the tagged godog battery, which requires LUMNIK_URL pointing at a
// hub started with the non-owner application DB role.
package otk
