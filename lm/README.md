# lm — the lumnik CLI & TUI

The integrator's single Go binary: a kubectl-style CLI plus a k9s-style TUI.

## Build

```bash
cd lm && go build -o lm ./cmd/lm
# put ./lm on your PATH, e.g.:  sudo mv lm /usr/local/bin/
```

## Connect & sign in

```bash
lm config set-context selfhost --server http://localhost:8080 \
    --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context selfhost
lm login    # device flow: prints a URL + code
```

## Use

```bash
lm --help          # the full command tree — every command self-documents
lm                 # bare = launch the TUI (`:` opens the palette, `?` shows keys)
```

The full command and TUI reference lives in
[`docs/cli/lm-reference.md`](../docs/cli/lm-reference.md); the product docs site starts at
[`docs/index.md`](../docs/index.md).

## Develop

```bash
go build ./... && go test ./...
```
