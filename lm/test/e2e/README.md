# lm E2E smoke tests

Manual sequence to verify Plan 1 deliverables against a live Quarkus.

## Prerequisites

In one terminal:

    mvn quarkus:dev -pl lumnik-hub -am

In another terminal:

    cd lm && go build -o ./lm ./cmd/lm
    mkdir -p ~/.lm
    cat > ~/.lm/config.yaml <<EOF
    current-context: local
    contexts:
      local:
        server: http://localhost:8080
        token: dev-token
    EOF

## Smoke sequence (read paths)

    ./lm version                        # expect: lm version dev (commit unknown)
    ./lm config view                    # expect: yaml with current-context: local
    ./lm module list                    # expect: list of installed modules
    ./lm migration status               # expect: per-schema versions
    ./lm rag status                     # expect: corpus stats
    ./lm hook list                      # expect: hooks (possibly empty array)
    ./lm connector list                 # expect: connectors (possibly empty)
    ./lm dlq list                       # expect: dead letters (possibly empty)

## Smoke for write paths

    cat > /tmp/c.yaml <<EOF
    tenantId: 1
    name: smoke-test
    typeId: hello-world
    enabled: true
    config:
      count: 10
    EOF
    ./lm connector apply -f /tmp/c.yaml
    ./lm connector list                 # expect: smoke-test listed
    ./lm connector run smoke-test       # expect: run scheduled: scheduled:...
    sleep 2
    ./lm connector delete smoke-test

## Automated smoke (optional)

Tests gated by the `e2e` build tag, requiring `QUARKUS_URL` env var:

    cd lm
    QUARKUS_URL=http://localhost:8080 go test -tags=e2e ./test/e2e/... -v

## TUI smoke

With Quarkus running and `~/.lm/config.yaml` configured:

    ./lm                # default: launches TUI on :modules
    ./lm ui             # explicit

In the TUI, verify each navigation works:

- `:modules`         → list appears
- `:sources`         → list appears; press `r` to refresh
- `:runs`            → list appears; create a run via CLI in another tab and watch it land here
- `:dlq`             → list appears (may be empty)
- `:hooks`           → list appears
- `:migrations`      → list appears with per-schema status
- `?`                → help modal opens
- `q`                → quit
- `:sources` then `e` on a row → $EDITOR opens; save+quit → connector PUT
- `:sources` then `^R` on a row → run scheduled (verify via `:runs`)
- header indicator → `[CONNECTING]`, then `[LIVE✓]` once the hub accepts the event stream (`[RECONNECTING]` after a drop, its reason in the footer)
