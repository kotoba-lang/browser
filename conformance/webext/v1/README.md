# WebExtension conformance corpus v1

The fixture corpus ADR-0003 Ruling 3 requires: no "Chrome API compatible"
claim exists outside these cases. It is data only. The runner arrives with the
WebExtension runtime (KOTOBA-150); until then nothing here is executed or
scored.

- `manifests/` — four MV3/MV2 inputs. `cmp-reader.json` is shaped after the
  mamori consent reader (cloud-itonami/mamori README:111): storage, cookies,
  tabs and scripting on one declared origin.
- `cases.json` — 18 cases: 9 positive (`P*`), 8 negative (`N*`) and one
  install mapping (`I01`). Each names a manifest, an install or a `chrome.*`
  call, an optional consent decision, and the expected outcome from the
  vocabulary at the top of the file.
- `SHA256SUMS` — pins the bytes. Changing a case changes the pin.

The five mandatory negatives of Ruling 3 are N01 (undeclared origin), N02
(undeclared permission), N03 (overscoped `tabs.query`), N04 (deny, then no
request is sent) and N05 (namespace outside the v1 slice). N06–N08 add
manifest v2, a runtime `declarativeNetRequest` rule (mapping rule 6) and
"install approval is not operation consent" (mapping rule 5).

## Open ruling before the corpus freezes

P05 and P07 expect `allow-declared` for `tabs.query` and `cookies.get`
inside the approved scope, as Ruling 2 tier 1 says. The canonical broker
(`kotoba-lang/wasm-webcomponent/src/kotoba-client-runtime.js` on main)
accepts only `consent: "each-operation"` for every declared operation. Either
tier-1 reads are a host-local tier outside the broker, or these cases change
to `consent-required`. That is decided when ADR-0003 is accepted; the corpus
is frozen with the decision.

## Checking the files

```sh
shasum -a 256 -c SHA256SUMS
```

The mamori acceptance test (CMP/TCF consent read, vendor mapping, one-click
deny) is a phase-4 test against the running extension, not a case here.
