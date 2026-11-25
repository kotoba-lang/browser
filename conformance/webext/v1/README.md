# WebExtension conformance corpus v1

The fixture corpus ADR-0003 Ruling 3 requires: no "Chrome API compatible"
claim exists outside these cases. It is data only. The runner arrives with the
WebExtension runtime (KOTOBA-150); until then nothing here is executed or
scored.

This corpus is frozen. ADR-0003 was accepted on 2026-11-23 with the owner
ruling that every `chrome.*` operation requires `consent: "each-operation"`,
with no prompt-less exception; every case here follows that ruling.

- `manifests/` — 13 manifests and ten static rulesets. `cmp-reader.json` is
  shaped after the mamori consent reader (cloud-itonami/mamori README:111):
  storage, cookies, tabs and scripting on one declared origin.
- `cases.json` — 82 cases:

  | Ids | Count | What they show |
  | --- | --- | --- |
  | `P01`–`P20` | 20 | each v1 operation, approved: prompt, result, post-state, requests, audit |
  | `D01`–`D20` (no `D06`) | 19 | the same calls, denied: nothing executes, is sent or is audited as an effect |
  | `N01`–`N30` | 30 | fail-closed negatives, including the denial of `tabs.create` (N04) |
  | `S01`–`S06` | 6 | sequences: consent is fresh for every operation |
  | `I01`–`I07` | 7 | admitted installs; `I02`–`I07` also show where an admitted static rule does and does not take effect |

- `SHA256SUMS` — pins the bytes. Changing a case changes the pin.

## Outcomes

| Outcome | Meaning |
| --- | --- |
| `install-admit` | The manifest maps to the expected descriptor, which is shown for approval before any effect. |
| `install-refuse` | The manifest is refused before any descriptor approval. |
| `consent-required` | The declared call prompts for fresh per-operation consent immediately before invocation. It executes and is audited only on approval. |
| `refuse` | Fails closed before any prompt. No grant, no proxy. |

There is no outcome that allows a `chrome.*` operation without a prompt.

## Case fields

Every case starts from a fresh copy of the top-level `fixture`. For a call case
the manifest is already installed and its descriptor approved; N08 states that
with `installApproved` because it is the point of the case.

| Field | Meaning |
| --- | --- |
| `manifest` | The file that plays `manifest.json`. The package root is `manifests/` overlaid with `fixture.packageFiles`. |
| `install` | The case is the install itself; there is no call. |
| `seed` | Replaces the named `fixture` sections for this case. |
| `context` | The calling extension context. Default: the background service worker. |
| `call` | `namespace` + `method` + `args`, or `namespace` + `property` for a property read. |
| `consent` | The one decision the runner gives if prompted: `approve`, `deny`, or `none` (the prompt is never answered; the case ends with the call pending or rejected). |
| `steps` | Calls made in order on the same state, each with its own `context`, `call`, `consent` and `expect`. Step counts and lists cover that step only. |
| `control` | The approved case that this negative must be read against: it shows that the effect is observable when allowed. |

`fixture` holds the assigned `extensionId`, open `tabs` (opened before the
install, so content scripts have not run in them), `cookies` (in creation
order), both `storage` areas, registered `listeners`, `dynamicRules`, the
`network` (the only responses available; pages carry an inline icon, so nothing
else is fetched) and `packageFiles`. URLs are requested as written: the fixture
has no HSTS and no automatic HTTPS upgrade, so an `http:` page stays `http:`. A
listener answers every message by calling `sendResponse` synchronously with
`respond`.

`{"$listener": {...}}` in `args` stands for such a listener function.
`{"$undefined": true}` stands for the JavaScript value `undefined`.

## Expectations

| Field | Meaning |
| --- | --- |
| `prompts` | Number of Kotoba operation-consent prompts shown. Install approval and browser or OS prompts do not count. |
| `executed` | Whether the operation ran. |
| `result` | The returned value, after awaiting a promise. Deep equality. |
| `resultSubset` | Like `result`, but objects may carry further fields (used where Chrome returns opaque or variable fields). Arrays keep length and order. |
| `error`, `reason` | `error` is why the call rejects or throws after a prompt; `reason` is why it, or the install, is refused before one. Corpus codes, not Chrome message text. |
| `delivered` | Messages received by listeners, with the sender matched as a subset. |
| `stateAfter` | Post-state once loading settles: `"unchanged"` (from before the call), or the complete content of each named section; sections not named are unchanged. Tabs match in order as subsets, cookies in any order, other sections by deep equality. |
| `requests` | The complete, ordered list of HTTP requests the case caused. `[]` means none were sent. |
| `audit` | The complete, ordered list of `:webext/effect` audit events. `[]` means no effect was recorded. |
| `descriptor` | The approval projection: `identity` (id, name, version), origins, namespaces with their operations, data categories, the consent mode, and static `rulesets`. Each operation becomes one `kotoba.app.client-runtime.v1` operation with `consent: "each-operation"`. A namespace with no v1 operation is not listed. |
| `afterApproval` | A user navigation made after the descriptor is approved, and the `requests` it causes. A request that a rule blocks is not listed. |

An audit event is the attribute map that `browser.audit` writes as
`[:db/add eid attribute value]` datoms, with keywords written as strings; the
entity id is not asserted and further attributes are permitted. `:origin` is the
origin of the tab or cookie acted on, or the extension's own origin for runtime
and storage operations. An implementation may also record prompts, denials and
refusals under other events; the corpus does not constrain those.

## Coverage

The five mandatory negatives of Ruling 3 are N01 (undeclared origin), N02
(undeclared permission), N03 (overscoped `tabs.query`), N04 (deny, then no
request is sent; its control P06 does send one) and N05 (namespace outside the
v1 slice). N06–N08 add manifest v2, a runtime `declarativeNetRequest` rule
(mapping rule 6) and "install approval is not operation consent" (mapping
rule 5). N09–N20 and N22 cover undeclared origins, permissions, argument shapes
and members across the slice. N21 refuses a static ruleset that reaches an
undeclared origin; I02 admits a valid one and shows that it takes effect.

### Static rules and exact origins

A static rule is admitted only if it cannot match outside the approved exact
origins (mapping rule 6): its `urlFilter` begins with `|<origin>/`. The declared
origin is `https://news.example` in all of these cases.

| Case | Rule or request, under the declared domain | Expected |
| --- | --- | --- |
| N23 | `requestDomains` with an unanchored `urlFilter`: Chrome also matches subdomains, `http:` and other ports | install refused |
| N24 | `\|\|news.example/`: Chrome also matches subdomains, `http:` and other ports | install refused |
| N25 | `\|https://news.example^`: `^` also matches the `:` before a port | install refused |
| N26 | `regexFilter`, even one that names only the declared origin | install refused |
| N27, N28, N29 | a rule anchored to an undeclared subdomain, scheme or port | install refused |
| N30 | one valid rule and one wider rule | install refused as a whole |
| I03, I04, I05 | admitted rule; the user visits `https://sub.news.example`, `http://news.example`, `https://news.example:8443` | no effect: `tracker.js` is requested |
| I06 | admitted rule; a declared page loads `tracker.js` from its own origin, the subdomain and the port | only its own is blocked |
| I07 | admitted rule; a page on the subdomain loads `tracker.js` from the declared origin | no effect: the initiator is undeclared |

I02 and I06 are the controls: the same rule does block inside the declared
origin. Refusal reasons: `rule-origin-not-declared` when a rule is confined to an
origin that is not approved (N21, N27–N29), `rule-scope-exceeds-declared-origins`
when its `urlFilter` is not confined to one exact origin (N23–N25, N30), and
`rule-condition-outside-v1-slice` for `regexFilter` (N26). Refusing N21 and
N23–N30, and the I07 result, are the deviation from Chrome that ADR-0003 states.

`S01`, `S03`: an approved call is repeated unchanged and denied. `S02`, `S04`:
an approval is followed by a different operation that is never approved. `S05`:
two approvals, two prompts. `S06`: a denial does not answer the next prompt.

Where Chrome would succeed silently, or return a filtered result, for an
undeclared origin or an unscoped query (N03, N13–N15), refusal is the deviation
ADR-0003 states.

## Checking the files

```sh
shasum -a 256 -c SHA256SUMS
```

The mamori acceptance test (CMP/TCF consent read, vendor mapping, one-click
deny) is a phase-4 test against the running extension, not a case here.
