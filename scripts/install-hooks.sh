#!/usr/bin/env bash
# scripts/install-hooks.sh
#
# Symlinks tracked hooks from scripts/hooks/ into .git/hooks/.
# Run once after cloning: ./scripts/install-hooks.sh

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOKS_SRC="$ROOT/scripts/hooks"
HOOKS_DST="$ROOT/.git/hooks"

for hook in "$HOOKS_SRC"/*; do
  name=$(basename "$hook")
  chmod +x "$hook"
  ln -sf "../../scripts/hooks/$name" "$HOOKS_DST/$name"
  echo "✓  Installed hook: $name"
done

echo ""
echo "Hooks installed. Generate initial context snapshot:"
echo "  ./scripts/gen-ai-context.sh"
