(ns browser.compat.quickjs-wasm
  "QuickJS/QuickJS-NG WASM guest integration.

  The JVM side can describe the engine boundary, but actual JavaScript source
  evaluation is provided by the ClojureScript host through quickjs-emscripten's
  singlefile WASM variant."
  (:require [browser.compat.quickjs-execution :as execution]
            [browser.runtime :as runtime]
            [browser.storage :as storage]
            [clojure.string :as str]
            [quickjs.binary :as binary]
            #?(:cljs ["quickjs-emscripten-core" :refer [newQuickJSWASMModuleFromVariant]])
            #?(:cljs ["@jitl/quickjs-singlefile-cjs-release-sync" :default quickjs-variant])))

(def wasm-magic
  #?(:clj (byte-array [(byte 0) (byte 97) (byte 115) (byte 109)])
     :cljs [0 97 115 109]))

(defn descriptor
  []
  {:quickjs.wasm/runtime :quickjs-emscripten
   :quickjs.wasm/variant :singlefile-cjs-release-sync
   :quickjs.wasm/engine :quickjs
   :quickjs.wasm/runtime-manifest (runtime/component-manifest (runtime/quickjs))})

(defn binary-descriptor
  []
  (binary/descriptor {:path "npm:@jitl/quickjs-singlefile-cjs-release-sync"
                      :bytes wasm-magic
                      :embedded? true
                      :variant (:quickjs.wasm/variant (descriptor))}))

(def module-storage-prefix "quickjs.module:")

(defn module-candidates
  [module-name]
  (let [module-name (str module-name)
        basename (last (str/split module-name #"/"))]
    (distinct
     (remove str/blank?
             [module-name
              (str "./" module-name)
              (str "/" module-name)
              basename
              (str "./" basename)]))))

(defn storage-module-key
  [specifier]
  (str module-storage-prefix specifier))

(defn storage-module-provider
  "Creates a module source provider backed by profile-scoped browser storage.

  Module sources are looked up under `quickjs.module:<specifier>` in the given
  profile/origin storage partition."
  [{:keys [store profile origin prefix] :or {prefix module-storage-prefix}}]
  (fn [module-name]
    (some (fn [candidate]
            (storage/get-value store profile origin (str prefix candidate)))
          (module-candidates module-name))))

(defn session-module-provider
  [session]
  (let [store (:browser.session/store session)
        profile (:browser.session/profile session)
        origin (or (get-in session [:browser.session/page :browser/url])
                   "kotoba://quickjs")]
    (when (and store profile)
      (storage-module-provider {:store store
                                :profile profile
                                :origin origin}))))

(defn engine-options-from-session
  [session]
  (cond-> {}
    (session-module-provider session)
    (assoc :module-provider (session-module-provider session))))

(defn resolve-module-source
  [modules module-provider module-name]
  (or (some #(get modules %) (module-candidates module-name))
      (when module-provider
        (module-provider module-name))))

#?(:cljs
   (defn- variant []
     (or (.-default quickjs-variant) quickjs-variant)))

(def webapi-shim-source
  "globalThis.__kotobaRequests = [];
      globalThis.window = globalThis;
      globalThis.__kotobaNextClientId = 1;
      globalThis.__kotobaNextHandlerId = 1;
      globalThis.__kotobaNextTimerId = 1;
      globalThis.__kotobaNextMicrotaskId = 1;
      globalThis.__kotobaNextWebSocketId = 1;
      globalThis.__kotobaNextWorkerId = 1;
      globalThis.__kotobaNextBroadcastId = 1;
      globalThis.__kotobaListeners = {};
      globalThis.__kotobaListenerIds = {};
      globalThis.__kotobaTimers = {};
      globalThis.__kotobaMicrotasks = {};
      globalThis.__kotobaRunTask = function(callbackId) {
        var timerKeys = Object.keys(globalThis.__kotobaTimers || {});
        for (var i = 0; i < timerKeys.length; i++) {
          var timer = globalThis.__kotobaTimers[timerKeys[i]];
          if (timer && timer['callback/id'] === callbackId) {
            delete globalThis.__kotobaTimers[timerKeys[i]];
            if (typeof timer.callback === 'function') timer.callback.apply(null, timer.args || []);
            return true;
          }
        }
        var microtaskKeys = Object.keys(globalThis.__kotobaMicrotasks || {});
        for (var j = 0; j < microtaskKeys.length; j++) {
          var microtask = globalThis.__kotobaMicrotasks[microtaskKeys[j]];
          if (microtask && microtask['callback/id'] === callbackId) {
            delete globalThis.__kotobaMicrotasks[microtaskKeys[j]];
            if (typeof microtask.callback === 'function') microtask.callback();
            return true;
          }
        }
        return false;
      };
      function __kotobaClientId() {
        var id = 'node-' + globalThis.__kotobaNextClientId;
        globalThis.__kotobaNextClientId = globalThis.__kotobaNextClientId + 1;
        return id;
      }
      globalThis.__kotobaSnapshot = globalThis.__kotobaSnapshot || { root: null, nodes: {} };
      globalThis.__kotobaWebSockets = globalThis.__kotobaWebSockets || {};
      globalThis.__kotobaClientNodes = {};
      globalThis.__kotobaElementCache = {};
      globalThis.__kotobaMutationObservers = [];
      globalThis.__kotobaNextMutationObserverId = 1;
      function __kotobaNodeKey(id) {
        return id == null ? null : String(id);
      }
      function __kotobaNodeById(id) {
        return globalThis.__kotobaSnapshot.nodes[__kotobaNodeKey(id)] || null;
      }
      function __kotobaRefNodeId(ref) {
        if (!ref) return null;
        if (ref.nodeId != null) return ref.nodeId;
        if (ref.clientId && globalThis.__kotobaClientNodes[ref.clientId]) {
          return globalThis.__kotobaClientNodes[ref.clientId]['node/id'];
        }
        if (ref.selector) return __kotobaQuerySelectorId(ref.selector);
        return null;
      }
      function __kotobaScheduleMicrotask(callback, payload) {
        var microtaskId = globalThis.__kotobaNextMicrotaskId;
        var callbackId = 'microtask-' + microtaskId;
        globalThis.__kotobaNextMicrotaskId = microtaskId + 1;
        globalThis.__kotobaMicrotasks[microtaskId] = {
          'callback/id': callbackId,
          callback: callback,
          payload: payload || null
        };
        globalThis.__kotobaRequests.push({
          capability: 'timer/microtask',
          'timer/kind': 'microtask',
          'microtask/id': microtaskId,
          'callback/id': callbackId
        });
        return callbackId;
      }
      function __kotobaNodeRef(nodeId) {
        return nodeId == null ? null : __kotobaElement({ nodeId: nodeId });
      }
      function __kotobaObserverContains(targetId, observedId) {
        var target = __kotobaNodeById(targetId);
        var observed = __kotobaNodeById(observedId);
        return __kotobaDescendantOrSelf(observed, target);
      }
      function __kotobaMutationRecord(record) {
        var result = {
          type: record.type,
          target: __kotobaNodeRef(record.targetId),
          addedNodes: (record.addedNodeIds || []).map(__kotobaNodeRef),
          removedNodes: (record.removedNodeIds || []).map(__kotobaNodeRef),
          previousSibling: record.previousSiblingId == null ? null : __kotobaNodeRef(record.previousSiblingId),
          nextSibling: record.nextSiblingId == null ? null : __kotobaNodeRef(record.nextSiblingId),
          attributeName: record.attributeName == null ? null : String(record.attributeName),
          oldValue: record.oldValue == null ? null : String(record.oldValue)
        };
        result.addedNodes.item = function(index) { return result.addedNodes[index] || null; };
        result.removedNodes.item = function(index) { return result.removedNodes[index] || null; };
        return result;
      }
      function __kotobaQueueMutation(record) {
        var observers = (globalThis.__kotobaMutationObservers || []).slice();
        for (var i = 0; i < observers.length; i++) {
          var observer = observers[i];
          var records = [];
          for (var j = 0; j < observer.targets.length; j++) {
            var target = observer.targets[j];
            var options = target.options || {};
            var observesType = (record.type === 'attributes' && options.attributes) ||
              (record.type === 'childList' && options.childList) ||
              (record.type === 'characterData' && options.characterData);
            if (!observesType) continue;
            if (record.targetId !== target.nodeId && !(options.subtree && __kotobaObserverContains(record.targetId, target.nodeId))) continue;
            var next = __kotobaMutationRecord(record);
            if (record.type === 'attributes' && !options.attributeOldValue) next.oldValue = null;
            if (record.type === 'characterData' && !options.characterDataOldValue) next.oldValue = null;
            records.push(next);
          }
          if (records.length) {
            Array.prototype.push.apply(observer.records, records);
            if (!observer.scheduled) {
              observer.scheduled = true;
              __kotobaScheduleMicrotask(function() {
                observer.scheduled = false;
                var pending = observer.records.slice();
                observer.records = [];
                if (pending.length) observer.callback(pending, observer.instance);
              }, {'mutation-observer/id': observer.id});
            }
          }
        }
      }
      function __kotobaAttr(node, name) {
        return node && node.attrs ? node.attrs[name] : null;
      }
      function __kotobaElementByTag(tag) {
        var nodes = globalThis.__kotobaSnapshot.nodes || {};
        var keys = Object.keys(nodes).sort(function(a, b) { return Number(a) - Number(b); });
        tag = String(tag).toLowerCase();
        for (var i = 0; i < keys.length; i++) {
          var node = nodes[keys[i]];
          if (node && node['node/type'] === 'element' && String(node.tag || '').toLowerCase() === tag) {
            return node['node/id'];
          }
        }
        return null;
      }
      function __kotobaDataAttrName(name) {
        return 'data-' + String(name).replace(/[A-Z]/g, function(ch) { return '-' + ch.toLowerCase(); });
      }
      function __kotobaDatasetKey(name) {
        return String(name).slice(5).replace(/-([a-z])/g, function(_, ch) { return ch.toUpperCase(); });
      }
      function __kotobaStyleAttrName(name) {
        return 'style/' + String(name).replace(/[A-Z]/g, function(ch) { return '-' + ch.toLowerCase(); });
      }
      function __kotobaBoolAttr(node, name) {
        var value = __kotobaAttr(node, name);
        return value === true || value === 'true' || value === name || value === '';
      }
      function __kotobaClassList(node) {
        var klass = __kotobaAttr(node, 'class');
        return klass ? String(klass).split(/\\s+/).filter(Boolean) : [];
      }
      function __kotobaFormControl(node) {
        var tag = String(node && node.tag || '').toLowerCase();
        return tag === 'button' || tag === 'input' || tag === 'select' || tag === 'textarea';
      }
      function __kotobaDisabledCapableControl(node) {
        var tag = String(node && node.tag || '').toLowerCase();
        return tag === 'button' || tag === 'fieldset' || tag === 'input' ||
          tag === 'optgroup' || tag === 'option' || tag === 'select' || tag === 'textarea';
      }
      function __kotobaEditableFormControl(node) {
        var tag = String(node && node.tag || '').toLowerCase();
        var type = String(__kotobaAttr(node, 'type') || 'text').toLowerCase();
        return (tag === 'input' &&
          type !== 'hidden' &&
          type !== 'file') ||
          tag === 'textarea';
      }
      function __kotobaDescendantNodeIds(node) {
        var result = [];
        var seen = {};
        function visit(n) {
          var children = n && n.children ? n.children : [];
          for (var i = 0; i < children.length; i++) {
            var childId = children[i];
            if (seen[String(childId)]) continue;
            seen[String(childId)] = true;
            result.push(childId);
            visit(__kotobaNodeById(childId));
          }
        }
        visit(node);
        return result;
      }
      function __kotobaParentNode(node) {
        var nodes = globalThis.__kotobaSnapshot.nodes || {};
        var keys = Object.keys(nodes);
        var id = node && node['node/id'];
        for (var i = 0; i < keys.length; i++) {
          var candidate = nodes[keys[i]];
          var children = candidate && candidate.children ? candidate.children : [];
          if (children.indexOf(id) >= 0) return candidate;
        }
        return null;
      }
      function __kotobaDescendantOrSelf(node, target) {
        if (!node || !target) return false;
        if (node['node/id'] === target['node/id']) return true;
        var children = node.children || [];
        for (var i = 0; i < children.length; i++) {
          if (__kotobaDescendantOrSelf(__kotobaNodeById(children[i]), target)) return true;
        }
        return false;
      }
      function __kotobaFirstLegendChild(node) {
        var children = node && node.children ? node.children : [];
        for (var i = 0; i < children.length; i++) {
          var child = __kotobaNodeById(children[i]);
          if (child && String(child.tag || '').toLowerCase() === 'legend') return child;
        }
        return null;
      }
      function __kotobaDisabledByFieldset(node) {
        var parent = __kotobaParentNode(node);
        while (parent) {
          if (String(parent.tag || '').toLowerCase() === 'fieldset' && __kotobaBoolAttr(parent, 'disabled')) {
            var legend = __kotobaFirstLegendChild(parent);
            if (!legend || !__kotobaDescendantOrSelf(legend, node)) return true;
          }
          parent = __kotobaParentNode(parent);
        }
        return false;
      }
      function __kotobaDisabledByOptgroup(node) {
        if (String(node && node.tag || '').toLowerCase() !== 'option') return false;
        var parent = __kotobaParentNode(node);
        while (parent) {
          if (String(parent.tag || '').toLowerCase() === 'optgroup') {
            return __kotobaBoolAttr(parent, 'disabled');
          }
          parent = __kotobaParentNode(parent);
        }
        return false;
      }
      function __kotobaDisabledControl(node) {
        return __kotobaDisabledCapableControl(node) &&
          (__kotobaBoolAttr(node, 'disabled') ||
           __kotobaDisabledByFieldset(node) ||
           __kotobaDisabledByOptgroup(node));
      }
      function __kotobaTextContent(node) {
        if (!node) return '';
        if (node['node/type'] === 'text') return String(node.text || '');
        var children = node.children || [];
        return children.map(function(childId) { return __kotobaTextContent(__kotobaNodeById(childId)); }).join('');
      }
      function __kotobaOptionValue(optionNode) {
        var value = __kotobaAttr(optionNode, 'value');
        return value == null ? __kotobaTextContent(optionNode) : String(value);
      }
      function __kotobaOptionIds(selectNode) {
        if (!selectNode || String(selectNode.tag || '').toLowerCase() !== 'select') return [];
        return __kotobaDescendantNodeIds(selectNode).filter(function(id) {
          var candidate = __kotobaNodeById(id);
          return candidate && String(candidate.tag || '').toLowerCase() === 'option';
        });
      }
      function __kotobaSelectedOptionIds(selectNode) {
        return __kotobaOptionIds(selectNode).filter(function(id) {
          return __kotobaBoolAttr(__kotobaNodeById(id), 'selected');
        });
      }
      function __kotobaSelectedIndex(selectNode) {
        var options = __kotobaOptionIds(selectNode);
        for (var i = 0; i < options.length; i++) {
          if (__kotobaBoolAttr(__kotobaNodeById(options[i]), 'selected')) return i;
        }
        return options.length && !__kotobaBoolAttr(selectNode, 'multiple') ? 0 : -1;
      }
      function __kotobaSelectValue(node) {
        var descendants = __kotobaDescendantNodeIds(node);
        var firstEnabledOption = null;
        var hasSelectedOption = false;
        var multiple = __kotobaBoolAttr(node, 'multiple');
        for (var i = 0; i < descendants.length; i++) {
          var candidate = __kotobaNodeById(descendants[i]);
          if (candidate && String(candidate.tag || '').toLowerCase() === 'option') {
            var disabled = __kotobaDisabledControl(candidate);
            if (!firstEnabledOption && !disabled) firstEnabledOption = candidate;
            if (__kotobaBoolAttr(candidate, 'selected')) {
              hasSelectedOption = true;
              if (!disabled) return __kotobaOptionValue(candidate);
            }
          }
        }
        return !hasSelectedOption && !multiple && firstEnabledOption ? __kotobaOptionValue(firstEnabledOption) : '';
      }
      function __kotobaRadioGroupNodes(node) {
        var nodes = globalThis.__kotobaSnapshot.nodes || {};
        var keys = Object.keys(nodes).sort(function(a, b) { return Number(a) - Number(b); });
        var name = String(__kotobaAttr(node, 'name'));
        var form = String(__kotobaAttr(node, 'form'));
        var result = [];
        for (var i = 0; i < keys.length; i++) {
          var candidate = nodes[keys[i]];
          if (candidate &&
              String(candidate.tag || '').toLowerCase() === 'input' &&
              String(__kotobaAttr(candidate, 'type') || 'text').toLowerCase() === 'radio' &&
              String(__kotobaAttr(candidate, 'name')) === name &&
              String(__kotobaAttr(candidate, 'form')) === form &&
              !__kotobaDisabledControl(candidate)) {
            result.push(candidate);
          }
        }
        return result;
      }
      function __kotobaRadioRequiredSatisfied(node) {
        var group = __kotobaRadioGroupNodes(node);
        for (var i = 0; i < group.length; i++) {
          if (__kotobaBoolAttr(group[i], 'checked')) return true;
        }
        return false;
      }
      function __kotobaControlValue(node) {
        var textValue = __kotobaAttr(node, 'text/value');
        if (textValue != null) return String(textValue);
        if (String(node && node.tag || '').toLowerCase() === 'select') return __kotobaSelectValue(node);
        var value = __kotobaAttr(node, 'value');
        return value == null ? '' : String(value);
      }
      function __kotobaConstraintInvalid(node) {
        if (__kotobaConstraintValidationBarredControl(node)) return false;
        var value = __kotobaControlValue(node);
        var tag = String(node && node.tag || '').toLowerCase();
        var type = String(__kotobaAttr(node, 'type') || 'text').toLowerCase();
        var minlength = parseInt(__kotobaAttr(node, 'minlength'), 10);
        var maxlength = parseInt(__kotobaAttr(node, 'maxlength'), 10);
        return __kotobaBoolAttr(node, 'invalid') ||
          (__kotobaBoolAttr(node, 'required') &&
            ((tag === 'input' && type === 'checkbox' && !__kotobaBoolAttr(node, 'checked')) ||
             (tag === 'input' && type === 'radio' && !__kotobaRadioRequiredSatisfied(node)) ||
             (!(tag === 'input' && (type === 'checkbox' || type === 'radio')) && value.trim() === ''))) ||
          (!Number.isNaN(minlength) && value.length > 0 && value.length < minlength) ||
          (!Number.isNaN(maxlength) && value.length > maxlength);
      }
      function __kotobaValidationBarredControl(node) {
        var type = String(__kotobaAttr(node, 'type') || 'text').toLowerCase();
        return String(node && node.tag || '').toLowerCase() === 'input' &&
          (type === 'hidden' || type === 'file');
      }
      function __kotobaConstraintValidationBarredControl(node) {
        return __kotobaValidationBarredControl(node) ||
          (__kotobaEditableFormControl(node) && __kotobaBoolAttr(node, 'readonly'));
      }
      function __kotobaConstraintValid(node) {
        return __kotobaFormControl(node) &&
          !__kotobaDisabledControl(node) &&
          !__kotobaConstraintValidationBarredControl(node) &&
          !__kotobaConstraintInvalid(node);
      }
      function __kotobaParseSimpleSelector(selector) {
        selector = String(selector || '').trim();
        var attrPattern = /\\[\\s*([A-Za-z_][-A-Za-z0-9_]*)\\s*(?:(~=|\\|=|\\^=|\\$=|\\*=|=)\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\]\\s]+)))?\\s*\\]/g;
        var attrs = [];
        var attrMatch;
        while ((attrMatch = attrPattern.exec(selector)) !== null) {
          attrs.push({
            name: attrMatch[1],
            operator: attrMatch[2] || null,
            value: attrMatch[3] || attrMatch[4] || attrMatch[5] || null
          });
        }
        var withoutAttrs = selector.replace(attrPattern, '');
        var pseudoPattern = /:([A-Za-z_][-A-Za-z0-9_]*)/g;
        var pseudos = [];
        var pseudoMatch;
        while ((pseudoMatch = pseudoPattern.exec(withoutAttrs)) !== null) {
          pseudos.push(String(pseudoMatch[1]).toLowerCase());
        }
        var withoutPseudos = withoutAttrs.replace(pseudoPattern, '');
        var tagMatch = withoutPseudos.match(/^([A-Za-z][A-Za-z0-9_-]*)/);
        var idMatch = withoutPseudos.match(/#([A-Za-z_][-A-Za-z0-9_]*)/);
        var classes = [];
        var classPattern = /\\.([A-Za-z_][-A-Za-z0-9_]*)/g;
        var match;
        while ((match = classPattern.exec(withoutPseudos)) !== null) classes.push(match[1]);
        return {
          tag: tagMatch ? tagMatch[1].toLowerCase() : null,
          id: idMatch ? idMatch[1] : null,
          classes: classes,
          attrs: attrs,
          pseudos: pseudos
        };
      }
      function __kotobaSelectorTokens(selector) {
        var text = String(selector || '');
        var tokens = [];
        var start = 0;
        var bracketDepth = 0;
        var quoteChar = null;
        function appendToken(end) {
          var token = text.slice(start, end).trim();
          if (token) tokens.push(token);
        }
        for (var i = 0; i < text.length; i++) {
          var ch = text[i];
          var escaped = i > 0 && text[i - 1] === '\\\\';
          if (quoteChar) {
            if (ch === quoteChar && !escaped) quoteChar = null;
          } else if (ch === '\"' || ch === \"'\") {
            quoteChar = ch;
          } else if (ch === '[') {
            bracketDepth += 1;
          } else if (ch === ']') {
            bracketDepth = Math.max(0, bracketDepth - 1);
          } else if (ch === '>' && bracketDepth === 0) {
            appendToken(i);
            tokens.push('>');
            start = i + 1;
          } else if (/\\s/.test(ch) && bracketDepth === 0) {
            appendToken(i);
            start = i + 1;
          }
        }
        appendToken(text.length);
        return tokens;
      }
      function __kotobaParseSelector(selector) {
        var rawTokens = __kotobaSelectorTokens(selector);
        var parts = [];
        var combinator = null;
        for (var i = 0; i < rawTokens.length; i++) {
          if (rawTokens[i] === '>') {
            combinator = 'child';
          } else {
            var simple = __kotobaParseSimpleSelector(rawTokens[i]);
            simple.combinator = parts.length ? (combinator || 'descendant') : null;
            parts.push(simple);
            combinator = null;
          }
        }
        return parts;
      }
      function __kotobaSplitSelectorList(selectorList) {
        var text = String(selectorList || '');
        var selectors = [];
        var start = 0;
        var bracketDepth = 0;
        var quoteChar = null;
        for (var i = 0; i < text.length; i++) {
          var ch = text[i];
          var escaped = i > 0 && text[i - 1] === '\\\\';
          if (quoteChar) {
            if (ch === quoteChar && !escaped) quoteChar = null;
          } else if (ch === '\"' || ch === \"'\") {
            quoteChar = ch;
          } else if (ch === '[') {
            bracketDepth += 1;
          } else if (ch === ']') {
            bracketDepth = Math.max(0, bracketDepth - 1);
          } else if (ch === ',' && bracketDepth === 0) {
            var selector = text.slice(start, i).trim();
            if (selector) selectors.push(selector);
            start = i + 1;
          }
        }
        var last = text.slice(start).trim();
        if (last) selectors.push(last);
        return selectors;
      }
      function __kotobaMatchesSimple(node, simple) {
        if (!node || node['node/type'] !== 'element') return false;
        if (simple.tag && String(node.tag || '').toLowerCase() !== simple.tag) return false;
        if (simple.id && __kotobaAttr(node, 'id') !== simple.id) return false;
        var nodeClasses = __kotobaClassList(node);
        for (var i = 0; i < simple.classes.length; i++) {
          if (nodeClasses.indexOf(simple.classes[i]) < 0) return false;
        }
        for (var a = 0; a < simple.attrs.length; a++) {
          var attr = simple.attrs[a];
          var actual = __kotobaAttr(node, attr.name);
          if (actual == null) return false;
          actual = String(actual);
          var expected = String(attr.value == null ? '' : attr.value);
          switch (attr.operator) {
            case null:
              break;
            case '=':
              if (actual !== expected) return false;
              break;
            case '~=':
              if (actual.split(/\\s+/).filter(Boolean).indexOf(expected) < 0) return false;
              break;
            case '^=':
              if (actual.indexOf(expected) !== 0) return false;
              break;
            case '$=':
              if (actual.slice(-expected.length) !== expected) return false;
              break;
            case '*=':
              if (actual.indexOf(expected) < 0) return false;
              break;
            case '|=':
              if (actual !== expected && actual.indexOf(expected + '-') !== 0) return false;
              break;
            default:
              return false;
          }
        }
        for (var p = 0; p < simple.pseudos.length; p++) {
          switch (simple.pseudos[p]) {
            case 'disabled':
              if (!__kotobaDisabledControl(node)) return false;
              break;
            case 'enabled':
              if (!__kotobaFormControl(node) || __kotobaDisabledControl(node)) return false;
              break;
            case 'checked':
              if (!__kotobaBoolAttr(node, 'checked')) return false;
              break;
            case 'required':
              if (!__kotobaFormControl(node) || __kotobaDisabledControl(node) || __kotobaValidationBarredControl(node) || !__kotobaBoolAttr(node, 'required')) return false;
              break;
            case 'optional':
              if (!__kotobaFormControl(node) || __kotobaDisabledControl(node) || __kotobaValidationBarredControl(node) || __kotobaBoolAttr(node, 'required')) return false;
              break;
            case 'read-only':
              if (__kotobaValidationBarredControl(node) || (__kotobaEditableFormControl(node) && !__kotobaBoolAttr(node, 'readonly'))) return false;
              break;
            case 'read-write':
              if (!__kotobaEditableFormControl(node) || __kotobaBoolAttr(node, 'readonly') || __kotobaDisabledControl(node)) return false;
              break;
            case 'invalid':
              if (!__kotobaFormControl(node) || __kotobaDisabledControl(node) || __kotobaConstraintValidationBarredControl(node) || !__kotobaConstraintInvalid(node)) return false;
              break;
            case 'valid':
              if (!__kotobaConstraintValid(node)) return false;
              break;
            case 'focus':
              if (!globalThis.__kotobaSnapshot || node['node/id'] !== globalThis.__kotobaSnapshot.focus) return false;
              break;
            default:
              return false;
          }
        }
        return true;
      }
      function __kotobaMatchesParts(node, parts, idx) {
        if (!node || idx < 0) return false;
        var simple = parts[idx];
        if (!__kotobaMatchesSimple(node, simple)) return false;
        if (idx === 0) return true;
        if (simple.combinator === 'child') {
          return __kotobaMatchesParts(__kotobaNodeById(node['parent/id']), parts, idx - 1);
        }
        if (simple.combinator === 'descendant') {
          var ancestor = __kotobaNodeById(node['parent/id']);
          while (ancestor) {
            if (__kotobaMatchesParts(ancestor, parts, idx - 1)) return true;
            ancestor = __kotobaNodeById(ancestor['parent/id']);
          }
        }
        return false;
      }
      function __kotobaMatches(node, selector) {
        var groups = __kotobaSplitSelectorList(selector);
        for (var i = 0; i < groups.length; i++) {
          var parts = __kotobaParseSelector(groups[i]);
          if (parts.length && __kotobaMatchesParts(node, parts, parts.length - 1)) return true;
        }
        return false;
      }
      function __kotobaQuerySelectorId(selector) {
        var ids = __kotobaQuerySelectorAllIds(selector);
        return ids.length ? ids[0] : null;
      }
      function __kotobaQuerySelectorAllIds(selector) {
        var ids = [globalThis.__kotobaSnapshot.root].concat(__kotobaDescendantNodeIds(__kotobaNodeById(globalThis.__kotobaSnapshot.root)));
        var result = [];
        for (var i = 0; i < ids.length; i++) {
          var node = __kotobaNodeById(ids[i]);
          if (node && node['node/type'] === 'element' && __kotobaMatches(node, selector)) result.push(node['node/id']);
        }
        return result;
      }
      function __kotobaElementIdsByTagName(tagName) {
        return __kotobaElementIdsByTagNameFromIds(
          [globalThis.__kotobaSnapshot.root].concat(__kotobaDescendantNodeIds(__kotobaNodeById(globalThis.__kotobaSnapshot.root))),
          tagName);
      }
      function __kotobaElementIdsByTagNameFromIds(ids, tagName) {
        var tag = String(tagName).toLowerCase();
        var result = [];
        for (var i = 0; i < ids.length; i++) {
          var node = __kotobaNodeById(ids[i]);
          if (node && node['node/type'] === 'element' &&
              (tag === '*' || String(node.tag || '').toLowerCase() === tag)) {
            result.push(node['node/id']);
          }
        }
        return result;
      }
      function __kotobaElementIdsByClassName(className) {
        return __kotobaElementIdsByClassNameFromIds(
          [globalThis.__kotobaSnapshot.root].concat(__kotobaDescendantNodeIds(__kotobaNodeById(globalThis.__kotobaSnapshot.root))),
          className);
      }
      function __kotobaElementIdsByClassNameFromIds(ids, className) {
        var names = String(className).split(/\\s+/).filter(Boolean);
        if (!names.length) return [];
        var result = [];
        for (var i = 0; i < ids.length; i++) {
          var node = __kotobaNodeById(ids[i]);
          if (node && node['node/type'] === 'element') {
            var classes = __kotobaClassList(node);
            var matches = names.every(function(name) { return classes.indexOf(name) >= 0; });
            if (matches) result.push(node['node/id']);
          }
        }
        return result;
      }
      function __kotobaDocumentLinkIds() {
        var ids = [globalThis.__kotobaSnapshot.root].concat(__kotobaDescendantNodeIds(__kotobaNodeById(globalThis.__kotobaSnapshot.root)));
        var result = [];
        for (var i = 0; i < ids.length; i++) {
          var node = __kotobaNodeById(ids[i]);
          var tag = String(node && node.tag || '').toLowerCase();
          if (node && node['node/type'] === 'element' &&
              (tag === 'a' || tag === 'area') &&
              __kotobaAttr(node, 'href') != null) {
            result.push(node['node/id']);
          }
        }
        return result;
      }
      function __kotobaScopedQuerySelectorAllIds(rootId, selector) {
        var descendantIds = __kotobaDescendantNodeIds(__kotobaNodeById(rootId));
        return descendantIds.filter(function(id) {
          var node = __kotobaNodeById(id);
          return node && node['node/type'] === 'element' && __kotobaMatches(node, selector);
        });
      }
      function __kotobaScopedElementIds(rootId, ids) {
        var descendantIds = __kotobaDescendantNodeIds(__kotobaNodeById(rootId));
        var descendantSet = {};
        for (var i = 0; i < descendantIds.length; i++) {
          descendantSet[__kotobaNodeKey(descendantIds[i])] = true;
        }
        return ids.filter(function(id) {
          return Boolean(descendantSet[__kotobaNodeKey(id)]);
        });
      }
      function __kotobaScopedQuerySelectorId(rootId, selector) {
        var ids = __kotobaScopedQuerySelectorAllIds(rootId, selector);
        return ids.length ? ids[0] : null;
      }
      function __kotobaChildElements(nodeId) {
        var node = __kotobaNodeById(nodeId);
        var children = node && node.children ? node.children : [];
        return children.filter(function(childId) {
          var child = __kotobaNodeById(childId);
          return child && child['node/type'] === 'element';
        });
      }
      function __kotobaChildNodeId(nodeId, elementsOnly, last) {
        var node = __kotobaNodeById(nodeId);
        var children = node && node.children ? node.children : [];
        var ids = elementsOnly ? children.filter(function(childId) {
          var child = __kotobaNodeById(childId);
          return child && child['node/type'] === 'element';
        }) : children;
        if (!ids.length) return null;
        return last ? ids[ids.length - 1] : ids[0];
      }
      function __kotobaSiblingNodeId(nodeId, elementsOnly, next) {
        var node = __kotobaNodeById(nodeId);
        var parent = node && node['parent/id'] != null ? __kotobaNodeById(node['parent/id']) : null;
        var children = parent && parent.children ? parent.children : [];
        var ids = elementsOnly ? children.filter(function(childId) {
          var child = __kotobaNodeById(childId);
          return child && child['node/type'] === 'element';
        }) : children;
        var idx = ids.indexOf(nodeId);
        if (idx < 0) return null;
        var siblingIdx = next ? idx + 1 : idx - 1;
        return siblingIdx >= 0 && siblingIdx < ids.length ? ids[siblingIdx] : null;
      }
      function __kotobaFormOwnerId(node) {
        var explicit = __kotobaAttr(node, 'form');
        if (explicit) return __kotobaQuerySelectorId('#' + String(explicit));
        var parent = __kotobaParentNode(node);
        while (parent) {
          if (String(parent.tag || '').toLowerCase() === 'form') return parent['node/id'];
          parent = __kotobaParentNode(parent);
        }
        return null;
      }
      function __kotobaLabelControlId(label) {
        if (!label || String(label.tag || '').toLowerCase() !== 'label') return null;
        var explicit = __kotobaAttr(label, 'for');
        if (explicit) return __kotobaQuerySelectorId('#' + String(explicit));
        var descendants = __kotobaDescendantNodeIds(label);
        for (var i = 0; i < descendants.length; i++) {
          var candidate = __kotobaNodeById(descendants[i]);
          if (__kotobaFormControl(candidate)) return candidate['node/id'];
        }
        return null;
      }
      function __kotobaLabelIdsForControl(node) {
        var id = __kotobaAttr(node, 'id');
        var result = [];
        var nodes = globalThis.__kotobaSnapshot.nodes || {};
        var keys = Object.keys(nodes).sort(function(a, b) { return Number(a) - Number(b); });
        for (var i = 0; i < keys.length; i++) {
          var label = nodes[keys[i]];
          if (!label || String(label.tag || '').toLowerCase() !== 'label') continue;
          var explicit = __kotobaAttr(label, 'for');
          if ((explicit && id && String(explicit) === String(id)) ||
              (!explicit && __kotobaDescendantOrSelf(label, node) && label['node/id'] !== node['node/id'])) {
            result.push(label['node/id']);
          }
        }
        return result;
      }
      function __kotobaElements(ids) {
        var result = ids.map(function(id) { return __kotobaElement({ nodeId: id }); });
        result.item = function(index) { return result[index] || null; };
        return result;
      }
      function __kotobaRememberNode(node) {
        globalThis.__kotobaSnapshot.nodes[__kotobaNodeKey(node['node/id'])] = node;
        return node;
      }
      function __kotobaCloneSnapshotNode(source, deep) {
        var id = __kotobaClientId();
        var clone = {
          'node/id': id,
          'node/type': source && source['node/type']
        };
        if (source && source.tag != null) clone.tag = source.tag;
        if (source && source.text != null) clone.text = source.text;
        if (source && source.attrs) {
          clone.attrs = {};
          Object.keys(source.attrs).forEach(function(name) {
            clone.attrs[name] = source.attrs[name];
          });
        }
        if (source && source.children) clone.children = [];
        clone['text-content'] = source && source['text-content'] != null ? source['text-content'] : '';
        __kotobaRememberNode(clone);
        globalThis.__kotobaClientNodes[id] = clone;
        if (deep && source && source.children) {
          for (var i = 0; i < source.children.length; i++) {
            var childClone = __kotobaCloneSnapshotNode(__kotobaNodeById(source.children[i]), true);
            var childId = __kotobaRefNodeId(childClone.__kotobaRef);
            clone.children.push(childId);
            var childNode = __kotobaNodeById(childId);
            if (childNode) childNode['parent/id'] = id;
          }
          __kotobaRefreshText(id);
        } else if (clone.children) {
          clone.children = [];
          __kotobaRefreshText(id);
        }
        return __kotobaElement({ clientId: id });
      }
      function __kotobaRefreshText(nodeId) {
        var node = __kotobaNodeById(nodeId);
        if (!node) return '';
        if (node['node/type'] === 'text') return String(node.text || '');
        var children = node.children || [];
        var text = children.map(function(childId) { return __kotobaRefreshText(childId); }).join('');
        node['text-content'] = text;
        return text;
      }
      function __kotobaEscapeHtml(value) {
        return String(value == null ? '' : value)
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .split(String.fromCharCode(34)).join('&quot;');
      }
      function __kotobaSerializeNode(node) {
        if (!node) return '';
        if (node['node/type'] === 'text') return __kotobaEscapeHtml(node.text || '');
        if (node['node/type'] === 'document-fragment') {
          return (node.children || []).map(function(id) { return __kotobaSerializeNode(__kotobaNodeById(id)); }).join('');
        }
        var tag = String(node.tag || '').toLowerCase();
        var attrs = node.attrs || {};
        var attrText = Object.keys(attrs).sort().map(function(name) {
          return ' ' + name + '=' + String.fromCharCode(34) + __kotobaEscapeHtml(attrs[name]) + String.fromCharCode(34);
        }).join('');
        var body = (node.children || []).map(function(id) { return __kotobaSerializeNode(__kotobaNodeById(id)); }).join('');
        return '<' + tag + attrText + '>' + body + '</' + tag + '>';
      }
      function __kotobaTextNode(text) {
        var textId = __kotobaClientId();
        var textNode = __kotobaRememberNode({
          'node/id': textId,
          'node/type': 'text',
          text: String(text)
        });
        globalThis.__kotobaRequests.push({
          capability: 'dom/mutate',
          'dom/op': 'create-text',
          'client/id': textId,
          text: String(text)
        });
        globalThis.__kotobaClientNodes[textId] = textNode;
        return __kotobaElement({ clientId: textId });
      }
      function __kotobaSetTextNodeData(ref, value) {
        var text = String(value);
        var request = {
          capability: 'dom/mutate',
          'dom/op': 'set-text',
          text: text
        };
        Object.assign(request, __kotobaNodeRequest(ref, 'node'));
        globalThis.__kotobaRequests.push(request);
        var node = __kotobaNodeById(__kotobaRefNodeId(ref));
        if (node) {
          var oldText = String(node.text || '');
          node.text = text;
          node['text-content'] = text;
          if (node['parent/id'] != null) __kotobaRefreshText(node['parent/id']);
          __kotobaQueueMutation({
            type: 'characterData',
            targetId: node['node/id'],
            oldValue: oldText
          });
        }
      }
      function __kotobaNormalizeNode(nodeId) {
        var node = __kotobaNodeById(nodeId);
        if (!node || !node.children) return;
        var next = [];
        var activeTextId = null;
        var children = (node.children || []).slice();
        for (var i = 0; i < children.length; i++) {
          var child = __kotobaNodeById(children[i]);
          if (child && child.children) __kotobaNormalizeNode(children[i]);
          if (child && child['node/type'] === 'text') {
            var text = String(child.text || '');
            if (!text.length) {
              delete child['parent/id'];
            } else if (activeTextId != null) {
              var active = __kotobaNodeById(activeTextId);
              active.text = String(active.text || '') + text;
              delete child['parent/id'];
            } else {
              next.push(children[i]);
              activeTextId = children[i];
            }
          } else {
            next.push(children[i]);
            activeTextId = null;
          }
        }
        node.children = next;
        __kotobaRefreshText(nodeId);
      }
      function __kotobaNodeArg(value) {
        return value && value.__kotobaRef ? value : __kotobaTextNode(value == null ? '' : value);
      }
      function __kotobaHandlerId() {
        var id = 'handler-' + globalThis.__kotobaNextHandlerId;
        globalThis.__kotobaNextHandlerId = globalThis.__kotobaNextHandlerId + 1;
        return id;
      }
      function __kotobaNodeRequest(ref, prefix) {
        var request = {};
        if (ref.clientId) {
          request[prefix + '/client-id'] = ref.clientId;
        } else if (ref.nodeId != null) {
          request[prefix + '/id'] = ref.nodeId;
        } else if (ref.selector) {
          request[prefix + '/selector'] = ref.selector;
        }
        return request;
      }
      function __kotobaSetAttribute(ref, name, value) {
        var request = {
          capability: 'dom/mutate',
          'dom/op': 'set-attribute',
          attr: String(name),
          value: String(value)
        };
        Object.assign(request, __kotobaNodeRequest(ref, 'node'));
        globalThis.__kotobaRequests.push(request);
        var node = __kotobaNodeById(__kotobaRefNodeId(ref));
        if (node) {
          var oldValue = __kotobaAttr(node, String(name));
          node.attrs = node.attrs || {};
          node.attrs[String(name)] = String(value);
          __kotobaQueueMutation({
            type: 'attributes',
            targetId: node['node/id'],
            attributeName: String(name),
            oldValue: oldValue
          });
        }
      }
      function __kotobaRemoveAttribute(ref, name) {
        var request = {
          capability: 'dom/mutate',
          'dom/op': 'remove-attribute',
          attr: String(name)
        };
        Object.assign(request, __kotobaNodeRequest(ref, 'node'));
        globalThis.__kotobaRequests.push(request);
        var node = __kotobaNodeById(__kotobaRefNodeId(ref));
        if (node && node.attrs) {
          var oldValue = __kotobaAttr(node, String(name));
          delete node.attrs[String(name)];
          __kotobaQueueMutation({
            type: 'attributes',
            targetId: node['node/id'],
            attributeName: String(name),
            oldValue: oldValue
          });
        }
      }
      function __kotobaSetBooleanAttribute(ref, name, value) {
        if (value) __kotobaSetAttribute(ref, name, 'true');
        else __kotobaRemoveAttribute(ref, name);
      }
      function __kotobaStringAttribute(ref, name) {
        var node = __kotobaNodeById(__kotobaRefNodeId(ref));
        var value = __kotobaAttr(node, name);
        return value == null ? '' : String(value);
      }
      function __kotobaUrlAttribute(ref, name) {
        var value = __kotobaStringAttribute(ref, name);
        if (!value) return '';
        var base = globalThis.__kotobaSnapshot && globalThis.__kotobaSnapshot['base-uri'] != null
          ? String(globalThis.__kotobaSnapshot['base-uri'])
          : (globalThis.location && globalThis.location.href ? String(globalThis.location.href) : 'about:blank');
        return __kotobaResolveUrl(value, base);
      }
      function __kotobaAttributes(ref) {
        var node = __kotobaNodeById(__kotobaRefNodeId(ref));
        var attrs = node && node.attrs ? node.attrs : {};
        var names = Object.keys(attrs).sort();
        var result = names.map(function(name) {
          return { name: name, value: String(attrs[name]), nodeName: name, nodeValue: String(attrs[name]) };
        });
        result.item = function(index) { return result[index] || null; };
        result.getNamedItem = function(name) {
          name = String(name);
          return Object.prototype.hasOwnProperty.call(attrs, name)
            ? { name: name, value: String(attrs[name]), nodeName: name, nodeValue: String(attrs[name]) }
            : null;
        };
        return result;
      }
      function __kotobaClassTokenList(ref) {
        function tokens() {
          return __kotobaClassList(__kotobaNodeById(__kotobaRefNodeId(ref)));
        }
        function write(next) {
          __kotobaSetAttribute(ref, 'class', next.filter(Boolean).join(' '));
        }
        return {
          contains: function(token) { return tokens().indexOf(String(token)) >= 0; },
          add: function() {
            var next = tokens();
            for (var i = 0; i < arguments.length; i++) {
              var token = String(arguments[i]);
              if (next.indexOf(token) < 0) next.push(token);
            }
            write(next);
          },
          remove: function() {
            var remove = Array.prototype.map.call(arguments, String);
            write(tokens().filter(function(token) { return remove.indexOf(token) < 0; }));
          },
          toggle: function(token, force) {
            token = String(token);
            var next = tokens();
            var present = next.indexOf(token) >= 0;
            var shouldAdd = force === undefined ? !present : Boolean(force);
            if (shouldAdd && !present) next.push(token);
            if (!shouldAdd && present) next = next.filter(function(t) { return t !== token; });
            write(next);
            return shouldAdd;
          },
          toString: function() { return tokens().join(' '); },
          get value() { return tokens().join(' '); }
        };
      }
      function __kotobaDataSet(ref) {
        return new Proxy({}, {
          get: function(_, prop) {
            if (prop === 'toJSON') return function() {
              var node = __kotobaNodeById(__kotobaRefNodeId(ref));
              var attrs = node && node.attrs ? node.attrs : {};
              return Object.keys(attrs).reduce(function(out, name) {
                if (name.indexOf('data-') === 0) out[__kotobaDatasetKey(name)] = String(attrs[name]);
                return out;
              }, {});
            };
            if (typeof prop === 'symbol') return undefined;
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var value = __kotobaAttr(node, __kotobaDataAttrName(prop));
            return value == null ? undefined : String(value);
          },
          set: function(_, prop, value) {
            __kotobaSetAttribute(ref, __kotobaDataAttrName(prop), value);
            return true;
          },
          deleteProperty: function(_, prop) {
            __kotobaRemoveAttribute(ref, __kotobaDataAttrName(prop));
            return true;
          }
        });
      }
      function __kotobaStyle(ref) {
        return new Proxy({}, {
          get: function(_, prop) {
            if (prop === 'setProperty') return function(name, value) {
              __kotobaSetAttribute(ref, __kotobaStyleAttrName(name), value);
            };
            if (prop === 'getPropertyValue') return function(name) {
              var node = __kotobaNodeById(__kotobaRefNodeId(ref));
              var value = __kotobaAttr(node, __kotobaStyleAttrName(name));
              return value == null ? '' : String(value);
            };
            if (prop === 'removeProperty') return function(name) {
              var node = __kotobaNodeById(__kotobaRefNodeId(ref));
              var attrName = __kotobaStyleAttrName(name);
              var value = __kotobaAttr(node, attrName);
              __kotobaRemoveAttribute(ref, attrName);
              return value == null ? '' : String(value);
            };
            if (typeof prop === 'symbol') return undefined;
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var value = __kotobaAttr(node, __kotobaStyleAttrName(prop));
            return value == null ? '' : String(value);
          },
          set: function(_, prop, value) {
            __kotobaSetAttribute(ref, __kotobaStyleAttrName(prop), value);
            return true;
          }
        });
      }
      function __kotobaEvent(type, init) {
        var event = init || {};
        event.type = String(type);
        event['event/type'] = String(type);
        if (event.bubbles == null) event.bubbles = false;
        if (event.cancelable == null) event.cancelable = false;
        if (event.composed == null) event.composed = false;
        event.defaultPrevented = false;
        event.cancelBubble = false;
        event.preventDefault = function() {
          if (event.cancelable) event.defaultPrevented = true;
        };
        event.stopPropagation = function() {
          event.cancelBubble = true;
        };
        return event;
      }
      function __kotobaEventPayload(event, targetId) {
        var targetNode = __kotobaNodeById(targetId);
        var value = __kotobaAttr(targetNode, 'value');
        var checked = __kotobaBoolAttr(targetNode, 'checked');
        return {
          'event/type': String(event && (event['event/type'] || event.type || 'event')),
          type: String(event && (event.type || event['event/type'] || 'event')),
          'target/id': targetId,
          value: value == null ? null : String(value),
          checked: checked,
          bubbles: Boolean(event && event.bubbles),
          cancelable: Boolean(event && event.cancelable),
          composed: Boolean(event && event.composed),
          defaultPrevented: Boolean(event && event.defaultPrevented),
          cancelBubble: Boolean(event && event.cancelBubble),
          detail: event && Object.prototype.hasOwnProperty.call(event, 'detail') ? event.detail : null,
          key: event && event.key != null ? String(event.key) : null,
          code: event && event.code != null ? String(event.code) : null,
          button: event && event.button != null ? Number(event.button) : null,
          clientX: event && event.clientX != null ? Number(event.clientX) : null,
          clientY: event && event.clientY != null ? Number(event.clientY) : null
        };
      }
      function __kotobaDispatch(ref, event) {
        var targetId = __kotobaRefNodeId(ref);
        var eventType = String(event && (event['event/type'] || event.type || 'event'));
        event = event || __kotobaEvent(eventType, {});
        var payload = __kotobaEventPayload(event || __kotobaEvent(eventType, {}), targetId);
        var request = {
          capability: 'event/dispatch',
          event: payload
        };
        Object.assign(request, __kotobaNodeRequest(ref, 'node'));
        globalThis.__kotobaRequests.push(request);
        event.target = __kotobaElement(ref);
        var currentId = targetId;
        while (currentId != null) {
          var currentTarget = __kotobaElement({ nodeId: currentId });
          var key = __kotobaNodeKey(currentId) + ':' + eventType;
          var listeners = (globalThis.__kotobaListeners[key] || []).slice();
          event.currentTarget = currentTarget;
          for (var i = 0; i < listeners.length; i++) {
            listeners[i].call(currentTarget, event);
            if (event.cancelBubble) break;
          }
          if (!event.bubbles || event.cancelBubble) break;
          var currentNode = __kotobaNodeById(currentId);
          currentId = currentNode && currentNode['parent/id'] != null ? currentNode['parent/id'] : null;
        }
        return !(event && event.defaultPrevented);
      }
      function __kotobaElement(ref) {
        var elementId = __kotobaRefNodeId(ref);
        var elementCacheKey = elementId == null ? null : __kotobaNodeKey(elementId);
        if (elementCacheKey && globalThis.__kotobaElementCache[elementCacheKey]) {
          return globalThis.__kotobaElementCache[elementCacheKey];
        }
        var element = {
          __kotobaRef: ref,
          get nodeType() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (node && node['node/type'] === 'text') return 3;
            if (node && node['node/type'] === 'document-fragment') return 11;
            return 1;
          },
          get tagName() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return node && node.tag ? String(node.tag).toUpperCase() : '';
          },
          get nodeName() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (node && node['node/type'] === 'text') return '#text';
            if (node && node['node/type'] === 'document-fragment') return '#document-fragment';
            return this.tagName;
          },
          get localName() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return node && node.tag ? String(node.tag).toLowerCase() : '';
          },
          get namespaceURI() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaAttr(node, 'namespace-uri') || null;
          },
          get ownerDocument() {
            return globalThis.document;
          },
          get isConnected() {
            var root = __kotobaNodeById(globalThis.__kotobaSnapshot.root);
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaDescendantOrSelf(root, node);
          },
          get id() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaAttr(node, 'id') || '';
          },
          set id(value) {
            this.setAttribute('id', value);
          },
          get className() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaAttr(node, 'class') || '';
          },
          set className(value) {
            this.setAttribute('class', value);
          },
          get type() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaAttr(node, 'type') || '';
          },
          set type(value) {
            this.setAttribute('type', value);
          },
          get name() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaAttr(node, 'name') || '';
          },
          set name(value) {
            this.setAttribute('name', value);
          },
          get htmlFor() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaAttr(node, 'for') || '';
          },
          set htmlFor(value) {
            this.setAttribute('for', value);
          },
          get href() {
            return __kotobaUrlAttribute(ref, 'href');
          },
          set href(value) {
            this.setAttribute('href', value);
          },
          get src() {
            return __kotobaUrlAttribute(ref, 'src');
          },
          set src(value) {
            this.setAttribute('src', value);
          },
          get alt() {
            return __kotobaStringAttribute(ref, 'alt');
          },
          set alt(value) {
            this.setAttribute('alt', value);
          },
          get form() {
            var id = __kotobaFormOwnerId(__kotobaNodeById(__kotobaRefNodeId(ref)));
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get labels() {
            return __kotobaElements(__kotobaLabelIdsForControl(__kotobaNodeById(__kotobaRefNodeId(ref))));
          },
          get control() {
            var id = __kotobaLabelControlId(__kotobaNodeById(__kotobaRefNodeId(ref)));
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get value() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (String(node && node.tag || '').toLowerCase() === 'select') return __kotobaSelectValue(node);
            var value = __kotobaAttr(node, 'value');
            return value == null ? '' : String(value);
          },
          set value(value) {
            this.setAttribute('value', value);
          },
          get checked() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'checked');
          },
          set checked(value) {
            __kotobaSetBooleanAttribute(ref, 'checked', value);
          },
          get defaultChecked() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'default-checked');
          },
          set defaultChecked(value) {
            __kotobaSetBooleanAttribute(ref, 'default-checked', value);
          },
          get defaultValue() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var value = __kotobaAttr(node, 'default-value');
            return value == null ? '' : String(value);
          },
          set defaultValue(value) {
            this.setAttribute('default-value', value);
          },
          get disabled() {
            return __kotobaDisabledControl(__kotobaNodeById(__kotobaRefNodeId(ref)));
          },
          set disabled(value) {
            __kotobaSetBooleanAttribute(ref, 'disabled', value);
          },
          get required() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'required');
          },
          set required(value) {
            __kotobaSetBooleanAttribute(ref, 'required', value);
          },
          get readOnly() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'readonly');
          },
          set readOnly(value) {
            __kotobaSetBooleanAttribute(ref, 'readonly', value);
          },
          get multiple() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'multiple');
          },
          set multiple(value) {
            __kotobaSetBooleanAttribute(ref, 'multiple', value);
          },
          get async() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'async');
          },
          set async(value) {
            __kotobaSetBooleanAttribute(ref, 'async', value);
          },
          get defer() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'defer');
          },
          set defer(value) {
            __kotobaSetBooleanAttribute(ref, 'defer', value);
          },
          get complete() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var tag = String(node && node.tag || '').toLowerCase();
            return tag === 'img' ? Boolean(__kotobaAttr(node, 'src')) : true;
          },
          get selected() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'selected');
          },
          set selected(value) {
            __kotobaSetBooleanAttribute(ref, 'selected', value);
          },
          get defaultSelected() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'default-selected');
          },
          set defaultSelected(value) {
            __kotobaSetBooleanAttribute(ref, 'default-selected', value);
          },
          get options() {
            return __kotobaElements(__kotobaOptionIds(__kotobaNodeById(__kotobaRefNodeId(ref))));
          },
          get selectedOptions() {
            return __kotobaElements(__kotobaSelectedOptionIds(__kotobaNodeById(__kotobaRefNodeId(ref))));
          },
          get selectedIndex() {
            return __kotobaSelectedIndex(__kotobaNodeById(__kotobaRefNodeId(ref)));
          },
          set selectedIndex(value) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var options = __kotobaOptionIds(node);
            var index = Number(value);
            for (var i = 0; i < options.length; i++) {
              var option = __kotobaElement({ nodeId: options[i] });
              if (i === index) option.setAttribute('selected', 'true');
              else option.removeAttribute('selected');
            }
            var selected = index >= 0 && index < options.length ? __kotobaNodeById(options[index]) : null;
            this.setAttribute('value', selected ? __kotobaOptionValue(selected) : '');
          },
          get selectionStart() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return node && node['selection-start'] != null ? node['selection-start'] : this.value.length;
          },
          set selectionStart(value) {
            this.setSelectionRange(Number(value), this.selectionEnd);
          },
          get selectionEnd() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return node && node['selection-end'] != null ? node['selection-end'] : this.value.length;
          },
          set selectionEnd(value) {
            this.setSelectionRange(this.selectionStart, Number(value));
          },
          get parentNode() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return node && node['parent/id'] != null ? __kotobaElement({ nodeId: node['parent/id'] }) : null;
          },
          get parentElement() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var parent = node && node['parent/id'] != null ? __kotobaNodeById(node['parent/id']) : null;
            return parent && parent['node/type'] === 'element' ? __kotobaElement({ nodeId: parent['node/id'] }) : null;
          },
          get firstChild() {
            var id = __kotobaChildNodeId(__kotobaRefNodeId(ref), false, false);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get lastChild() {
            var id = __kotobaChildNodeId(__kotobaRefNodeId(ref), false, true);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get firstElementChild() {
            var id = __kotobaChildNodeId(__kotobaRefNodeId(ref), true, false);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get lastElementChild() {
            var id = __kotobaChildNodeId(__kotobaRefNodeId(ref), true, true);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get previousSibling() {
            var id = __kotobaSiblingNodeId(__kotobaRefNodeId(ref), false, false);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get nextSibling() {
            var id = __kotobaSiblingNodeId(__kotobaRefNodeId(ref), false, true);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get previousElementSibling() {
            var id = __kotobaSiblingNodeId(__kotobaRefNodeId(ref), true, false);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get nextElementSibling() {
            var id = __kotobaSiblingNodeId(__kotobaRefNodeId(ref), true, true);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          get childNodes() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaElements(node && node.children ? node.children : []);
          },
          get children() {
            return __kotobaElements(__kotobaChildElements(__kotobaRefNodeId(ref)));
          },
          get attributes() {
            return __kotobaAttributes(ref);
          },
          get classList() {
            return __kotobaClassTokenList(ref);
          },
          get dataset() {
            return __kotobaDataSet(ref);
          },
          get style() {
            return __kotobaStyle(ref);
          },
          get textContent() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (!node) return '';
            return node['text-content'] != null ? String(node['text-content']) : String(node.text || '');
          },
          set textContent(value) {
            __kotobaSetTextContent(element, value);
          },
          get nodeValue() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return node && node['node/type'] === 'text' ? String(node.text || '') : null;
          },
          set nodeValue(value) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (node && node['node/type'] === 'text') __kotobaSetTextNodeData(ref, value);
          },
          get data() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return node && node['node/type'] === 'text' ? String(node.text || '') : undefined;
          },
          set data(value) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (node && node['node/type'] === 'text') __kotobaSetTextNodeData(ref, value);
          },
          substringData: function(offset, count) {
            var value = this.data || '';
            var start = Math.max(0, Number(offset) || 0);
            var length = Math.max(0, Number(count) || 0);
            return value.slice(start, start + length);
          },
          appendData: function(value) {
            this.data = (this.data || '') + String(value);
          },
          insertData: function(offset, value) {
            var current = this.data || '';
            var start = Math.max(0, Math.min(current.length, Number(offset) || 0));
            this.data = current.slice(0, start) + String(value) + current.slice(start);
          },
          deleteData: function(offset, count) {
            var current = this.data || '';
            var start = Math.max(0, Math.min(current.length, Number(offset) || 0));
            var length = Math.max(0, Number(count) || 0);
            this.data = current.slice(0, start) + current.slice(start + length);
          },
          replaceData: function(offset, count, value) {
            var current = this.data || '';
            var start = Math.max(0, Math.min(current.length, Number(offset) || 0));
            var length = Math.max(0, Number(count) || 0);
            this.data = current.slice(0, start) + String(value) + current.slice(start + length);
          },
          splitText: function(offset) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var current = node && node['node/type'] === 'text' ? String(node.text || '') : '';
            var splitAt = Math.max(0, Math.min(current.length, Number(offset) || 0));
            var before = current.slice(0, splitAt);
            var after = current.slice(splitAt);
            var newId = __kotobaClientId();
            var newNode = __kotobaRememberNode({
              'node/id': newId,
              'node/type': 'text',
              text: after
            });
            globalThis.__kotobaClientNodes[newId] = newNode;
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'split-text',
              'client/id': newId,
              offset: splitAt
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
            if (node) {
              node.text = before;
              if (node['parent/id'] != null) {
                var parent = __kotobaNodeById(node['parent/id']);
                var children = parent && parent.children ? parent.children.slice() : [];
                var idx = children.indexOf(node['node/id']);
                if (idx >= 0) children.splice(idx + 1, 0, newId);
                else children.push(newId);
                if (parent) parent.children = children;
                newNode['parent/id'] = node['parent/id'];
                __kotobaRefreshText(node['parent/id']);
              }
            }
            return __kotobaElement({ clientId: newId });
          },
          normalize: function() {
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'normalize'
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
            __kotobaNormalizeNode(__kotobaRefNodeId(ref));
          },
          get innerHTML() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return (node && node.children ? node.children : []).map(function(id) {
              return __kotobaSerializeNode(__kotobaNodeById(id));
            }).join('');
          },
          set innerHTML(value) {
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'set-inner-html',
              html: String(value)
            };
            if (ref.clientId) {
              request['node/client-id'] = ref.clientId;
            } else if (ref.nodeId != null) {
              request['node/id'] = ref.nodeId;
            } else if (ref.selector) {
              request['node/selector'] = ref.selector;
            }
            globalThis.__kotobaRequests.push(request);
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (node) {
              node.children = [];
              node['text-content'] = '';
            }
          },
          get outerHTML() {
            return __kotobaSerializeNode(__kotobaNodeById(__kotobaRefNodeId(ref)));
          },
          set outerHTML(value) {
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'set-outer-html',
              html: String(value)
            };
            if (ref.clientId) {
              request['node/client-id'] = ref.clientId;
            } else if (ref.nodeId != null) {
              request['node/id'] = ref.nodeId;
            } else if (ref.selector) {
              request['node/selector'] = ref.selector;
            }
            globalThis.__kotobaRequests.push(request);
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var parent = node && node['parent/id'] != null ? __kotobaNodeById(node['parent/id']) : null;
            if (parent) {
              parent.children = (parent.children || []).filter(function(id) { return id !== node['node/id']; });
              delete node['parent/id'];
              __kotobaRefreshText(parent['node/id']);
            }
          },
          matches: function(selector) {
            return __kotobaMatches(__kotobaNodeById(__kotobaRefNodeId(ref)), selector);
          },
          contains: function(other) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var otherNode = other && other.__kotobaRef ? __kotobaNodeById(__kotobaRefNodeId(other.__kotobaRef)) : null;
            return __kotobaDescendantOrSelf(node, otherNode);
          },
          closest: function(selector) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            while (node) {
              if (__kotobaMatches(node, selector)) return __kotobaElement({ nodeId: node['node/id'] });
              node = node['parent/id'] != null ? __kotobaNodeById(node['parent/id']) : null;
            }
            return null;
          },
          querySelector: function(selector) {
            var id = __kotobaScopedQuerySelectorId(__kotobaRefNodeId(ref), selector);
            return id == null ? null : __kotobaElement({ nodeId: id });
          },
          querySelectorAll: function(selector) {
            return __kotobaElements(__kotobaScopedQuerySelectorAllIds(__kotobaRefNodeId(ref), selector));
          },
          getElementsByTagName: function(tagName) {
            return __kotobaElements(__kotobaElementIdsByTagNameFromIds(
              __kotobaDescendantNodeIds(__kotobaNodeById(__kotobaRefNodeId(ref))),
              tagName));
          },
          getElementsByClassName: function(className) {
            return __kotobaElements(__kotobaElementIdsByClassNameFromIds(
              __kotobaDescendantNodeIds(__kotobaNodeById(__kotobaRefNodeId(ref))),
              className));
          },
          cloneNode: function(deep) {
            var sourceId = __kotobaRefNodeId(ref);
            var clone = __kotobaCloneSnapshotNode(__kotobaNodeById(sourceId), Boolean(deep));
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'clone-node',
              'client/id': clone.__kotobaRef.clientId,
              'deep?': Boolean(deep)
            };
            if (ref.clientId) {
              request['source/client-id'] = ref.clientId;
            } else if (ref.nodeId != null) {
              request['source/id'] = ref.nodeId;
            } else if (ref.selector) {
              request['source/id'] = sourceId;
            }
            globalThis.__kotobaRequests.push(request);
            return clone;
          },
          replaceChildren: function() {
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'remove-children'
            };
            if (ref.clientId) {
              request['node/client-id'] = ref.clientId;
            } else if (ref.nodeId != null) {
              request['node/id'] = ref.nodeId;
            } else if (ref.selector) {
              request['node/selector'] = ref.selector;
            }
            globalThis.__kotobaRequests.push(request);
            var parentId = __kotobaRefNodeId(ref);
            var parent = __kotobaNodeById(parentId);
            var removed = parent && parent.children ? parent.children.slice() : [];
            if (parent) {
              parent.children = [];
              __kotobaQueueMutation({
                type: 'childList',
                targetId: parentId,
                addedNodeIds: [],
                removedNodeIds: removed
              });
            }
            __kotobaRefreshText(parentId);
            for (var i = 0; i < arguments.length; i++) {
              this.appendChild(arguments[i]);
            }
          },
          appendChild: function(child) {
            var parentId = __kotobaRefNodeId(ref);
            var childId = child && child.__kotobaRef && __kotobaRefNodeId(child.__kotobaRef);
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'append-child',
              'child/client-id': child && child.__kotobaRef && child.__kotobaRef.clientId
            };
            if (child && child.__kotobaRef && child.__kotobaRef.nodeId != null) {
              request['child/id'] = child.__kotobaRef.nodeId;
            }
            if (ref.clientId) {
              request['parent/client-id'] = ref.clientId;
            } else if (ref.nodeId != null) {
              request['parent/id'] = ref.nodeId;
            } else if (ref.selector) {
              request['parent/selector'] = ref.selector;
            }
            globalThis.__kotobaRequests.push(request);
            var parent = __kotobaNodeById(parentId);
            var childNode = __kotobaNodeById(childId);
            var added = [];
            if (parent && childId != null) {
              if (childNode && childNode['node/type'] === 'document-fragment') {
                var fragmentChildren = (childNode.children || []).slice();
                for (var i = 0; i < fragmentChildren.length; i++) {
                  parent.children = (parent.children || []).filter(function(id) { return id !== fragmentChildren[i]; });
                  parent.children.push(fragmentChildren[i]);
                  added.push(fragmentChildren[i]);
                  var moved = __kotobaNodeById(fragmentChildren[i]);
                  if (moved && parentId != null) moved['parent/id'] = parentId;
                }
                childNode.children = [];
              } else {
                parent.children = (parent.children || []).filter(function(id) { return id !== childId; });
                parent.children.push(childId);
                added.push(childId);
              }
              __kotobaQueueMutation({
                type: 'childList',
                targetId: parentId,
                addedNodeIds: added,
                removedNodeIds: []
              });
            }
            if (childNode && childNode['node/type'] !== 'document-fragment' && parentId != null) childNode['parent/id'] = parentId;
            __kotobaRefreshText(parentId);
            return child;
          },
          append: function() {
            for (var i = 0; i < arguments.length; i++) {
              this.appendChild(__kotobaNodeArg(arguments[i]));
            }
          },
          prepend: function() {
            for (var i = 0; i < arguments.length; i++) {
              this.insertBefore(__kotobaNodeArg(arguments[i]), this.firstChild);
            }
          },
          removeChild: function(child) {
            var parentId = __kotobaRefNodeId(ref);
            var childId = child && child.__kotobaRef && __kotobaRefNodeId(child.__kotobaRef);
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'remove-child',
              'child/client-id': child && child.__kotobaRef && child.__kotobaRef.clientId
            };
            if (child && child.__kotobaRef && child.__kotobaRef.nodeId != null) {
              request['child/id'] = child.__kotobaRef.nodeId;
            }
            if (ref.clientId) {
              request['parent/client-id'] = ref.clientId;
            } else if (ref.nodeId != null) {
              request['parent/id'] = ref.nodeId;
            } else if (ref.selector) {
              request['parent/selector'] = ref.selector;
            }
            globalThis.__kotobaRequests.push(request);
            var parent = __kotobaNodeById(parentId);
            var childNode = __kotobaNodeById(childId);
            if (parent) {
              parent.children = (parent.children || []).filter(function(id) { return id !== childId; });
              __kotobaQueueMutation({
                type: 'childList',
                targetId: parentId,
                addedNodeIds: [],
                removedNodeIds: childId == null ? [] : [childId]
              });
            }
            if (childNode) delete childNode['parent/id'];
            __kotobaRefreshText(parentId);
            return child;
          },
          remove: function() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (node && node['parent/id'] != null) {
              __kotobaElement({ nodeId: node['parent/id'] }).removeChild(element);
            }
          },
          before: function() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (!node || node['parent/id'] == null) return;
            var parent = __kotobaElement({ nodeId: node['parent/id'] });
            for (var i = 0; i < arguments.length; i++) {
              parent.insertBefore(__kotobaNodeArg(arguments[i]), element);
            }
          },
          after: function() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (!node || node['parent/id'] == null) return;
            var parent = __kotobaElement({ nodeId: node['parent/id'] });
            var next = this.nextSibling;
            for (var i = 0; i < arguments.length; i++) {
              parent.insertBefore(__kotobaNodeArg(arguments[i]), next);
            }
          },
          replaceWith: function() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (!node || node['parent/id'] == null) return;
            var parent = __kotobaElement({ nodeId: node['parent/id'] });
            for (var i = 0; i < arguments.length; i++) {
              parent.insertBefore(__kotobaNodeArg(arguments[i]), element);
            }
            parent.removeChild(element);
          },
          insertBefore: function(child, before) {
            var parentId = __kotobaRefNodeId(ref);
            var childId = child && child.__kotobaRef && __kotobaRefNodeId(child.__kotobaRef);
            var beforeId = before && before.__kotobaRef && __kotobaRefNodeId(before.__kotobaRef);
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'insert-before',
              'child/client-id': child && child.__kotobaRef && child.__kotobaRef.clientId
            };
            if (child && child.__kotobaRef && child.__kotobaRef.nodeId != null) {
              request['child/id'] = child.__kotobaRef.nodeId;
            }
            if (before && before.__kotobaRef && before.__kotobaRef.clientId) {
              request['before/client-id'] = before.__kotobaRef.clientId;
            } else if (before && before.__kotobaRef && before.__kotobaRef.nodeId != null) {
              request['before/id'] = before.__kotobaRef.nodeId;
            }
            if (ref.clientId) {
              request['parent/client-id'] = ref.clientId;
            } else if (ref.nodeId != null) {
              request['parent/id'] = ref.nodeId;
            } else if (ref.selector) {
              request['parent/selector'] = ref.selector;
            }
            globalThis.__kotobaRequests.push(request);
            var parent = __kotobaNodeById(parentId);
            var childNode = __kotobaNodeById(childId);
            var inserted = [];
            if (parent && childId != null) {
              var children = parent.children || [];
              if (childNode && childNode['node/type'] === 'document-fragment') {
                var fragmentChildren = (childNode.children || []).slice();
                for (var i = 0; i < fragmentChildren.length; i++) {
                  children = children.filter(function(id) { return id !== fragmentChildren[i]; });
                  var idx = children.indexOf(beforeId);
                  if (idx < 0) children.push(fragmentChildren[i]);
                  else children.splice(idx, 0, fragmentChildren[i]);
                  inserted.push(fragmentChildren[i]);
                  var moved = __kotobaNodeById(fragmentChildren[i]);
                  if (moved && parentId != null) moved['parent/id'] = parentId;
                }
                childNode.children = [];
              } else {
                children = children.filter(function(id) { return id !== childId; });
                var idx = children.indexOf(beforeId);
                if (idx < 0) children.push(childId);
                else children.splice(idx, 0, childId);
                inserted.push(childId);
              }
              parent.children = children;
              __kotobaQueueMutation({
                type: 'childList',
                targetId: parentId,
                addedNodeIds: inserted,
                removedNodeIds: [],
                nextSiblingId: beforeId == null ? null : beforeId
              });
            }
            if (childNode && childNode['node/type'] !== 'document-fragment' && parentId != null) childNode['parent/id'] = parentId;
            __kotobaRefreshText(parentId);
            return child;
          },
          setAttribute: function(name, value) {
            __kotobaSetAttribute(ref, name, value);
          },
          getAttribute: function(name) {
            var value = __kotobaAttr(__kotobaNodeById(__kotobaRefNodeId(ref)), String(name));
            return value == null ? null : String(value);
          },
          hasAttribute: function(name) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return Boolean(node && node.attrs && Object.prototype.hasOwnProperty.call(node.attrs, String(name)));
          },
          toggleAttribute: function(name, force) {
            name = String(name);
            var present = this.hasAttribute(name);
            var shouldAdd = force === undefined ? !present : Boolean(force);
            if (shouldAdd) {
              if (!present) __kotobaSetAttribute(ref, name, '');
            } else if (present) {
              __kotobaRemoveAttribute(ref, name);
            }
            return shouldAdd;
          },
          removeAttribute: function(name) {
            __kotobaRemoveAttribute(ref, name);
          },
          setSelectionRange: function(start, end) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var valueLength = this.value.length;
            var s = Math.max(0, Math.min(valueLength, Number(start) || 0));
            var e = Math.max(0, Math.min(valueLength, Number(end) || 0));
            if (node) {
              node['selection-start'] = Math.min(s, e);
              node['selection-end'] = Math.max(s, e);
            }
          },
          addEventListener: function(type, handler) {
            var handlerId = __kotobaHandlerId();
            var eventType = String(type);
            var targetId = __kotobaRefNodeId(ref);
            var key = __kotobaNodeKey(targetId) + ':' + eventType;
            globalThis.__kotobaListeners[key] = globalThis.__kotobaListeners[key] || [];
            if (typeof handler === 'function') {
              globalThis.__kotobaListeners[key].push(handler);
              globalThis.__kotobaListenerIds[key] = globalThis.__kotobaListenerIds[key] || [];
              globalThis.__kotobaListenerIds[key].push({ handler: handler, id: handlerId });
            }
            var request = {
              capability: 'event/listen',
              'event/type': eventType,
              'handler/id': handlerId
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
            return undefined;
          },
          removeEventListener: function(type, handler) {
            var eventType = String(type);
            var targetId = __kotobaRefNodeId(ref);
            var key = __kotobaNodeKey(targetId) + ':' + eventType;
            var listeners = globalThis.__kotobaListeners[key] || [];
            var listenerIds = globalThis.__kotobaListenerIds[key] || [];
            var removedId = null;
            globalThis.__kotobaListeners[key] = listeners.filter(function(listener) {
              return listener !== handler;
            });
            globalThis.__kotobaListenerIds[key] = listenerIds.filter(function(entry) {
              if (removedId == null && entry.handler === handler) {
                removedId = entry.id;
                return false;
              }
              return true;
            });
            var request = {
              capability: 'event/remove',
              'event/type': eventType,
              'handler/id': removedId || __kotobaHandlerId()
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
          },
          dispatchEvent: function(event) {
            return __kotobaDispatch(ref, event || __kotobaEvent('event', {}));
          },
          click: function() {
            return __kotobaDispatch(ref, __kotobaEvent('click', { bubbles: true, cancelable: true }));
          },
          focus: function() {
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'focus-node'
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
            globalThis.__kotobaSnapshot.focus = __kotobaRefNodeId(ref);
          },
          blur: function() {
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'blur-node'
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
            if (globalThis.__kotobaSnapshot.focus === __kotobaRefNodeId(ref)) {
              globalThis.__kotobaSnapshot.focus = null;
            }
          },
          requestFullscreen: function(options) {
            var request = {
              capability: 'fullscreen/request'
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            if (options != null) request['fullscreen/options'] = options;
            globalThis.__kotobaRequests.push(request);
          }
        };
        if (elementCacheKey) globalThis.__kotobaElementCache[elementCacheKey] = element;
        return element;
      }
      function __kotobaSetTextContent(element, value) {
        element.replaceChildren(__kotobaTextNode(value));
      }
      globalThis.document = {
        body: __kotobaElement({ selector: 'body' }),
        get URL() {
          return globalThis.__kotobaSnapshot && globalThis.__kotobaSnapshot.url != null
            ? String(globalThis.__kotobaSnapshot.url)
            : 'about:blank';
        },
        get documentURI() {
          return this.URL;
        },
        get baseURI() {
          return globalThis.__kotobaSnapshot && globalThis.__kotobaSnapshot['base-uri'] != null
            ? String(globalThis.__kotobaSnapshot['base-uri'])
            : this.URL;
        },
        get currentScript() {
          var id = globalThis.__kotobaSnapshot ? globalThis.__kotobaSnapshot['current-script'] : null;
          return id == null ? null : __kotobaElement({ nodeId: id });
        },
        get readyState() {
          return globalThis.__kotobaSnapshot && globalThis.__kotobaSnapshot['ready-state']
            ? String(globalThis.__kotobaSnapshot['ready-state'])
            : 'complete';
        },
        get title() {
          return globalThis.__kotobaSnapshot && globalThis.__kotobaSnapshot.title != null
            ? String(globalThis.__kotobaSnapshot.title)
            : '';
        },
        set title(value) {
          var title = String(value);
          globalThis.__kotobaSnapshot.title = title;
          globalThis.__kotobaRequests.push({
            capability: 'dom/mutate',
            'dom/op': 'set-title',
            title: title
          });
        },
        get documentElement() {
          var id = __kotobaElementByTag('html');
          return id == null ? null : __kotobaElement({ nodeId: id });
        },
        get head() {
          var id = __kotobaElementByTag('head');
          return id == null ? null : __kotobaElement({ nodeId: id });
        },
        querySelector: function(selector) {
          globalThis.__kotobaRequests.push({
            capability: 'dom/query',
            'dom/query': 'query-selector',
            selector: String(selector)
          });
          var id = __kotobaQuerySelectorId(selector);
          return id == null ? null : __kotobaElement({ nodeId: id });
        },
        querySelectorAll: function(selector) {
          globalThis.__kotobaRequests.push({
            capability: 'dom/query',
            'dom/query': 'query-selector-all',
            selector: String(selector)
          });
          return __kotobaElements(__kotobaQuerySelectorAllIds(selector));
        },
        getElementsByTagName: function(tagName) {
          return __kotobaElements(__kotobaElementIdsByTagName(tagName));
        },
        getElementsByClassName: function(className) {
          return __kotobaElements(__kotobaElementIdsByClassName(className));
        },
        get forms() {
          return __kotobaElements(__kotobaElementIdsByTagName('form'));
        },
        get images() {
          return __kotobaElements(__kotobaElementIdsByTagName('img'));
        },
        get links() {
          return __kotobaElements(__kotobaDocumentLinkIds());
        },
        get scripts() {
          return __kotobaElements(__kotobaElementIdsByTagName('script'));
        },
        getElementById: function(id) {
          globalThis.__kotobaRequests.push({
            capability: 'dom/query',
            'dom/query': 'get-element-by-id',
            id: String(id)
          });
          var nodeId = __kotobaQuerySelectorId('#' + String(id));
          return nodeId == null ? null : __kotobaElement({ nodeId: nodeId });
        },
        get activeElement() {
          var id = globalThis.__kotobaSnapshot ? globalThis.__kotobaSnapshot.focus : null;
          return id == null ? null : __kotobaElement({ nodeId: id });
        },
        exitFullscreen: function() {
          globalThis.__kotobaRequests.push({
            capability: 'fullscreen/exit',
            'fullscreen/op': 'exit'
          });
        },
        createElement: function(tag) {
          var id = __kotobaClientId();
          var node = __kotobaRememberNode({
            'node/id': id,
            'node/type': 'element',
            tag: String(tag).toLowerCase(),
            attrs: {},
            children: [],
            'text-content': ''
          });
          globalThis.__kotobaClientNodes[id] = node;
          globalThis.__kotobaRequests.push({
            capability: 'dom/mutate',
            'dom/op': 'create-element',
            'client/id': id,
            tag: String(tag)
          });
          return __kotobaElement({ clientId: id });
        },
        createElementNS: function(namespaceURI, qualifiedName) {
          var id = __kotobaClientId();
          var namespace = namespaceURI == null ? '' : String(namespaceURI);
          var tag = String(qualifiedName).toLowerCase();
          var node = __kotobaRememberNode({
            'node/id': id,
            'node/type': 'element',
            tag: tag,
            attrs: {'namespace-uri': namespace},
            children: [],
            'text-content': ''
          });
          globalThis.__kotobaClientNodes[id] = node;
          globalThis.__kotobaRequests.push({
            capability: 'dom/mutate',
            'dom/op': 'create-element',
            'client/id': id,
            tag: String(qualifiedName),
            attrs: {'namespace-uri': namespace}
          });
          return __kotobaElement({ clientId: id });
        },
        createDocumentFragment: function() {
          var id = __kotobaClientId();
          var node = __kotobaRememberNode({
            'node/id': id,
            'node/type': 'document-fragment',
            children: [],
            'text-content': ''
          });
          globalThis.__kotobaClientNodes[id] = node;
          globalThis.__kotobaRequests.push({
            capability: 'dom/mutate',
            'dom/op': 'create-fragment',
            'client/id': id
          });
          return __kotobaElement({ clientId: id });
        },
        createTextNode: function(text) {
          var id = __kotobaClientId();
          var node = __kotobaRememberNode({
            'node/id': id,
            'node/type': 'text',
            text: String(text)
          });
          globalThis.__kotobaClientNodes[id] = node;
          globalThis.__kotobaRequests.push({
            capability: 'dom/mutate',
            'dom/op': 'create-text',
            'client/id': id,
            text: String(text)
          });
          return __kotobaElement({ clientId: id });
        }
      };
      Object.defineProperty(globalThis.document, 'cookie', {
        get: function() {
          globalThis.__kotobaRequests.push({
            capability: 'cookie/get',
            'cookie/op': 'get'
          });
          return '';
        },
        set: function(value) {
          globalThis.__kotobaRequests.push({
            capability: 'cookie/set',
            'cookie/op': 'set',
            'cookie/value': String(value)
          });
        }
      });
      globalThis.Event = function(type, init) {
        return __kotobaEvent(type, init || {});
      };
      globalThis.CustomEvent = function(type, init) {
        init = init || {};
        var event = __kotobaEvent(type, init);
        event.detail = Object.prototype.hasOwnProperty.call(init, 'detail') ? init.detail : null;
        return event;
      };
      globalThis.MouseEvent = function(type, init) {
        init = init || {};
        var event = __kotobaEvent(type, init);
        event.button = init.button == null ? 0 : Number(init.button);
        event.clientX = init.clientX == null ? 0 : Number(init.clientX);
        event.clientY = init.clientY == null ? 0 : Number(init.clientY);
        return event;
      };
      globalThis.KeyboardEvent = function(type, init) {
        init = init || {};
        var event = __kotobaEvent(type, init);
        event.key = init.key == null ? '' : String(init.key);
        event.code = init.code == null ? '' : String(init.code);
        event.repeat = Boolean(init.repeat);
        return event;
      };
      globalThis.MutationObserver = function(callback) {
        if (typeof callback !== 'function') throw new TypeError('MutationObserver callback must be a function');
        var observer = {
          id: globalThis.__kotobaNextMutationObserverId++,
          callback: callback,
          targets: [],
          records: [],
          scheduled: false,
          instance: null
        };
        var instance = {
          observe: function(target, options) {
            if (!target || !target.__kotobaRef) throw new TypeError('MutationObserver target must be a Node');
            options = options || {};
            observer.targets.push({
              nodeId: __kotobaRefNodeId(target.__kotobaRef),
              options: {
                attributes: Boolean(options.attributes),
                childList: Boolean(options.childList),
                characterData: Boolean(options.characterData),
                subtree: Boolean(options.subtree),
                attributeOldValue: Boolean(options.attributeOldValue),
                characterDataOldValue: Boolean(options.characterDataOldValue)
              }
            });
            if (globalThis.__kotobaMutationObservers.indexOf(observer) < 0) {
              globalThis.__kotobaMutationObservers.push(observer);
            }
          },
          disconnect: function() {
            observer.targets = [];
            observer.records = [];
            globalThis.__kotobaMutationObservers = globalThis.__kotobaMutationObservers.filter(function(item) {
              return item !== observer;
            });
          },
          takeRecords: function() {
            var records = observer.records.slice();
            observer.records = [];
            return records;
          }
        };
        observer.instance = instance;
        return instance;
      };
      function __kotobaGlobalEventKey(target, type) {
        return String(target) + ':' + String(type);
      }
      function __kotobaListenGlobalEvent(target, type, handler) {
        var handlerId = __kotobaHandlerId();
        var eventType = String(type);
        var key = __kotobaGlobalEventKey(target, eventType);
        globalThis.__kotobaListeners[key] = globalThis.__kotobaListeners[key] || [];
        if (typeof handler === 'function') globalThis.__kotobaListeners[key].push(handler);
        globalThis.__kotobaRequests.push({
          capability: 'event/listen',
          'event/target': String(target),
          'event/type': eventType,
          'handler/id': handlerId
        });
      }
      function __kotobaRemoveGlobalEvent(target, type, handler) {
        var eventType = String(type);
        var key = __kotobaGlobalEventKey(target, eventType);
        var listeners = globalThis.__kotobaListeners[key] || [];
        globalThis.__kotobaListeners[key] = listeners.filter(function(listener) {
          return listener !== handler;
        });
        globalThis.__kotobaRequests.push({
          capability: 'event/remove',
          'event/target': String(target),
          'event/type': eventType,
          'handler/id': 'global-' + String(target) + '-' + eventType
        });
      }
      function __kotobaDispatchGlobalEvent(target, event) {
        event = event || __kotobaEvent('event', {});
        var eventType = String(event.type || 'event');
        event.target = target === 'window' ? globalThis : globalThis.document;
        event.currentTarget = event.target;
        var key = __kotobaGlobalEventKey(target, eventType);
        var listeners = globalThis.__kotobaListeners[key] || [];
        for (var i = 0; i < listeners.length; i++) listeners[i].call(event.currentTarget, event);
        globalThis.__kotobaRequests.push({
          capability: 'event/dispatch',
          'event/target': String(target),
          event: {
            'event/type': eventType,
            'default-prevented?': !!event.defaultPrevented
          }
        });
        return !event.defaultPrevented;
      }
      globalThis.document.addEventListener = function(type, handler) {
        return __kotobaListenGlobalEvent('document', type, handler);
      };
      globalThis.document.removeEventListener = function(type, handler) {
        return __kotobaRemoveGlobalEvent('document', type, handler);
      };
      globalThis.document.dispatchEvent = function(event) {
        return __kotobaDispatchGlobalEvent('document', event);
      };
      globalThis.addEventListener = function(type, handler) {
        return __kotobaListenGlobalEvent('window', type, handler);
      };
      globalThis.removeEventListener = function(type, handler) {
        return __kotobaRemoveGlobalEvent('window', type, handler);
      };
      globalThis.dispatchEvent = function(event) {
        return __kotobaDispatchGlobalEvent('window', event);
      };
      function __kotobaConsoleArgs(args) {
        return Array.prototype.slice.call(args).map(function(value) {
          if (value == null) return value;
          if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value;
          try { return JSON.stringify(value); } catch (_) { return String(value); }
        });
      }
      function __kotobaConsoleLog(level, args) {
        globalThis.__kotobaRequests.push({
          capability: 'console/log',
          'console/level': level,
          args: __kotobaConsoleArgs(args)
        });
      }
      globalThis.console = globalThis.console || {};
      globalThis.console.log = function() { __kotobaConsoleLog('log', arguments); };
      globalThis.console.info = function() { __kotobaConsoleLog('info', arguments); };
      globalThis.console.warn = function() { __kotobaConsoleLog('warn', arguments); };
      globalThis.console.error = function() { __kotobaConsoleLog('error', arguments); };
      globalThis.console.debug = function() { __kotobaConsoleLog('debug', arguments); };
      globalThis.fetch = function(url, request) {
        globalThis.__kotobaRequests.push({
          capability: 'net/fetch',
          url: String(url),
          request: request || {}
        });
        return { ok: true, status: 0, capability: 'net/fetch' };
      };
      globalThis.open = function(url, target, features) {
        var request = {
          capability: 'window/open',
          url: String(url),
          target: target == null ? '_blank' : String(target)
        };
        if (features != null) request['window/features'] = String(features);
        globalThis.__kotobaRequests.push(request);
        return null;
      };
      globalThis.location = globalThis.location || {
        href: 'about:blank',
        assign: function(url) {
          this.href = String(url);
          globalThis.__kotobaRequests.push({
            capability: 'location/assign',
            url: this.href
          });
        },
        replace: function(url) {
          this.href = String(url);
          globalThis.__kotobaRequests.push({
            capability: 'location/replace',
            url: this.href
          });
        },
        reload: function() {
          globalThis.__kotobaRequests.push({
            capability: 'location/reload',
            'location/op': 'reload'
          });
        },
        toString: function() {
          return this.href;
        }
      };
      function __kotobaDecodeQueryValue(value) {
        try {
          return decodeURIComponent(String(value).replace(/\\+/g, ' '));
        } catch (_) {
          return String(value);
        }
      }
      function __kotobaEncodeQueryValue(value) {
        return encodeURIComponent(String(value));
      }
      globalThis.URLSearchParams = function(init) {
        this.__pairs = [];
        if (init == null) return;
        if (typeof init === 'string') {
          var source = init.charAt(0) === '?' ? init.slice(1) : init;
          if (source.length === 0) return;
          var parts = source.split('&');
          for (var i = 0; i < parts.length; i++) {
            if (parts[i] === '') continue;
            var eq = parts[i].indexOf('=');
            var key = eq < 0 ? parts[i] : parts[i].slice(0, eq);
            var value = eq < 0 ? '' : parts[i].slice(eq + 1);
            this.append(__kotobaDecodeQueryValue(key), __kotobaDecodeQueryValue(value));
          }
        } else if (Array.isArray(init)) {
          for (var j = 0; j < init.length; j++) this.append(init[j][0], init[j][1]);
        } else if (init && typeof init === 'object') {
          var keys = Object.keys(init);
          for (var k = 0; k < keys.length; k++) this.append(keys[k], init[keys[k]]);
        }
      };
      globalThis.URLSearchParams.prototype.append = function(name, value) {
        this.__pairs.push([String(name), String(value)]);
      };
      globalThis.URLSearchParams.prototype.set = function(name, value) {
        this.delete(name);
        this.append(name, value);
      };
      globalThis.URLSearchParams.prototype.get = function(name) {
        name = String(name);
        for (var i = 0; i < this.__pairs.length; i++) {
          if (this.__pairs[i][0] === name) return this.__pairs[i][1];
        }
        return null;
      };
      globalThis.URLSearchParams.prototype.getAll = function(name) {
        name = String(name);
        var values = [];
        for (var i = 0; i < this.__pairs.length; i++) {
          if (this.__pairs[i][0] === name) values.push(this.__pairs[i][1]);
        }
        return values;
      };
      globalThis.URLSearchParams.prototype.has = function(name) {
        return this.get(name) !== null;
      };
      globalThis.URLSearchParams.prototype.delete = function(name) {
        name = String(name);
        this.__pairs = this.__pairs.filter(function(pair) { return pair[0] !== name; });
      };
      globalThis.URLSearchParams.prototype.sort = function() {
        this.__pairs.sort(function(a, b) { return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0); });
      };
      globalThis.URLSearchParams.prototype.forEach = function(callback, thisArg) {
        for (var i = 0; i < this.__pairs.length; i++) {
          callback.call(thisArg, this.__pairs[i][1], this.__pairs[i][0], this);
        }
      };
      globalThis.URLSearchParams.prototype.toString = function() {
        return this.__pairs.map(function(pair) {
          return __kotobaEncodeQueryValue(pair[0]) + '=' + __kotobaEncodeQueryValue(pair[1]);
        }).join('&');
      };
      function __kotobaNormalizePath(path) {
        var absolute = String(path).charAt(0) === '/';
        var parts = String(path).split('/');
        var result = [];
        for (var i = 0; i < parts.length; i++) {
          if (parts[i] === '' || parts[i] === '.') continue;
          if (parts[i] === '..') result.pop();
          else result.push(parts[i]);
        }
        return (absolute ? '/' : '') + result.join('/');
      }
      function __kotobaSplitUrl(value) {
        var match = String(value).match(/^([A-Za-z][A-Za-z0-9+.-]*:)?(?:\\/\\/([^\\/?#]*))?([^?#]*)(\\?[^#]*)?(#.*)?$/);
        return {
          protocol: match && match[1] ? match[1] : '',
          authority: match && match[2] ? match[2] : '',
          pathname: match && match[3] ? match[3] : '',
          search: match && match[4] ? match[4] : '',
          hash: match && match[5] ? match[5] : ''
        };
      }
      function __kotobaResolveUrl(input, base) {
        input = String(input);
        if (/^[A-Za-z][A-Za-z0-9+.-]*:/.test(input)) return input;
        var baseParts = __kotobaSplitUrl(base || globalThis.location.href || 'about:blank');
        var baseOrigin = baseParts.protocol + '//' + baseParts.authority;
        if (input.slice(0, 2) === '//') return baseParts.protocol + input;
        if (input.charAt(0) === '/') return baseOrigin + input;
        if (input.charAt(0) === '?') return baseOrigin + (baseParts.pathname || '/') + input;
        if (input.charAt(0) === '#') return baseOrigin + (baseParts.pathname || '/') + baseParts.search + input;
        var basePath = baseParts.pathname || '/';
        var dir = basePath.slice(0, basePath.lastIndexOf('/') + 1);
        return baseOrigin + __kotobaNormalizePath(dir + input);
      }
      globalThis.URL = function(input, base) {
        var href = __kotobaResolveUrl(input, base);
        var parts = __kotobaSplitUrl(href);
        this.href = href;
        this.protocol = parts.protocol;
        this.host = parts.authority;
        var hostParts = parts.authority.split(':');
        this.hostname = hostParts[0] || '';
        this.port = hostParts.length > 1 ? hostParts.slice(1).join(':') : '';
        this.pathname = parts.pathname || '/';
        this.search = parts.search;
        this.hash = parts.hash;
        this.origin = parts.authority ? parts.protocol + '//' + parts.authority : 'null';
        this.searchParams = new globalThis.URLSearchParams(this.search);
      };
      globalThis.URL.prototype.toString = function() {
        return this.href;
      };
      globalThis.localStorage = {
        getItem: function(key) {
          var k = String(key);
          globalThis.__kotobaRequests.push({
            capability: 'storage/get',
            'storage/key': k
          });
          var snapshot = globalThis.__kotobaStorageSnapshot || {};
          return Object.prototype.hasOwnProperty.call(snapshot, k) ? String(snapshot[k]) : null;
        },
        setItem: function(key, value) {
          globalThis.__kotobaRequests.push({
            capability: 'storage/put',
            'storage/key': String(key),
            'storage/value': String(value)
          });
        },
        removeItem: function(key) {
          globalThis.__kotobaRequests.push({
            capability: 'storage/delete',
            'storage/key': String(key)
          });
        }
      };
      globalThis.navigator = globalThis.navigator || {};
      globalThis.navigator.permissions = {
        query: function(descriptor) {
          var name = descriptor && descriptor.name != null ? String(descriptor.name) : '';
          globalThis.__kotobaRequests.push({
            capability: 'permissions/query',
            'permission/name': name
          });
          return { state: 'prompt', name: name };
        }
      };
      globalThis.navigator.sendBeacon = function(url, data) {
        var request = {
          capability: 'beacon/send',
          url: String(url)
        };
        if (data != null) request.data = data;
        globalThis.__kotobaRequests.push(request);
        return true;
      };
      globalThis.history = globalThis.history || {
        state: null,
        length: 0,
        pushState: function(state, title, url) {
          this.state = state == null ? null : state;
          this.length = this.length + 1;
          globalThis.__kotobaRequests.push({
            capability: 'history/push-state',
            state: this.state,
            title: title == null ? '' : String(title),
            url: url == null ? '' : String(url)
          });
        },
        replaceState: function(state, title, url) {
          this.state = state == null ? null : state;
          if (this.length === 0) this.length = 1;
          globalThis.__kotobaRequests.push({
            capability: 'history/replace-state',
            state: this.state,
            title: title == null ? '' : String(title),
            url: url == null ? '' : String(url)
          });
        },
        go: function(delta) {
          globalThis.__kotobaRequests.push({
            capability: 'history/traverse',
            delta: Number(delta) || 0
          });
        },
        back: function() {
          this.go(-1);
        },
        forward: function() {
          this.go(1);
        }
      };
      globalThis.navigator.clipboard = {
        readText: function() {
          globalThis.__kotobaRequests.push({
            capability: 'clipboard/read',
            'clipboard/format': 'text'
          });
          var snapshot = globalThis.__kotobaClipboardSnapshot || {};
          return snapshot.text != null ? String(snapshot.text) : '';
        },
        writeText: function(text) {
          globalThis.__kotobaRequests.push({
            capability: 'clipboard/write',
            'clipboard/format': 'text',
            text: String(text)
          });
        }
      };
      globalThis.navigator.geolocation = {
        getCurrentPosition: function(success, error, options) {
          var request = {
            capability: 'geolocation/read',
            'geolocation/op': 'current-position'
          };
          if (options != null) request['geolocation/options'] = options;
          globalThis.__kotobaRequests.push(request);
          var snapshot = globalThis.__kotobaGeolocationSnapshot ||
            { granted: false, error: { code: 2, message: 'Position unavailable' } };
          if (snapshot.granted && snapshot.position) {
            if (typeof success === 'function') success(snapshot.position);
          } else {
            var err = snapshot.error || { code: 2, message: 'Position unavailable' };
            if (typeof error === 'function') error(err);
          }
        }
      };
      globalThis.navigator.mediaDevices = {
        getUserMedia: function(constraints) {
          globalThis.__kotobaRequests.push({
            capability: 'media/capture',
            'media/op': 'get-user-media',
            'media/constraints': constraints || {}
          });
          return { capability: 'media/capture' };
        }
      };
      globalThis.Notification = function(title, options) {
        var request = {
          capability: 'notification/show',
          title: String(title)
        };
        if (options != null) request['notification/options'] = options;
        globalThis.__kotobaRequests.push(request);
        this.title = String(title);
      };
      globalThis.Notification.permission = 'default';
      globalThis.Notification.requestPermission = function(callback) {
        globalThis.__kotobaRequests.push({
          capability: 'notification/request-permission',
          'notification/op': 'request-permission'
        });
        if (typeof callback === 'function') callback('default');
        return 'default';
      };
      globalThis.WebSocket = function(url, protocols) {
        var socketId = 'websocket-' + globalThis.__kotobaNextWebSocketId;
        globalThis.__kotobaNextWebSocketId = globalThis.__kotobaNextWebSocketId + 1;
        this.__kotobaWebSocketId = socketId;
        this.url = String(url);
        this.readyState = 1;
        this.onopen = null;
        this.onmessage = null;
        this.onclose = null;
        this.onerror = null;
        globalThis.__kotobaWebSockets[socketId] = this;
        var request = {
          capability: 'websocket/connect',
          'websocket/id': socketId,
          url: String(url)
        };
        if (protocols != null) request['websocket/protocols'] = protocols;
        globalThis.__kotobaRequests.push(request);
      };
      globalThis.WebSocket.CONNECTING = 0;
      globalThis.WebSocket.OPEN = 1;
      globalThis.WebSocket.CLOSING = 2;
      globalThis.WebSocket.CLOSED = 3;
      globalThis.WebSocket.prototype.send = function(data) {
        globalThis.__kotobaRequests.push({
          capability: 'websocket/send',
          'websocket/id': this.__kotobaWebSocketId,
          data: String(data)
        });
      };
      globalThis.WebSocket.prototype.close = function(code, reason) {
        var request = {
          capability: 'websocket/close',
          'websocket/id': this.__kotobaWebSocketId
        };
        if (code != null) request.code = Number(code);
        if (reason != null) request.reason = String(reason);
        globalThis.__kotobaRequests.push(request);
        this.readyState = globalThis.WebSocket.CLOSED;
      };
      globalThis.crypto = {
        getRandomValues: function(array) {
          var length = array && array.length != null ? Number(array.length) : 0;
          globalThis.__kotobaRequests.push({
            capability: 'crypto/random-values',
            'crypto/op': 'random-values',
            length: length
          });
          for (var i = 0; i < length; i++) array[i] = 0;
          return array;
        },
        randomUUID: function() {
          globalThis.__kotobaRequests.push({
            capability: 'crypto/random-uuid',
            'crypto/op': 'random-uuid'
          });
          return '00000000-0000-4000-8000-000000000000';
        }
      };
      globalThis.Worker = function(url, options) {
        var workerId = 'worker-' + globalThis.__kotobaNextWorkerId;
        globalThis.__kotobaNextWorkerId = globalThis.__kotobaNextWorkerId + 1;
        this.__kotobaWorkerId = workerId;
        var request = {
          capability: 'worker/create',
          'worker/id': workerId,
          url: String(url)
        };
        if (options != null) request['worker/options'] = options;
        globalThis.__kotobaRequests.push(request);
      };
      globalThis.Worker.prototype.postMessage = function(message) {
        globalThis.__kotobaRequests.push({
          capability: 'worker/post-message',
          'worker/id': this.__kotobaWorkerId,
          message: message
        });
      };
      globalThis.Worker.prototype.terminate = function() {
        globalThis.__kotobaRequests.push({
          capability: 'worker/terminate',
          'worker/id': this.__kotobaWorkerId
        });
      };
      globalThis.BroadcastChannel = function(name) {
        var channelId = 'broadcast-' + globalThis.__kotobaNextBroadcastId;
        globalThis.__kotobaNextBroadcastId = globalThis.__kotobaNextBroadcastId + 1;
        this.__kotobaBroadcastId = channelId;
        this.name = String(name);
        globalThis.__kotobaRequests.push({
          capability: 'broadcast/open',
          'broadcast/id': channelId,
          'broadcast/name': String(name)
        });
      };
      globalThis.BroadcastChannel.prototype.postMessage = function(message) {
        globalThis.__kotobaRequests.push({
          capability: 'broadcast/post-message',
          'broadcast/id': this.__kotobaBroadcastId,
          message: message
        });
      };
      globalThis.BroadcastChannel.prototype.close = function() {
        globalThis.__kotobaRequests.push({
          capability: 'broadcast/close',
          'broadcast/id': this.__kotobaBroadcastId
        });
      };
      globalThis.setTimeout = function(callback, delay) {
        var timerId = globalThis.__kotobaNextTimerId;
        var callbackId = 'timer-' + timerId;
        var args = Array.prototype.slice.call(arguments, 2);
        globalThis.__kotobaNextTimerId = timerId + 1;
        globalThis.__kotobaTimers[timerId] = {
          'callback/id': callbackId,
          callback: callback,
          args: args
        };
        globalThis.__kotobaRequests.push({
          capability: 'timer/schedule',
          'timer/kind': 'timeout',
          'timer/id': timerId,
          'callback/id': callbackId,
          'delay-ms': Number(delay) || 0,
          payload: { args: args }
        });
        return timerId;
      };
      globalThis.clearTimeout = function(timerId) {
        var timer = globalThis.__kotobaTimers[timerId];
        var callbackId = timer ? timer['callback/id'] : 'timer-' + timerId;
        delete globalThis.__kotobaTimers[timerId];
        globalThis.__kotobaRequests.push({
          capability: 'timer/cancel',
          'timer/kind': 'timeout',
          'timer/id': Number(timerId),
          'callback/id': callbackId
        });
      };
      globalThis.requestAnimationFrame = function(callback) {
        var frameId = globalThis.__kotobaNextTimerId;
        var callbackId = 'animation-frame-' + frameId;
        globalThis.__kotobaNextTimerId = frameId + 1;
        globalThis.__kotobaTimers[frameId] = {
          'callback/id': callbackId,
          callback: callback,
          args: [0]
        };
        globalThis.__kotobaRequests.push({
          capability: 'timer/schedule',
          'timer/kind': 'animation-frame',
          'timer/id': frameId,
          'callback/id': callbackId,
          'delay-ms': 0,
          payload: { args: [0] }
        });
        return frameId;
      };
      globalThis.cancelAnimationFrame = function(frameId) {
        var frame = globalThis.__kotobaTimers[frameId];
        var callbackId = frame ? frame['callback/id'] : 'animation-frame-' + frameId;
        delete globalThis.__kotobaTimers[frameId];
        globalThis.__kotobaRequests.push({
          capability: 'timer/cancel',
          'timer/kind': 'animation-frame',
          'timer/id': Number(frameId),
          'callback/id': callbackId
        });
      };
      globalThis.queueMicrotask = function(callback) {
        __kotobaScheduleMicrotask(callback, null);
      };
      (function() {
        var __kotobaWsSnapshot = globalThis.__kotobaWebsocketSnapshot || {};
        var __kotobaWsRegistry = globalThis.__kotobaWebSockets || {};
        var __kotobaWsIds = Object.keys(__kotobaWsSnapshot);
        for (var __kwi = 0; __kwi < __kotobaWsIds.length; __kwi++) {
          var __kwId = __kotobaWsIds[__kwi];
          var __kwEntry = __kotobaWsSnapshot[__kwId];
          var __kwSocket = __kotobaWsRegistry[__kwId];
          if (!__kwSocket || !__kwEntry) continue;
          var __kwMessages = __kwEntry.messages || [];
          for (var __kwj = 0; __kwj < __kwMessages.length; __kwj++) {
            if (typeof __kwSocket.onmessage === 'function') {
              __kwSocket.onmessage({ data: __kwMessages[__kwj] });
            }
          }
          if (__kwEntry.closed) {
            __kwSocket.readyState = globalThis.WebSocket.CLOSED;
            if (typeof __kwSocket.onclose === 'function') {
              __kwSocket.onclose({
                code: __kwEntry['close-code'] || 1000,
                reason: __kwEntry['close-reason'] || ''
              });
            }
          }
        }
      })();")

#?(:cljs
   (defn- request-keyword [k]
     (case k
       "capability" :capability
       "dom/query" :dom/query
       "dom/op" :dom/op
       "storage/key" :storage/key
       "storage/value" :storage/value
       (keyword k))))

#?(:cljs
(defn- normalize-request [request]
     (let [request (js->clj request)]
       (into {}
             (map (fn [[k v]]
                    (let [k (request-keyword k)]
                      [k (cond
                           (contains? #{:capability :dom/query :dom/op} k)
                           (keyword v)

                           (and (= :request k) (map? v))
                           (into {}
                                 (map (fn [[request-k request-v]]
                                        [(keyword request-k) request-v]))
                                 v)

                           (and (= :event k) (map? v))
                           (into {}
                                 (map (fn [[event-k event-v]]
                                        [(keyword event-k) event-v]))
                                 v)

                           :else v)])))
             request))))

#?(:cljs
   (defn- dump-requests [vm]
     (let [^js result (.evalCode ^js vm
                                  "(function(){ const requests = globalThis.__kotobaRequests || []; globalThis.__kotobaRequests = []; return requests; })()"
                                  "kotoba://quickjs/requests.js")]
       (try
         (if (.-error result)
           []
           (mapv normalize-request
                 (.dump ^js vm (.-value result))))
         (finally
           (when (.-error result)
             (.dispose ^js (.-error result)))
           (when (.-value result)
             (.dispose ^js (.-value result))))))))

#?(:cljs
   (defn- eval-result
     ([vm source url]
      (eval-result vm source url false))
     ([vm source url module?]
     (let [^js result (if module?
                        (.evalCode ^js vm source (or url "eval.js") #js {:type "module"})
                        (.evalCode ^js vm source (or url "eval.js")))]
       (try
         (if (.-error result)
           {:error :quickjs/eval-error
            :result (.dump ^js vm (.-error result))}
           {:result (.dump ^js vm (.-value result))})
         (finally
           (when (.-error result)
             (.dispose ^js (.-error result)))
           (when (.-value result)
             (.dispose ^js (.-value result)))))))))

#?(:cljs
   (defn- module-source [modules module-name]
     (resolve-module-source modules nil module-name)))

#?(:cljs
   (defn- module-loader [modules module-provider]
     (fn [module-name]
       (if-let [source (resolve-module-source modules module-provider module-name)]
         source
         (throw (js/Error. (str "QuickJS module not found: " module-name)))))))

#?(:cljs
   (defn- eval-dispose! [vm source url]
     (let [^js result (.evalCode ^js vm source url)]
       (when (.-error result)
         (.dispose ^js (.-error result)))
       (when (.-value result)
         (.dispose ^js (.-value result))))))

#?(:cljs
   (defn- json-key [k]
     (cond
       (keyword? k) (if (namespace k)
                      (str (namespace k) "/" (name k))
                      (name k))
       :else (str k))))

#?(:cljs
   (defn- jsonable [value]
     (cond
       (map? value) (into {}
                          (map (fn [[k v]]
                                 [(json-key k) (jsonable v)]))
                          value)
       (sequential? value) (mapv jsonable value)
       :else value)))

#?(:cljs
   (defn- snapshot-source [snapshot]
     (str "globalThis.__kotobaSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or snapshot {:root nil :nodes {}}))))
          ";")))

#?(:cljs
   (defn- storage-snapshot-source
     "JS source that installs the real, current `:storage` key/value map (from
     the persisted `quickjs-execution` runtime state -- see
     `browser.compat.quickjs-runner`'s `persistent-execution-keys`) as
     `globalThis.__kotobaStorageSnapshot`, so the webapi shim's
     `localStorage.getItem` can read it synchronously instead of always
     returning `null`. Mirrors `snapshot-source`'s treatment of the document
     snapshot."
     [storage]
     (str "globalThis.__kotobaStorageSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or storage {}))))
          ";")))

#?(:cljs
   (defn- clipboard-snapshot-source
     "JS source that installs the real, current `:clipboard` snapshot (from
     the persisted `quickjs-execution` runtime state -- see
     `browser.compat.quickjs-runner`'s `persistent-execution-keys`) as
     `globalThis.__kotobaClipboardSnapshot`, so the webapi shim's
     `navigator.clipboard.readText` can read it synchronously instead of
     always returning `''`. Mirrors `snapshot-source`/`storage-snapshot-source`."
     [clipboard]
     (str "globalThis.__kotobaClipboardSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or clipboard {:text ""}))))
          ";")))

#?(:cljs
   (defn- geolocation-snapshot-source
     "JS source that installs the real, current geolocation permission
     decision + position (`quickjs-execution/geolocation-snapshot`, computed
     host-side from the persisted `:geolocation` atom -- see
     `browser.compat.quickjs-runner`'s `persistent-execution-keys` -- and the
     SAME `permission-decision-for` gate `apply-capability`'s
     `:geolocation/read` case uses) as `globalThis.__kotobaGeolocationSnapshot`,
     so the webapi shim's `navigator.geolocation.getCurrentPosition` can
     synchronously call `success`/`error` with the REAL injected position
     instead of never calling either. Mirrors `snapshot-source`/
     `storage-snapshot-source`/`clipboard-snapshot-source`, but the shape is
     necessarily a bit richer than storage's/clipboard's raw value: geolocation
     is permission-gated, so this is ONE coherent map carrying both the
     permission decision and the position/error together, not a bolted-on
     second snapshot."
     [geolocation]
     (str "globalThis.__kotobaGeolocationSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or geolocation
                                                     {:granted false
                                                      :error {:code 2
                                                              :message "Position unavailable"}}))))
          ";")))

#?(:cljs
   (defn- websocket-snapshot-source
     "JS source that installs the real, current per-WebSocket-connection
     inbound-message snapshot (`quickjs-execution/websocket-snapshot`,
     computed host-side by draining each open connection's REAL socket --
     see `browser.compat.quickjs-runner`'s `persistent-execution-keys`) as
     `globalThis.__kotobaWebsocketSnapshot`, keyed by WebSocket id. Unlike
     `snapshot-source`/`storage-snapshot-source`/`clipboard-snapshot-source`/
     `geolocation-snapshot-source`, installing this snapshot is not enough
     on its own: the webapi shim below also has to actively DELIVER it (see
     the bottom of `webapi-shim-source`) -- for each id present here with a
     still-registered live `WebSocket` instance
     (`globalThis.__kotobaWebSockets`, itself persisted across script tags
     within a page the same way `globalThis.__kotobaSnapshot` already is),
     it invokes that instance's `onmessage` SYNCHRONOUSLY once per buffered
     message, in arrival order, before the script's own source runs --
     this is what makes a message a PREVIOUS `<script>` tag's `ws.onmessage`
     registered genuinely observable by a LATER one."
     [websocket-snapshot]
     (str "globalThis.__kotobaWebsocketSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or websocket-snapshot {}))))
          ";")))

#?(:cljs
   (defn- dump-result
     ([module source url modules]
      (dump-result module source url modules nil false nil nil nil nil nil))
     ([module source url modules module-provider module?]
      (dump-result module source url modules module-provider module? nil nil nil nil nil))
     ([module source url modules module-provider module? document-snapshot storage-snapshot clipboard-snapshot geolocation-snapshot websocket-snapshot]
      (let [^js runtime (.newRuntime ^js module #js {:moduleLoader (module-loader modules module-provider)})
            ^js vm (.newContext runtime)
            _ (eval-dispose! vm (snapshot-source document-snapshot) "kotoba://quickjs/document-snapshot.js")
            _ (eval-dispose! vm (storage-snapshot-source storage-snapshot) "kotoba://quickjs/storage-snapshot.js")
            _ (eval-dispose! vm (clipboard-snapshot-source clipboard-snapshot) "kotoba://quickjs/clipboard-snapshot.js")
            _ (eval-dispose! vm (geolocation-snapshot-source geolocation-snapshot) "kotoba://quickjs/geolocation-snapshot.js")
            _ (eval-dispose! vm (websocket-snapshot-source websocket-snapshot) "kotoba://quickjs/websocket-snapshot.js")
            _ (eval-dispose! vm webapi-shim-source "kotoba://quickjs/webapi-shim.js")
            response (eval-result vm source url module?)
            requests (dump-requests vm)]
        (try
          (assoc response :requests requests)
          (finally
            (.dispose vm)
            (.dispose runtime)))))))

#?(:cljs
   (defn- create-context [module modules module-provider]
     (let [^js runtime (.newRuntime ^js module #js {:moduleLoader (module-loader modules module-provider)})
           ^js vm (.newContext runtime)]
       {:runtime runtime
        :vm vm
        :modules modules
        :module-provider module-provider})))

#?(:cljs
   (defn- ensure-context! [context-atom module modules module-provider]
     (or @context-atom
         (let [context (create-context module modules module-provider)]
           (reset! context-atom context)
           context))))

#?(:cljs
   (defn- install-document-shim! [vm document-snapshot storage-snapshot clipboard-snapshot geolocation-snapshot websocket-snapshot]
     (eval-dispose! vm (snapshot-source document-snapshot) "kotoba://quickjs/document-snapshot.js")
     (eval-dispose! vm (storage-snapshot-source storage-snapshot) "kotoba://quickjs/storage-snapshot.js")
     (eval-dispose! vm (clipboard-snapshot-source clipboard-snapshot) "kotoba://quickjs/clipboard-snapshot.js")
     (eval-dispose! vm (geolocation-snapshot-source geolocation-snapshot) "kotoba://quickjs/geolocation-snapshot.js")
     (eval-dispose! vm (websocket-snapshot-source websocket-snapshot) "kotoba://quickjs/websocket-snapshot.js")
     (eval-dispose! vm webapi-shim-source "kotoba://quickjs/webapi-shim.js")))

#?(:cljs
   (defn- context-eval-result [context source url module? document-snapshot storage-snapshot clipboard-snapshot geolocation-snapshot websocket-snapshot]
     (let [vm (:vm context)
           _ (install-document-shim! vm document-snapshot storage-snapshot clipboard-snapshot geolocation-snapshot websocket-snapshot)
           response (eval-result vm source url module?)
           requests (dump-requests vm)]
       (assoc response :requests requests))))

#?(:cljs
   (defn- context-run-task-result [context task]
     (let [vm (:vm context)
           callback-id (:callback/id task)
           source (str "__kotobaRunTask("
                       (js/JSON.stringify (str callback-id))
                       ");")
           response (eval-result vm source "kotoba://quickjs/job.js")
           requests (dump-requests vm)]
       (assoc response :requests requests))))

#?(:cljs
   (defn- dispose-context! [context]
     (when-let [^js vm (:vm context)]
       (.dispose vm))
     (when-let [^js runtime (:runtime context)]
       (.dispose runtime))
     {:quickjs.wasm/context :disposed}))

#?(:cljs
   (defn- dispose-context-atom! [context-atom]
     (if-let [context @context-atom]
       (do
         (reset! context-atom nil)
         (dispose-context! context))
       {:quickjs.wasm/context :not-created})))

#?(:cljs
   (defn module!
     []
     (newQuickJSWASMModuleFromVariant (variant))))

#?(:cljs
   (defn- aborted?
     [signal]
     (boolean (and signal (.-aborted signal)))))

#?(:cljs
   (defn- abort-rejection
     []
     (js/Promise.reject (js/Error. "QuickJS WASM initialization aborted"))))

#?(:cljs
   (defn engine!
     "Create a quickjs-execution compatible engine descriptor.

     Returns a Promise because the WASM module is initialized asynchronously.
     Once resolved, `execution/evaluate!` can call the engine synchronously."
     ([] (engine! {}))
     ([{:keys [modules module-provider signal] :or {modules {}}}]
      (if (aborted? signal)
        (abort-rejection)
      (-> (module!)
          (.then (fn [module]
                   (when (aborted? signal)
                     (throw (js/Error. "QuickJS WASM initialization aborted")))
                   (let [context-atom (atom nil)]
                     (execution/wasm-engine
                      {:binary (binary-descriptor)
                       :manifest (runtime/component-manifest (runtime/quickjs))
                       :quickjs.wasm/descriptor (assoc (descriptor)
                                                       :quickjs.wasm/modules (vec (keys modules))
                                                       :quickjs.wasm/module-provider? (boolean module-provider)
                                                       :quickjs.wasm/context :reusable)
                       :dispose (fn [_engine]
                                  (dispose-context-atom! context-atom))
                       :invoke (fn [request]
                                 (let [context (ensure-context! context-atom module modules module-provider)]
                                   (case (:quickjs/call request)
                                     :js/evaluate
                                     (context-eval-result context
                                                          (:source request)
                                                          (:url request)
                                                          (or (= :module (:type request))
                                                              (= :module (:script/type request)))
                                                          (:document/snapshot request)
                                                          (:storage/snapshot request)
                                                          (:clipboard/snapshot request)
                                                          (:geolocation/snapshot request)
                                                          (:websocket/snapshot request))

                                     :js/module-load
                                     (if-let [source (resolve-module-source modules module-provider (:specifier request))]
                                       (context-eval-result context
                                                            source
                                                            (:specifier request)
                                                            true
                                                            (:document/snapshot request)
                                                            (:storage/snapshot request)
                                                            (:clipboard/snapshot request)
                                                            (:geolocation/snapshot request)
                                                            (:websocket/snapshot request))
                                       {:error :quickjs/module-not-found
                                        :specifier (:specifier request)
                                        :requests []})

                                     :js/job
                                     (context-run-task-result context (:task request))

                                     {:error :quickjs/unsupported-call
                                      :call (:quickjs/call request)
                                      :requests []})))})))))))))

#?(:cljs
   (defn engine-from-session!
     [session]
     (engine! (cond-> (engine-options-from-session session)
                (:browser.session/abort-signal session)
                (assoc :signal (:browser.session/abort-signal session))))))

#?(:clj
   (defn engine!
     ([] (engine! {}))
     ([{:keys [modules module-provider] :or {modules {}}}]
      (execution/wasm-engine
       {:binary (binary-descriptor)
        :manifest (runtime/component-manifest (runtime/quickjs))
        :quickjs.wasm/descriptor (assoc (descriptor)
                                        :quickjs.wasm/modules (vec (keys modules))
                                        :quickjs.wasm/module-provider? (boolean module-provider))
        :invoke nil}))))

#?(:clj
   (defn engine-from-session!
     [session]
     (engine! (engine-options-from-session session))))
