#!/bin/bash
# Deterministic maturity/coverage signal for the kotoba browser ecosystem.
# POSH: pure Kotoba syntax only. NO JVM (clojure -M:test) and NO cljs (npm/shadow)
# tiers. The sole acceptance gate is `amu check <file>.kotoba --jvm-free` (the
# JVM-free compiler admission), plus the .kotoba surface inventory and the
# R2 roadmap test/impl coverage map.
# Timestamp-free. Exit 0 always: PASS/FAIL/UNMEASURED are data, not a crash.
ROOT=~/github/com-junkawasaki
AMU="$ROOT/orgs/kotoba-lang/amu/bin/amu"
BR="$ROOT/orgs/kotoba-lang/browser"
KL="$ROOT/orgs/kotoba-lang"

# ecma262.kotoba is 3367 lines / 283 embedded test-* defns. Compile is load- and
# HW-sensitive: 147s in 2026-08, 370s (real, no outer timeout) on 2026-09-07.
# The old 300s budget still produced phantom FAIL(rc=124) timeouts, which are
# environmental UNMEASURED, not compile errors. Headroom raised to 600s.
TIMEOUT=600

echo "=== 0. .kotoba surface inventory (pure Kotoba syntax) ==="
total=0
for p in browser dom-gpu html css browser-use org-ecma-international-262; do
  dir="$KL/$p"
  if [ -d "$dir" ]; then
    n=$(find "$dir" -name '*.kotoba' 2>/dev/null | wc -l | tr -d ' ')
    total=$((total+n))
    echo "$p: $n .kotoba file(s)"
  else
    echo "$p: NOT_CHECKED_OUT"
  fi
done
echo "TOTAL .kotoba: $total"

if [ "$total" -eq 0 ]; then
  echo "=== AMU GATE: NO .kotoba SURFACE TO COMPILE (browser core is still .cljc) ==="
  echo "amunote: browser/dom-gpu/browser-use carry 0 .kotoba; html/css/ecma262 have .kotoba. This repo is pre-migration."
fi

echo "=== 1. amu check --jvm-free (pure Kotoba compile, JVM-free gate) ==="
# Gate must cover the SAME dirs §0 inventories (browser-use carries history.kotoba;
# dom-gpu has 0 today). Omitting one inflates PASS by silently ungating a counted
# .kotoba. Keep this find list in sync with the §0 loop.
amufiles=$(find "$KL/org-ecma-international-262" "$KL/html" "$KL/css" \
  "$KL/browser/kotoba" "$KL/browser-use" "$KL/dom-gpu" \
  -name '*.kotoba' 2>/dev/null)
if [ -z "$amufiles" ]; then
  echo "amu gate: UNMEASURED (no .kotoba files found)"
else
  passes=0; fails=0; nfiles=0
  for f in $amufiles; do
    nfiles=$((nfiles+1))
    # amu check --jvm-free must not spawn a JVM; measure with a probe.
    r=$(timeout $TIMEOUT "$AMU" check "$f" --jvm-free 2>/dev/null)
    rc=$?
    if [ "$rc" -ne 0 ]; then
      fails=$((fails+1)); echo "  FAIL(rc=$rc) $(basename "$f")"
    else
      ok=$(printf '%s' "$r" | grep -oE ':ok (true|false)' | head -1)
      if printf '%s' "$ok" | grep -q 'true'; then
        passes=$((passes+1)); echo "  PASS $(basename "$f")"
      else
        fails=$((fails+1)); echo "  COMPILE_ERROR(no :ok true) $(basename "$f")"
      fi
    fi
  done
  echo "amu gate: $passes/$nfiles PASS jvm-free"
fi

echo "=== 2. R2 roadmap conformance coverage (README 'R2 Browser Work') ==="
# src hit = implemented; test hit = covered. capability in README but 0 test = gap.
for pair in "scanlayout:flex" "scanlayout:grid" "scanlayout:block" \
            "ime:composition" "compositor:overlay" "compositor:clip" \
            "accessbridge:aria" "windowmgr:desktop" "webapi:WebSocket" \
            "net:cookie" "net:cors" "script:module" "script:ecma262"; do
  kw="${pair%%:*}"; term="${pair##*:}"
  s=$(rg -rli "$term" "$BR/src" 2>/dev/null | wc -l | tr -d ' ')
  t=$(rg -rli "$term" "$BR/test/browser" 2>/dev/null | wc -l | tr -d ' ')
  echo "$kw($term): src=$s test=$t"
done

echo "=== 3. ecma262 differential parity (pure Kotoba engine checkpoint) ==="
ec="$KL/org-ecma-international-262/src/ecma262.kotoba"
if [ -f "$ec" ]; then
  # count in-file test-* exports (embedded kotoba conformance suite)
  tests=$(rg -c '\bdefn test-' "$ec" 2>/dev/null | head -1)
  echo "ecma262.kotoba embedded test-* defns: ${tests:-0}"
  r=$(timeout $TIMEOUT "$AMU" check "$ec" --jvm-free 2>/dev/null)
  rc=$?
  ok=$(printf '%s' "$r" | grep -oE ':ok (true|false)' | head -1)
  echo "amu check ecma262.kotoba --jvm-free: rc=$rc ok=$ok"
  # test execution needs wasm runtime: does the runner exist here?
  if [ -f "$BR/test/runtime-differential.cljs" ]; then
    echo "runtime-differential.cljs: present (needs sibling build + node; not counted as pass by script)"
  fi
else
  echo "ecma262.kotoba: MISSING"
fi

echo "=== 4. west pin freshness (browser) ==="
if [ -d "$BR" ] && [ -f "$ROOT/manifest/west.yml" ]; then
  # Bugfix: anchor on the project NAME line, not the path line. In west.yml each
  # block is name/remote/revision/path/..., so a flag set on "path: .../browser"
  # grabbed the NEXT project's revision (browser-agent) => false match=no.
  # Anchoring on  "- name: browser$" (exact, end-anchored so -agent/-use don't
  # match) returns THIS project's own revision.
  pin=$(awk '/^[[:space:]]*- name: browser$/{f=1} f&&/^[[:space:]]*revision:/{print $2;exit}' "$ROOT/manifest/west.yml" 2>/dev/null)
  head=$(git -C "$BR" rev-parse HEAD 2>/dev/null)
  echo "west pin: ${pin:0:12} checkout HEAD: ${head:0:12} match=$([ -n "$pin" ] && [ "$pin" = "$head" ] && echo yes || echo no)"
else echo "west pin: UNMEASURED"; fi

echo "=== 5. browser repo git state ==="
if [ -d "$BR" ]; then
  echo "last:"; git -C "$BR" log --oneline -5
  echo "dirty: $(git -C "$BR" status --porcelain | wc -l | tr -d ' ')"
fi

echo "=== DONE ==="