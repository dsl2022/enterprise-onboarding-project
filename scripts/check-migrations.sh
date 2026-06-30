#!/usr/bin/env bash
# Soft guard for the expand/contract migration rule (DECISIONS ADR-0025).
#
# Every deploy runs the OLD and NEW app image concurrently against the SAME database (Phase 8 rolling
# deploy: min-healthy 100 / max 200; Phase 9 blue/green: the bake window). A migration that is destructive,
# tightening, or lock-heavy therefore risks breaking — or blocking — the still-serving previous version.
#
# This guard scans the migration files a change ADDS or MODIFIES (relative to a base ref) and emits GitHub
# Actions WARNINGS for risky DDL. It is a SOFT WARN, NOT A BLOCK: destructive DDL is legitimate in a
# contract (N+1) release — the guard exists to make expand/contract a conscious, reviewed decision and to
# point the author at ADR-0025. It always exits 0.
#
# Usage: scripts/check-migrations.sh [BASE_REF]
#   BASE_REF defaults to origin/main. Compares <BASE_REF>...HEAD (changes since the merge-base).
#
# Known limitation (acceptable for a SOFT guard): scanning is line-based, so DDL split across multiple lines
# (e.g. `ALTER ... \n ... TYPE`) and `/* ... */` block comments are not understood — only `--` line comments
# are stripped. A miss here just falls through to human review; it never produces a false BLOCK (exit is
# always 0). Most Flyway migrations here are single-statement-per-line with `--` comments.
set -euo pipefail

BASE_REF="${1:-origin/main}"
MIG_GLOB='app/src/main/resources/db/migration/V*.sql'

# GitHub Actions step-summary sink (no-op locally).
summary() { if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then echo -e "$1" >>"$GITHUB_STEP_SUMMARY"; fi; }

# Emit a GH warning annotation (and a plain line for local runs).
warn() { # file line message
  echo "::warning file=$1,line=$2::$3"
  echo "  ⚠ $1:$2 — $3"
  summary "- \`$1:$2\` — $3"
}

# Changed/added migration files since the base. --diff-filter=AM = Added or Modified (ignore deletes/renames
# of historical files). Fall back to all tracked migrations if the base ref isn't fetched (e.g. shallow CI
# checkout) — better to over-scan than to silently skip.
# (read-loop instead of mapfile so macOS bash 3.2 can run this too)
FILES=()
while IFS= read -r _line; do
  [[ -n "$_line" ]] && FILES+=("$_line")
done < <(
  git diff --name-only --diff-filter=AM "${BASE_REF}...HEAD" -- "$MIG_GLOB" 2>/dev/null \
    || git ls-files -- "$MIG_GLOB"
)

if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "check-migrations: no added/modified migrations vs ${BASE_REF} — nothing to scan."
  exit 0
fi

echo "check-migrations: scanning ${#FILES[@]} migration file(s) vs ${BASE_REF} (ADR-0025 expand/contract)."
summary "### Migration guard (ADR-0025 — expand/contract)\n"
summary "Scanned: ${FILES[*]}\n"

hits=0
scan() { # file label egrep-pattern
  local file="$1" label="$2" pat="$3"
  # Strip line comments so a '-- DROP ...' note doesn't trip the guard; keep line numbers via grep -n on the
  # original, then re-test the de-commented text.
  while IFS=: read -r ln rest; do
    [[ -z "$ln" ]] && continue
    # ignore matches that sit after a -- comment marker
    local code="${rest%%--*}"
    if echo "$code" | grep -Eiq "$pat"; then
      warn "$file" "$ln" "$label"
      hits=$((hits + 1))
    fi
  done < <(grep -nEi "$pat" "$file" || true)
}

# Like scan(), but only flags a line that matches POS and does NOT match NEG (portable stand-in for a
# negative lookahead — e.g. "CREATE INDEX" but not "CONCURRENTLY", on a single line).
scan_unless() { # file label pos-pattern neg-pattern
  local file="$1" label="$2" pos="$3" neg="$4"
  while IFS=: read -r ln rest; do
    [[ -z "$ln" ]] && continue
    local code="${rest%%--*}"
    if echo "$code" | grep -Eiq "$pos" && ! echo "$code" | grep -Eiq "$neg"; then
      warn "$file" "$ln" "$label"
      hits=$((hits + 1))
    fi
  done < <(grep -nEi "$pos" "$file" || true)
}

for f in "${FILES[@]}"; do
  [[ -f "$f" ]] || continue
  # Destructive / tightening DDL — must wait for the contract (N+1) release once the old image is gone.
  # (DROP NOT NULL / DROP DEFAULT are relaxing, hence backward-compatible, and intentionally NOT listed.)
  scan "$f" "DROP of a schema object — destructive; defer to the contract (N+1) release (ADR-0025)" \
    'drop[[:space:]]+(table|column|constraint|index|schema|type|view|sequence|materialized)'
  scan "$f" "Column RENAME — breaks the old image still reading the old name; expand+backfill instead (ADR-0025)" \
    'rename[[:space:]]+(column|to)'
  scan "$f" "ALTER COLUMN ... TYPE — table rewrite + breaks old readers; add a new column instead (ADR-0025)" \
    'alter[[:space:]]+column[[:space:]]+[^;]*type'
  scan "$f" "SET NOT NULL — fails/blocks if the old image still writes NULLs; add NOT NULL only after backfill (ADR-0025)" \
    'set[[:space:]]+not[[:space:]]+null'
  scan "$f" "TRUNCATE — destructive; not deploy-safe (ADR-0025)" \
    'truncate[[:space:]]'
  # Lock-heavy DDL — shape-compatible but can block the old image during the deploy window.
  scan_unless "$f" "CREATE INDEX without CONCURRENTLY — locks writes; use CONCURRENTLY (Flyway: outside-transaction) on prod-sized tables (ADR-0025)" \
    'create[[:space:]]+(unique[[:space:]]+)?index' 'concurrently'
  scan "$f" "ADD COLUMN ... NOT NULL — a volatile DEFAULT rewrites the whole table & blocks the old image; verify it's a constant default (ADR-0025)" \
    'add[[:space:]]+column[[:space:]]+[^;]*not[[:space:]]+null'
done

if [[ $hits -eq 0 ]]; then
  echo "check-migrations: clean — no destructive or lock-heavy DDL flagged."
  summary "\n✅ No destructive or lock-heavy DDL flagged."
else
  echo "check-migrations: ${hits} item(s) flagged for expand/contract review (ADR-0025). This is advisory, not a failure."
  summary "\n${hits} item(s) flagged for review. See **DECISIONS.md → ADR-0025**. This is advisory — destructive DDL is fine in a contract (N+1) release; confirm this PR is that release."
fi

# Soft by design: never fail the build.
exit 0
