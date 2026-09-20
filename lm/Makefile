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

.PHONY: pty
pty: build ## Run the manual pty lane (test/pty/no-echo-secrets.sh): the keyboard half of the secret prompts, which `go test` cannot reach — needs a tty allocator, so it is not in CI and not in `test`
	./test/pty/no-echo-secrets.sh

.PHONY: otk-unit
otk-unit: ## Run the hub-free OTK unit tests (oracle/generator/checks/runner + tagged helpers); the live TestOTK is always excluded so the run is deterministic regardless of ambient LUMNIK_URL/OTK_GOLD
	go test -tags otk -skip '^TestOTK$$' ./test/otk/...

.PHONY: otk
otk: build otk-unit ## Run OTK: hub-free unit tests, then the declared-tier battery (needs LUMNIK_URL; OTK_TIER overrides lm/test/otk/tier)
	go test -tags otk ./test/otk/ -run TestOTK -v

.PHONY: otk-gold
otk-gold: build otk-unit ## Run the OTK Gold gate (every catalogue case; needs LUMNIK_URL + OTK_DB_DSN + real-auth env)
	OTK_GOLD=1 go test -tags otk ./test/otk/ -run TestOTK -v

.PHONY: otk-calibrate
otk-calibrate: build otk-unit ## Prove the battery DETECTS: run one fault through control -> injected -> restored (set OTK_FAULT; needs a DB + real-auth env). It PACKAGES and BOOTS the hub itself and REMOVES lumnik-hub/target when done.
	go run -tags otk ./test/otk/cmd/otkcal
