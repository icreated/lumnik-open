.PHONY: build
build: ## Build the lm binary at lm/lm
	go build -o lm ./cmd/lm

.PHONY: install
install: build ## Build and install lm into ~/.local/bin (the PATH copy — a stale one wears new hints badly)
	install -d $(HOME)/.local/bin
	install -m 0755 lm $(HOME)/.local/bin/lm

.PHONY: test
test: ## Run the whole untagged Go suite (internal/ + cmd/) — the per-PR gate; the live TestOTK is behind //go:build otk and cannot be reached from here
	go test ./...

.PHONY: otk-unit
otk-unit: ## Run the hub-free OTK unit tests (oracle/generator/checks/runner + tagged helpers); the live TestOTK is always excluded so the run is deterministic regardless of ambient LUMNIK_URL/OTK_GOLD_SEEDS
	go test -tags otk -skip '^TestOTK$$' ./test/otk/...

.PHONY: otk
otk: build otk-unit ## Run OTK: hub-free unit tests, then the declared-tier battery (needs LUMNIK_URL; OTK_TIER overrides lm/test/otk/tier)
	go test -tags otk ./test/otk/ -run TestOTK -v

.PHONY: otk-gold
otk-gold: build otk-unit ## Run the OTK Gold gate (N>=500 generated; needs LUMNIK_URL + OTK_DB_DSN + real-auth env)
	OTK_GOLD_SEEDS=$${OTK_GOLD_SEEDS:-125} go test -tags otk ./test/otk/ -run TestOTK -v
