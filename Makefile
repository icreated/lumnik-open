.DEFAULT_GOAL := help

help: ## List available targets (the default — bare `make` is safe)
	@grep -hE '^[a-zA-Z0-9_-]+:.*##' $(MAKEFILE_LIST) | sort | \
	awk -F':.*## ' '{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ── docs site (mkdocs-material) ──────────────────────────────────────────────
.PHONY: help docs-setup docs-serve docs-build
docs-setup: ## Create .venv-docs and install the mkdocs toolchain
	python3 -m venv .venv-docs
	.venv-docs/bin/pip install -r requirements-docs.txt

docs-serve: ## Serve the docs site locally with live reload
	.venv-docs/bin/mkdocs serve

docs-build: ## Build the docs site strictly (fails on warnings), then check what it rendered
	.venv-docs/bin/mkdocs build --strict
	@bash scripts/check-docs-render.sh
