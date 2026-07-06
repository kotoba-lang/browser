(ns browser.quickjs-wasm-test
  (:require [browser.compat.quickjs-wasm :as quickjs-wasm]
            [browser.compat.quickjs-execution :as execution]
            [browser.profile :as profile]
            [browser.storage :as storage]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest quickjs-wasm-descriptor-is-wasm-engine-boundary
  (let [descriptor (quickjs-wasm/descriptor)
        binary (quickjs-wasm/binary-descriptor)
        engine (quickjs-wasm/engine! {:modules {"dep.js" "export const value = 7;"}})]
    (is (= :quickjs-emscripten (:quickjs.wasm/runtime descriptor)))
    (is (= :singlefile-cjs-release-sync (:quickjs.wasm/variant descriptor)))
    (is (:quickjs.binary/wasm? binary))
    (is (= :browser.runtime/quickjs
           (get-in descriptor [:quickjs.wasm/runtime-manifest :component/id])))
    (is (= false (execution/engine-ready? engine)))
    (is (= ["dep.js"]
           (get-in engine [:quickjs.engine/meta :quickjs.wasm/descriptor :quickjs.wasm/modules])))
    (is (= :quickjs/missing-engine-invoke
           (:quickjs.engine/error (execution/start-engine engine))))))

(deftest quickjs-wasm-module-provider-can-read-profile-storage
  (let [profile (profile/new-profile {:id "work"})
        origin "kotoba://quickjs"
        store (-> (storage/empty-store)
                  (storage/put-value profile origin
                                     (quickjs-wasm/storage-module-key "profile-dep.js")
                                     "export const value = 11;"))
        provider (quickjs-wasm/storage-module-provider {:store store
                                                       :profile profile
                                                       :origin origin})
        engine (quickjs-wasm/engine! {:module-provider provider})]
    (is (= "export const value = 11;"
           (provider "kotoba://quickjs/profile-dep.js")))
    (is (= "export const value = 11;"
           (quickjs-wasm/resolve-module-source {} provider "./profile-dep.js")))
    (is (= true
           (get-in engine [:quickjs.engine/meta :quickjs.wasm/descriptor :quickjs.wasm/module-provider?])))))

(deftest quickjs-wasm-engine-options-can-be-built-from-session-storage
  (let [profile (profile/new-profile {:id "work"})
        page-url "kotoba://quickjs"
        store (-> (storage/empty-store)
                  (storage/put-value profile page-url
                                     (quickjs-wasm/storage-module-key "restored.js")
                                     "export const restored = true;"))
        session {:browser.session/store store
                 :browser.session/profile profile
                 :browser.session/page {:browser/url page-url}}
        opts (quickjs-wasm/engine-options-from-session session)
        engine (quickjs-wasm/engine-from-session! session)]
    (is (= "export const restored = true;"
           ((:module-provider opts) "kotoba://quickjs/restored.js")))
    (is (= true
           (get-in engine [:quickjs.engine/meta :quickjs.wasm/descriptor :quickjs.wasm/module-provider?])))))

(deftest quickjs-wasm-webapi-shim-selector-vocabulary-matches-host-subset
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "operator: attrMatch[2]"))
    (is (str/includes? source "case '~='"))
    (is (str/includes? source "case '^='"))
    (is (str/includes? source "case '$='"))
    (is (str/includes? source "case '*='"))
    (is (str/includes? source "case '|='"))
    (is (str/includes? source "case 'disabled'"))
    (is (str/includes? source "case 'enabled'"))
    (is (str/includes? source "case 'checked'"))
    (is (str/includes? source "case 'required'"))
    (is (str/includes? source "case 'optional'"))
    (is (str/includes? source "case 'read-only'"))
    (is (str/includes? source "case 'read-write'"))
    (is (str/includes? source "case 'invalid'"))
    (is (str/includes? source "case 'valid'"))
    (is (str/includes? source "case 'focus'"))
    (is (str/includes? source "function __kotobaConstraintInvalid"))
    (is (str/includes? source "value.trim() === ''"))
    (is (str/includes? source "function __kotobaConstraintValid"))
    (is (str/includes? source "function __kotobaValidationBarredControl"))
    (is (str/includes? source "function __kotobaConstraintValidationBarredControl"))
    (is (str/includes? source "!__kotobaConstraintValidationBarredControl(node)"))
    (is (str/includes? source "type !== 'hidden'"))
    (is (str/includes? source "type !== 'file'"))
    (is (str/includes? source "type === 'hidden' || type === 'file'"))
    (is (str/includes? source "__kotobaBoolAttr(node, 'readonly')"))
    (is (str/includes? source "function __kotobaSelectValue"))
    (is (str/includes? source "function __kotobaRadioRequiredSatisfied"))
    (is (str/includes? source "function __kotobaDisabledByFieldset"))
    (is (str/includes? source "function __kotobaDisabledByOptgroup"))
    (is (str/includes? source "function __kotobaDisabledCapableControl"))
    (is (str/includes? source "function __kotobaDisabledControl"))
    (is (str/includes? source "var disabled = __kotobaDisabledControl(candidate)"))
    (is (str/includes? source "function __kotobaSelectorTokens"))
    (is (str/includes? source "function __kotobaSplitSelectorList"))
    (is (str/includes? source "bracketDepth === 0"))
    (is (str/includes? source "var rawTokens = __kotobaSelectorTokens(selector)"))
    (is (str/includes? source "withoutAttrs = selector.replace(attrPattern, '')"))
    (is (str/includes? source "while ((match = classPattern.exec(withoutPseudos))"))))

(deftest quickjs-wasm-webapi-shim-constraint-invalid-honors-min-max-range
  ;; A real element.matches(':invalid')/':valid' call goes through this
  ;; embedded JS copy of the constraint-validation logic -- confirmed (via
  ;; a real-QuickJS smoke test, since actual JS evaluation only happens
  ;; under the cljs/node-test target) to previously report a real
  ;; out-of-range `<input type="number" min="1" max="10" value="15">` as
  ;; VALID, unlike kotoba-lang/cssom's `constraint-invalid?` and this repo's
  ;; own `document_input.cljc`'s `validation-reason`, both already fixed
  ;; for this exact gap in an earlier cycle.
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaParseNumber(v)"))
    (is (str/includes? source "test(s) ? parseFloat(s) : NaN"))
    (is (str/includes? source "var rangeValue = (tag === 'input' && (type === 'number' || type === 'range')"))
    (is (str/includes? source "var rangeMin = __kotobaParseNumber(__kotobaAttr(node, 'min'))"))
    (is (str/includes? source "var rangeMax = __kotobaParseNumber(__kotobaAttr(node, 'max'))"))
    (is (str/includes? source "!Number.isNaN(rangeValue) && !Number.isNaN(rangeMin) && rangeValue < rangeMin"))
    (is (str/includes? source "!Number.isNaN(rangeValue) && !Number.isNaN(rangeMax) && rangeValue > rangeMax"))))

(deftest quickjs-wasm-webapi-shim-exposes-checkvalidity-and-validity-state
  ;; element.checkValidity()/.reportValidity()/.validity/.willValidate --
  ;; previously entirely missing (a repo-wide grep for `checkValidity`/
  ;; `reportValidity`/`ValidityState`/`willValidate` returned zero JS-facing
  ;; matches), even though the underlying constraint-validation reason logic
  ;; already existed (used only for the `:invalid`/`:valid` CSS pseudo-class
  ;; match). Confirmed via a real CLJS/QuickJS smoke test
  ;; (quickjs-check-validity-smoke-test) that a real blank `required` input
  ;; now correctly reports `checkValidity() === false`, `.validity.
  ;; valueMissing === true`, and a real out-of-range `type="number"` input
  ;; reports `.validity.rangeOverflow === true` -- all previously
  ;; unreachable from JS at all.
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaValidationReason(node)"))
    (is (str/includes? source "return 'valueMissing';"))
    (is (str/includes? source "if (!Number.isNaN(minlength) && value.length > 0 && value.length < minlength) return 'tooShort';"))
    (is (str/includes? source "if (!Number.isNaN(maxlength) && value.length > maxlength) return 'tooLong';"))
    (is (str/includes? source "return 'rangeUnderflow';"))
    (is (str/includes? source "return 'rangeOverflow';"))
    (is (str/includes? source "function __kotobaWillValidate(node)"))
    (is (str/includes? source "function __kotobaValidityState(node)"))
    (is (str/includes? source "get willValidate()"))
    (is (str/includes? source "get validity()"))
    (is (str/includes? source "checkValidity: function()"))
    (is (str/includes? source "reportValidity: function()"))
    (is (str/includes? source "valueMissing: reason === 'valueMissing'"))
    (is (str/includes? source "rangeOverflow: reason === 'rangeOverflow'"))
    (is (str/includes? source "valid: reason === null"))))

(deftest quickjs-wasm-webapi-shim-exposes-select-method
  ;; HTMLInputElement/HTMLTextAreaElement.select() -- previously entirely
  ;; missing (a repo-wide grep for a `select:` method on the generic
  ;; element wrapper returned nothing at all, only the unrelated
  ;; `<select>` tag/option-list machinery), deferred across ten prior
  ;; scoping passes as the safest trivial close-out. Confirmed via a real
  ;; CLJS/QuickJS smoke test (quickjs-select-smoke-test) that a real
  ;; input/textarea's own select() now correctly selects its whole value,
  ;; reusing the already-real setSelectionRange.
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "select: function()"))
    (is (str/includes? source "this.setSelectionRange(0, this.value.length);"))))

(deftest quickjs-wasm-webapi-shim-exposes-pattern-mismatch-validation
  ;; A real HTML5 `pattern` attribute -- previously an honest, documented
  ;; scope-cut everywhere (`patternMismatch: false` hardcoded, no check at
  ;; all in `__kotobaConstraintInvalid`/`__kotobaValidationReason`).
  ;; Confirmed via a real CLJS/QuickJS smoke test
  ;; (quickjs-pattern-mismatch-smoke-test) that a real
  ;; `<input pattern="[0-9]+" value="abc">` now correctly reports
  ;; `matches(':invalid')`/`checkValidity() === false`/`.validity.
  ;; patternMismatch === true`, all previously unreachable from JS at all.
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaCompilePattern(pattern)"))
    (is (str/includes? source "return new RegExp('^(?:' + pattern + ')$');"))
    (is (str/includes? source "var pattern = __kotobaAttr(node, 'pattern');"))
    (is (str/includes? source "var patternRegex = patternApplicable ? __kotobaCompilePattern(pattern) : null;"))
    (is (str/includes? source "if (patternRegex && !patternRegex.test(value)) return 'patternMismatch';"))
    (is (str/includes? source "patternMismatch: reason === 'patternMismatch',"))))

(deftest quickjs-wasm-webapi-shim-exposes-type-mismatch-validation
  ;; Real HTML5 type="email"/"url" format checking -- previously an
  ;; honest, documented scope-cut everywhere (`typeMismatch: false`
  ;; hardcoded, the other half of the same scope-cut comment
  ;; patternMismatch's own fix closed a prior cycle). Confirmed via a
  ;; real CLJS/QuickJS smoke test (quickjs-type-mismatch-smoke-test) that
  ;; a real `<input type="email" value="not-an-email">` now correctly
  ;; reports `matches(':invalid')`/`checkValidity() === false`/`.validity.
  ;; typeMismatch === true`, all previously unreachable from JS at all.
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaTypeMismatch(type, value)"))
    (is (str/includes? source "if (value.trim() === '') return false;"))
    (is (str/includes? source "if (type === 'email') {"))
    (is (str/includes? source "if (type === 'url') {"))
    (is (str/includes? source "if (tag === 'input' && __kotobaTypeMismatch(type, value)) return 'typeMismatch';"))
    (is (str/includes? source "typeMismatch: reason === 'typeMismatch',"))))

(deftest quickjs-wasm-webapi-shim-exposes-step-mismatch-validation
  ;; Real HTML5 step attribute constraint validation -- previously an
  ;; honest, documented scope-cut everywhere (`stepMismatch: false`
  ;; hardcoded). Confirmed via a real CLJS/QuickJS smoke test
  ;; (quickjs-step-mismatch-smoke-test) that a real
  ;; `<input type="number" step="2" value="3">` now correctly reports
  ;; `matches(':invalid')`/`checkValidity() === false`/`.validity.
  ;; stepMismatch === true`, all previously unreachable from JS at all.
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaStepMismatch(type, value, node)"))
    (is (str/includes? source "if (type !== 'number' && type !== 'range') return false;"))
    (is (str/includes? source "if (rawStep != null && String(rawStep).toLowerCase() === 'any') return false;"))
    (is (str/includes? source "var step = (!Number.isNaN(parsedStep) && parsedStep > 0) ? parsedStep : 1;"))
    (is (str/includes? source "if (tag === 'input' && __kotobaStepMismatch(type, value, node)) return 'stepMismatch';"))
    (is (str/includes? source "stepMismatch: reason === 'stepMismatch',"))))

(deftest quickjs-wasm-webapi-shim-exposes-validation-message
  ;; element.validationMessage -- previously entirely missing (a repo-wide
  ;; grep for `validationMessage` returned zero matches anywhere), even
  ;; though `__kotobaValidationReason` already computes exactly one of 8
  ;; canonical reasons (used only for `.validity`/checkValidity()). Confirmed
  ;; via a real CLJS/QuickJS smoke test
  ;; (quickjs-validation-message-smoke-test) that a real blank `required`
  ;; input now reports `validationMessage === "Please fill out this
  ;; field."`, a real invalid `type="email"` input reports a message naming
  ;; the email format, and both a valid control and a disabled (never a
  ;; validation candidate) control report the empty string -- all
  ;; previously unreachable from JS at all (reading the property returned
  ;; `undefined`).
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaValidationMessage(node)"))
    (is (str/includes? source "if (!__kotobaWillValidate(node)) return '';"))
    (is (str/includes? source "if (reason === null) return '';"))
    (is (str/includes? source "case 'valueMissing': return 'Please fill out this field.';"))
    (is (str/includes? source "if (type === 'email') return 'Please enter a valid email address.';"))
    (is (str/includes? source "if (type === 'url') return 'Please enter a valid URL.';"))
    (is (str/includes? source "case 'patternMismatch': return 'Please match the requested format.';"))
    (is (str/includes? source "case 'rangeUnderflow':"))
    (is (str/includes? source "case 'rangeOverflow':"))
    (is (str/includes? source "get validationMessage()"))
    (is (str/includes? source "return __kotobaValidationMessage(__kotobaNodeById(__kotobaRefNodeId(ref)));"))))

(deftest quickjs-wasm-webapi-shim-exposes-scoped-element-query-selectors
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaScopedQuerySelectorAllIds(rootId, selector)"))
    (is (str/includes? source "function __kotobaScopedQuerySelectorId(rootId, selector)"))
    (is (str/includes? source "querySelector: function(selector)"))
    (is (str/includes? source "__kotobaScopedQuerySelectorId(__kotobaRefNodeId(ref), selector)"))
    (is (str/includes? source "querySelectorAll: function(selector)"))
    (is (str/includes? source "__kotobaScopedQuerySelectorAllIds(__kotobaRefNodeId(ref), selector)"))))

(deftest quickjs-wasm-webapi-shim-exposes-namespace-element-construction
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "createElementNS: function(namespaceURI, qualifiedName)"))
    (is (str/includes? source "attrs: {'namespace-uri': namespace}"))
    (is (str/includes? source "get namespaceURI()"))
    (is (str/includes? source "__kotobaAttr(node, 'namespace-uri') || null"))))

(deftest quickjs-wasm-webapi-shim-exposes-document-fragment-construction
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "createDocumentFragment: function()"))
    (is (str/includes? source "'node/type': 'document-fragment'"))
    (is (str/includes? source "'dom/op': 'create-fragment'"))
    (is (str/includes? source "return 11"))
    (is (str/includes? source "return '#document-fragment'"))
    (is (str/includes? source "childNode['node/type'] === 'document-fragment'"))))

(deftest quickjs-wasm-webapi-shim-exposes-clone-node-binding
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaCloneSnapshotNode(source, deep)"))
    (is (str/includes? source "cloneNode: function(deep)"))
    (is (str/includes? source "'dom/op': 'clone-node'"))
    (is (str/includes? source "'source/client-id'"))
    (is (str/includes? source "'source/id'"))
    (is (str/includes? source "'deep?': Boolean(deep)"))))

(deftest quickjs-wasm-webapi-shim-exposes-inner-html-binding
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaSerializeNode(node)"))
    (is (str/includes? source "get innerHTML()"))
    (is (str/includes? source "set innerHTML(value)"))
    (is (str/includes? source "'dom/op': 'set-inner-html'"))
    (is (str/includes? source "html: String(value)"))
    (is (str/includes? source "node.children = []"))))

(deftest quickjs-wasm-webapi-shim-exposes-outer-html-binding
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "get outerHTML()"))
    (is (str/includes? source "set outerHTML(value)"))
    (is (str/includes? source "'dom/op': 'set-outer-html'"))
    (is (str/includes? source "return __kotobaSerializeNode(__kotobaNodeById(__kotobaRefNodeId(ref)))"))
    (is (str/includes? source "delete node['parent/id']"))))

(deftest quickjs-wasm-webapi-shim-exposes-text-node-data-bindings
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaSetTextNodeData(ref, value)"))
    (is (str/includes? source "function __kotobaNormalizeNode(nodeId)"))
    (is (str/includes? source "'dom/op': 'set-text'"))
    (is (str/includes? source "'dom/op': 'split-text'"))
    (is (str/includes? source "'dom/op': 'normalize'"))
    (is (str/includes? source "get nodeValue()"))
    (is (str/includes? source "set nodeValue(value)"))
    (is (str/includes? source "get data()"))
    (is (str/includes? source "set data(value)"))
    (is (str/includes? source "appendData: function(value)"))
    (is (str/includes? source "deleteData: function(offset, count)"))
    (is (str/includes? source "insertData: function(offset, value)"))
    (is (str/includes? source "replaceData: function(offset, count, value)"))
    (is (str/includes? source "substringData: function(offset, count)"))
    (is (str/includes? source "splitText: function(offset)"))
    (is (str/includes? source "normalize: function()"))))

(deftest quickjs-wasm-webapi-shim-exposes-mutation-observer-binding
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.__kotobaMutationObservers = []"))
    (is (str/includes? source "function __kotobaScheduleMicrotask(callback, payload)"))
    (is (str/includes? source "function __kotobaQueueMutation(record)"))
    (is (str/includes? source "globalThis.MutationObserver = function(callback)"))
    (is (str/includes? source "observe: function(target, options)"))
    (is (str/includes? source "disconnect: function()"))
    (is (str/includes? source "takeRecords: function()"))
    (is (str/includes? source "observer.scheduled = true"))
    (is (str/includes? source "__kotobaScheduleMicrotask(function()"))
    (is (str/includes? source "if (pending.length) observer.callback(pending, observer.instance)"))
    (is (str/includes? source "type: 'attributes'"))
    (is (str/includes? source "type: 'childList'"))
    (is (str/includes? source "type: 'characterData'"))
    (is (str/includes? source "attributeOldValue"))
    (is (str/includes? source "characterDataOldValue"))))

(deftest quickjs-wasm-webapi-shim-exposes-dom-traversal-properties
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaChildNodeId(nodeId, elementsOnly, last)"))
    (is (str/includes? source "function __kotobaSiblingNodeId(nodeId, elementsOnly, next)"))
    (is (str/includes? source "get parentElement()"))
    (is (str/includes? source "get firstChild()"))
    (is (str/includes? source "get lastChild()"))
    (is (str/includes? source "get firstElementChild()"))
    (is (str/includes? source "get lastElementChild()"))
    (is (str/includes? source "get previousSibling()"))
    (is (str/includes? source "get nextSibling()"))
    (is (str/includes? source "get previousElementSibling()"))
    (is (str/includes? source "get nextElementSibling()"))
    (is (str/includes? source "get nodeName()"))
    (is (str/includes? source "get localName()"))
    (is (str/includes? source "get namespaceURI()"))
    (is (str/includes? source "get ownerDocument()"))
    (is (str/includes? source "get isConnected()"))
    (is (str/includes? source "contains: function(other)"))
    (is (str/includes? source "__kotobaDescendantOrSelf(node, otherNode)"))))

(deftest quickjs-wasm-webapi-shim-exposes-focus-bindings
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "get activeElement()"))
    (is (str/includes? source "get URL()"))
    (is (str/includes? source "get documentURI()"))
    (is (str/includes? source "get baseURI()"))
    (is (str/includes? source "globalThis.__kotobaSnapshot['base-uri']"))
    (is (str/includes? source "get currentScript()"))
    (is (str/includes? source "globalThis.__kotobaSnapshot['current-script']"))
    (is (str/includes? source "get readyState()"))
    (is (str/includes? source "globalThis.__kotobaSnapshot['ready-state']"))
    (is (str/includes? source "get title()"))
    (is (str/includes? source "set title(value)"))
    (is (str/includes? source "'dom/op': 'set-title'"))
    (is (str/includes? source "globalThis.__kotobaSnapshot.focus"))
    (is (str/includes? source "focus: function()"))
    (is (str/includes? source "'dom/op': 'focus-node'"))
    (is (str/includes? source "blur: function()"))
    (is (str/includes? source "'dom/op': 'blur-node'"))))

(deftest quickjs-wasm-webapi-shim-exposes-form-owner-and-label-bindings
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaFormOwnerId(node)"))
    (is (str/includes? source "function __kotobaLabelControlId(label)"))
    (is (str/includes? source "function __kotobaLabelIdsForControl(node)"))
    (is (str/includes? source "get htmlFor()"))
    (is (str/includes? source "set htmlFor(value)"))
    (is (str/includes? source "get form()"))
    (is (str/includes? source "get labels()"))
    (is (str/includes? source "get control()"))
    (is (str/includes? source "__kotobaFormOwnerId(__kotobaNodeById(__kotobaRefNodeId(ref)))"))
    (is (str/includes? source "__kotobaLabelIdsForControl(__kotobaNodeById(__kotobaRefNodeId(ref)))"))))

(deftest quickjs-wasm-webapi-shim-exposes-select-option-bindings
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaOptionIds(selectNode)"))
    (is (str/includes? source "function __kotobaSelectedOptionIds(selectNode)"))
    (is (str/includes? source "function __kotobaSelectedIndex(selectNode)"))
    (is (str/includes? source "get selected()"))
    (is (str/includes? source "set selected(value)"))
    (is (str/includes? source "get options()"))
    (is (str/includes? source "get selectedOptions()"))
    (is (str/includes? source "get selectedIndex()"))
    (is (str/includes? source "set selectedIndex(value)"))
    (is (str/includes? source "if (String(node && node.tag || '').toLowerCase() === 'select') return __kotobaSelectValue(node)"))
    (is (str/includes? source "this.setAttribute('value', selected ? __kotobaOptionValue(selected) : '')"))))

(deftest quickjs-wasm-webapi-shim-exposes-tag-and-class-collection-bindings
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaElementIdsByTagName(tagName)"))
    (is (str/includes? source "function __kotobaElementIdsByTagNameFromIds(ids, tagName)"))
    (is (str/includes? source "function __kotobaElementIdsByClassName(className)"))
    (is (str/includes? source "function __kotobaElementIdsByClassNameFromIds(ids, className)"))
    (is (str/includes? source "getElementsByTagName: function(tagName)"))
    (is (str/includes? source "getElementsByClassName: function(className)"))
    (is (str/includes? source "function __kotobaDocumentLinkIds()"))
    (is (str/includes? source "get forms()"))
    (is (str/includes? source "__kotobaElementIdsByTagName('form')"))
    (is (str/includes? source "get images()"))
    (is (str/includes? source "__kotobaElementIdsByTagName('img')"))
    (is (str/includes? source "get links()"))
    (is (str/includes? source "__kotobaDocumentLinkIds()"))
    (is (str/includes? source "tag === 'a' || tag === 'area'"))
    (is (str/includes? source "__kotobaAttr(node, 'href') != null"))
    (is (str/includes? source "get scripts()"))
    (is (str/includes? source "__kotobaElementIdsByTagName('script')"))
    (is (str/includes? source "__kotobaDescendantNodeIds(__kotobaNodeById(__kotobaRefNodeId(ref)))"))
    (is (str/includes? source "__kotobaElementIdsByTagNameFromIds("))
    (is (str/includes? source "__kotobaElementIdsByClassNameFromIds("))))

(deftest quickjs-wasm-webapi-shim-exposes-form-control-property-bindings
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaSetBooleanAttribute(ref, name, value)"))
    (is (str/includes? source "function __kotobaStringAttribute(ref, name)"))
    (is (str/includes? source "get defaultChecked()"))
    (is (str/includes? source "set defaultChecked(value)"))
    (is (str/includes? source "get defaultValue()"))
    (is (str/includes? source "set defaultValue(value)"))
    (is (str/includes? source "get disabled()"))
    (is (str/includes? source "set disabled(value)"))
    (is (str/includes? source "get required()"))
    (is (str/includes? source "set required(value)"))
    (is (str/includes? source "get readOnly()"))
    (is (str/includes? source "set readOnly(value)"))
    (is (str/includes? source "get multiple()"))
    (is (str/includes? source "set multiple(value)"))
    (is (str/includes? source "get defaultSelected()"))
    (is (str/includes? source "set defaultSelected(value)"))
    (is (str/includes? source "__kotobaSetBooleanAttribute(ref, 'checked', value)"))
    (is (str/includes? source "__kotobaSetBooleanAttribute(ref, 'default-selected', value)"))))

(deftest quickjs-wasm-webapi-shim-exposes-hidden-property-bindings
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "get hidden()"))
    (is (str/includes? source "set hidden(value)"))
    (is (str/includes? source "__kotobaSetBooleanAttribute(ref, 'hidden', value)"))))

(deftest quickjs-wasm-webapi-shim-exposes-media-and-script-element-property-bindings
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "get href()"))
    (is (str/includes? source "set href(value)"))
    (is (str/includes? source "function __kotobaUrlAttribute(ref, name)"))
    (is (str/includes? source "__kotobaUrlAttribute(ref, 'href')"))
    (is (str/includes? source "get src()"))
    (is (str/includes? source "set src(value)"))
    (is (str/includes? source "__kotobaUrlAttribute(ref, 'src')"))
    (is (str/includes? source "globalThis.__kotobaSnapshot['base-uri']"))
    (is (str/includes? source "get alt()"))
    (is (str/includes? source "set alt(value)"))
    (is (str/includes? source "__kotobaStringAttribute(ref, 'alt')"))
    (is (str/includes? source "get async()"))
    (is (str/includes? source "set async(value)"))
    (is (str/includes? source "__kotobaSetBooleanAttribute(ref, 'async', value)"))
    (is (str/includes? source "get defer()"))
    (is (str/includes? source "set defer(value)"))
    (is (str/includes? source "__kotobaSetBooleanAttribute(ref, 'defer', value)"))
    (is (str/includes? source "get complete()"))
    (is (str/includes? source "return tag === 'img' ? Boolean(__kotobaAttr(node, 'src')) : true"))))

(deftest quickjs-wasm-webapi-shim-exposes-node-mutation-convenience-methods
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaNodeArg(value)"))
    (is (str/includes? source "prepend: function()"))
    (is (str/includes? source "before: function()"))
    (is (str/includes? source "after: function()"))
    (is (str/includes? source "replaceWith: function()"))
    (is (str/includes? source "parent.insertBefore(__kotobaNodeArg(arguments[i]), element)"))
    (is (str/includes? source "parent.insertBefore(__kotobaNodeArg(arguments[i]), next)"))
    (is (str/includes? source "parent.removeChild(element)"))))

(deftest quickjs-wasm-webapi-shim-exposes-replace-child
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "replaceChild: function(newChild, oldChild)"))
    (is (str/includes? source "this.insertBefore(newChild, oldChild)"))
    (is (str/includes? source "this.removeChild(oldChild)"))
    (is (str/includes? source "return oldChild"))))

(deftest quickjs-wasm-webapi-shim-exposes-scroll-position
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "get scrollTop()"))
    (is (str/includes? source "set scrollTop(value)"))
    (is (str/includes? source "get scrollLeft()"))
    (is (str/includes? source "set scrollLeft(value)"))
    (is (str/includes? source "this.setAttribute('scroll-top', Math.max(0, Number(value) || 0))"))
    (is (str/includes? source "this.setAttribute('scroll-left', Math.max(0, Number(value) || 0))"))))

(deftest quickjs-wasm-webapi-shim-exposes-get-computed-style
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaComputedStyle(ref)"))
    (is (str/includes? source "globalThis.getComputedStyle = function(el)"))
    (is (str/includes? source "return __kotobaComputedStyle(el && el.__kotobaRef)"))
    (is (str/includes? source "if (prop === 'getPropertyValue') return function(name)"))))

(deftest quickjs-wasm-webapi-shim-exposes-attribute-convenience-methods
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "toggleAttribute: function(name, force)"))
    (is (str/includes? source "var shouldAdd = force === undefined ? !present : Boolean(force)"))
    (is (str/includes? source "__kotobaSetAttribute(ref, name, '')"))
    (is (str/includes? source "__kotobaRemoveAttribute(ref, name)"))
    (is (str/includes? source "deleteProperty: function(_, prop)"))
    (is (str/includes? source "__kotobaRemoveAttribute(ref, __kotobaDataAttrName(prop))"))
    (is (str/includes? source "if (prop === 'getPropertyValue')"))
    (is (str/includes? source "if (prop === 'removeProperty')"))
    (is (str/includes? source "__kotobaRemoveAttribute(ref, attrName)"))))

(deftest quickjs-wasm-webapi-shim-exposes-clipboard-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.navigator.clipboard"))
    (is (str/includes? source "readText: function()"))
    (is (str/includes? source "writeText: function(text)"))
    (is (str/includes? source "capability: 'clipboard/read'"))
    (is (str/includes? source "capability: 'clipboard/write'"))
    (is (str/includes? source "'clipboard/format': 'text'"))))

(deftest quickjs-wasm-webapi-shim-exposes-window-open-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.window = globalThis"))
    (is (str/includes? source "globalThis.open = function(url, target, features)"))
    (is (str/includes? source "capability: 'window/open'"))
    (is (str/includes? source "target: target == null ? '_blank' : String(target)"))
    (is (str/includes? source "request['window/features'] = String(features)"))))

(deftest quickjs-wasm-webapi-shim-exposes-console-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.console = globalThis.console || {}"))
    (is (str/includes? source "function __kotobaConsoleLog(level, args)"))
    (is (str/includes? source "capability: 'console/log'"))
    (is (str/includes? source "'console/level': level"))
    (is (str/includes? source "globalThis.console.log = function()"))
    (is (str/includes? source "globalThis.console.info = function()"))
    (is (str/includes? source "globalThis.console.warn = function()"))
    (is (str/includes? source "globalThis.console.error = function()"))
    (is (str/includes? source "globalThis.console.debug = function()"))))

(deftest quickjs-wasm-webapi-shim-exposes-global-event-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaListenGlobalEvent(target, type, handler)"))
    (is (str/includes? source "function __kotobaRemoveGlobalEvent(target, type, handler)"))
    (is (str/includes? source "function __kotobaDispatchGlobalEvent(target, event)"))
    (is (str/includes? source "globalThis.CustomEvent = function(type, init)"))
    (is (str/includes? source "globalThis.MouseEvent = function(type, init)"))
    (is (str/includes? source "globalThis.KeyboardEvent = function(type, init)"))
    (is (str/includes? source "event.detail = Object.prototype.hasOwnProperty.call(init, 'detail')"))
    (is (str/includes? source "event.clientX = init.clientX == null ? 0 : Number(init.clientX)"))
    (is (str/includes? source "event.key = init.key == null ? '' : String(init.key)"))
    (is (str/includes? source "detail: event && Object.prototype.hasOwnProperty.call(event, 'detail')"))
    (is (str/includes? source "key: event && event.key != null ? String(event.key) : null"))
    (is (str/includes? source "clientX: event && event.clientX != null ? Number(event.clientX) : null"))
    (is (str/includes? source "globalThis.__kotobaListenerIds = {}"))
    (is (str/includes? source "capability: 'event/listen'"))
    (is (str/includes? source "capability: 'event/remove'"))
    (is (str/includes? source "capability: 'event/dispatch'"))
    (is (str/includes? source "globalThis.__kotobaListenerIds[key].push({ handler: handler, id: handlerId })"))
    (is (str/includes? source "Object.assign(request, __kotobaNodeRequest(ref, 'node'))"))
    (is (str/includes? source "'event/target': String(target)"))
    (is (str/includes? source "globalThis.document.addEventListener = function(type, handler)"))
    (is (str/includes? source "globalThis.document.removeEventListener = function(type, handler)"))
    (is (str/includes? source "globalThis.addEventListener = function(type, handler)"))
    (is (str/includes? source "globalThis.removeEventListener = function(type, handler)"))))

(deftest quickjs-wasm-webapi-shim-exposes-event-modifier-keys
  ;; KeyboardEvent/MouseEvent shiftKey/ctrlKey/altKey/metaKey -- previously
  ;; silently dropped: neither constructor read them from its own init
  ;; dict at all (a real `new KeyboardEvent('keydown', {})` read `undefined`
  ;; for all four instead of the real spec default `false`), and the
  ;; outbound __kotobaEventPayload builder used by every dispatchEvent had
  ;; zero modifier-key fields either. Confirmed via a real CLJS/QuickJS
  ;; smoke test (quickjs-event-modifier-keys-smoke-test) that a real
  ;; KeyboardEvent/MouseEvent with no modifiers given now correctly defaults
  ;; all four to `false` (previously `undefined`) -- while an already-set
  ;; modifier (e.g. `{shiftKey: true}`) had actually been reading back
  ;; correctly all along, an implementation-detail passthrough (the
  ;; constructor's `event` object IS its own `init` object, so any property
  ;; already present on `init` survived untouched even before this fix).
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "event.shiftKey = Boolean(init.shiftKey);"))
    (is (str/includes? source "event.ctrlKey = Boolean(init.ctrlKey);"))
    (is (str/includes? source "event.altKey = Boolean(init.altKey);"))
    (is (str/includes? source "event.metaKey = Boolean(init.metaKey);"))
    (is (str/includes? source "shiftKey: Boolean(event && event.shiftKey),"))
    (is (str/includes? source "ctrlKey: Boolean(event && event.ctrlKey),"))
    (is (str/includes? source "altKey: Boolean(event && event.altKey),"))
    (is (str/includes? source "metaKey: Boolean(event && event.metaKey)"))))

(deftest quickjs-wasm-webapi-shim-exposes-location-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.location = globalThis.location ||"))
    (is (str/includes? source "assign: function(url)"))
    (is (str/includes? source "capability: 'location/assign'"))
    (is (str/includes? source "replace: function(url)"))
    (is (str/includes? source "capability: 'location/replace'"))
    (is (str/includes? source "reload: function()"))
    (is (str/includes? source "capability: 'location/reload'"))
    (is (str/includes? source "'location/op': 'reload'"))))

(deftest quickjs-wasm-webapi-shim-exposes-url-and-search-params
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.URLSearchParams = function(init)"))
    (is (str/includes? source "globalThis.URLSearchParams.prototype.append = function(name, value)"))
    (is (str/includes? source "globalThis.URLSearchParams.prototype.get = function(name)"))
    (is (str/includes? source "globalThis.URLSearchParams.prototype.getAll = function(name)"))
    (is (str/includes? source "globalThis.URLSearchParams.prototype.set = function(name, value)"))
    (is (str/includes? source "globalThis.URLSearchParams.prototype.delete = function(name)"))
    (is (str/includes? source "globalThis.URLSearchParams.prototype.sort = function()"))
    (is (str/includes? source "globalThis.URLSearchParams.prototype.forEach = function(callback, thisArg)"))
    (is (str/includes? source "globalThis.URLSearchParams.prototype.toString = function()"))
    (is (str/includes? source "function __kotobaResolveUrl(input, base)"))
    (is (str/includes? source "globalThis.URL = function(input, base)"))
    (is (str/includes? source "this.searchParams = new globalThis.URLSearchParams(this.search)"))))

(deftest quickjs-wasm-webapi-shim-exposes-permissions-query-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.navigator.permissions"))
    (is (str/includes? source "query: function(descriptor)"))
    (is (str/includes? source "capability: 'permissions/query'"))
    (is (str/includes? source "'permission/name': name"))
    (is (str/includes? source "return { state: 'prompt', name: name }"))))

(deftest quickjs-wasm-webapi-shim-exposes-geolocation-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.navigator.geolocation"))
    (is (str/includes? source "getCurrentPosition: function(success, error, options)"))
    (is (str/includes? source "capability: 'geolocation/read'"))
    (is (str/includes? source "'geolocation/op': 'current-position'"))
    (is (str/includes? source "request['geolocation/options'] = options"))))

(deftest quickjs-wasm-webapi-shim-exposes-notification-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.Notification = function(title, options)"))
    (is (str/includes? source "capability: 'notification/show'"))
    (is (str/includes? source "request['notification/options'] = options"))
    (is (str/includes? source "globalThis.Notification.permission = (globalThis.__kotobaNotificationSnapshot && globalThis.__kotobaNotificationSnapshot.permission) || 'default'"))
    (is (str/includes? source "globalThis.Notification.requestPermission = function(callback)"))
    (is (str/includes? source "capability: 'notification/request-permission'"))
    (is (str/includes? source "var permission = (globalThis.__kotobaNotificationSnapshot && globalThis.__kotobaNotificationSnapshot.permission) || 'default'")
        "requestPermission's callback/return value must read the REAL, host-computed permission decision off globalThis.__kotobaNotificationSnapshot (installed by notification-permission-snapshot-source BEFORE this shim runs) instead of always reporting the hardcoded 'default' literal regardless of what was actually granted.")))

(deftest quickjs-wasm-webapi-shim-exposes-fullscreen-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "requestFullscreen: function(options)"))
    (is (str/includes? source "capability: 'fullscreen/request'"))
    (is (str/includes? source "request['fullscreen/options'] = options"))
    (is (str/includes? source "exitFullscreen: function()"))
    (is (str/includes? source "capability: 'fullscreen/exit'"))
    (is (str/includes? source "'fullscreen/op': 'exit'"))))

(deftest quickjs-wasm-webapi-shim-exposes-media-capture-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.navigator.mediaDevices"))
    (is (str/includes? source "getUserMedia: function(constraints)"))
    (is (str/includes? source "capability: 'media/capture'"))
    (is (str/includes? source "'media/op': 'get-user-media'"))
    (is (str/includes? source "'media/constraints': constraints || {}"))))

(deftest quickjs-wasm-webapi-shim-exposes-websocket-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.WebSocket = function(url, protocols)"))
    (is (str/includes? source "capability: 'websocket/connect'"))
    (is (str/includes? source "request['websocket/protocols'] = protocols"))
    (is (str/includes? source "globalThis.WebSocket.prototype.send = function(data)"))
    (is (str/includes? source "capability: 'websocket/send'"))
    (is (str/includes? source "globalThis.WebSocket.prototype.close = function(code, reason)"))
    (is (str/includes? source "capability: 'websocket/close'"))))

(deftest quickjs-wasm-webapi-shim-exposes-crypto-random-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.crypto ="))
    (is (str/includes? source "getRandomValues: function(array)"))
    (is (str/includes? source "capability: 'crypto/random-values'"))
    (is (str/includes? source "'crypto/op': 'random-values'"))
    (is (str/includes? source "randomUUID: function()"))
    (is (str/includes? source "capability: 'crypto/random-uuid'"))
    (is (str/includes? source "'crypto/op': 'random-uuid'"))))

(deftest quickjs-wasm-webapi-shim-exposes-worker-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.Worker = function(url, options)"))
    (is (str/includes? source "capability: 'worker/create'"))
    (is (str/includes? source "request['worker/options'] = options"))
    (is (str/includes? source "globalThis.Worker.prototype.postMessage = function(message)"))
    (is (str/includes? source "capability: 'worker/post-message'"))
    (is (str/includes? source "globalThis.Worker.prototype.terminate = function()"))
    (is (str/includes? source "capability: 'worker/terminate'"))))

(deftest quickjs-wasm-webapi-shim-exposes-broadcast-channel-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.BroadcastChannel = function(name)"))
    (is (str/includes? source "capability: 'broadcast/open'"))
    (is (str/includes? source "'broadcast/name': String(name)"))
    (is (str/includes? source "globalThis.BroadcastChannel.prototype.postMessage = function(message)"))
    (is (str/includes? source "capability: 'broadcast/post-message'"))
    (is (str/includes? source "globalThis.BroadcastChannel.prototype.close = function()"))
    (is (str/includes? source "capability: 'broadcast/close'"))))

(deftest quickjs-wasm-webapi-shim-exposes-animation-frame-timer-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.requestAnimationFrame = function(callback)"))
    (is (str/includes? source "callbackId = 'animation-frame-' + frameId"))
    (is (str/includes? source "capability: 'timer/schedule'"))
    (is (str/includes? source "'timer/kind': 'animation-frame'"))
    (is (str/includes? source "globalThis.cancelAnimationFrame = function(frameId)"))
    (is (str/includes? source "capability: 'timer/cancel'"))))

(deftest quickjs-wasm-webapi-shim-exposes-class-list-mutation
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "function __kotobaClassTokenList(ref)"))
    (is (str/includes? source "get classList()"))
    (is (str/includes? source "contains: function(token)"))
    (is (str/includes? source "add: function()"))
    (is (str/includes? source "remove: function()"))
    (is (str/includes? source "toggle: function(token, force)"))
    (is (str/includes? source "replace: function(oldToken, newToken)"))
    (is (str/includes? source "if (i < 0) return false"))
    (is (str/includes? source "__kotobaSetAttribute(ref, 'class'"))
    (is (str/includes? source "'dom/op': 'set-attribute'"))))

(deftest quickjs-wasm-webapi-shim-exposes-beacon-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.navigator.sendBeacon = function(url, data)"))
    (is (str/includes? source "capability: 'beacon/send'"))
    (is (str/includes? source "if (data != null) request.data = data"))
    (is (str/includes? source "return true"))))

(deftest quickjs-wasm-webapi-shim-exposes-document-cookie-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "Object.defineProperty(globalThis.document, 'cookie'"))
    (is (str/includes? source "capability: 'cookie/get'"))
    (is (str/includes? source "'cookie/op': 'get'"))
    (is (str/includes? source "capability: 'cookie/set'"))
    (is (str/includes? source "'cookie/op': 'set'"))
    (is (str/includes? source "'cookie/value': String(value)"))))

(deftest quickjs-wasm-webapi-shim-exposes-history-capability
  (let [source quickjs-wasm/webapi-shim-source]
    (is (str/includes? source "globalThis.history = globalThis.history ||"))
    (is (str/includes? source "pushState: function(state, title, url)"))
    (is (str/includes? source "capability: 'history/push-state'"))
    (is (str/includes? source "replaceState: function(state, title, url)"))
    (is (str/includes? source "capability: 'history/replace-state'"))
    (is (str/includes? source "go: function(delta)"))
    (is (str/includes? source "capability: 'history/traverse'"))
    (is (str/includes? source "this.go(-1)"))
    (is (str/includes? source "this.go(1)"))))
