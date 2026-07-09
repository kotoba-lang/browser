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

(defn worker-fn
  "Extract the real, engine-provided `:worker-fn` dispatch function (see
  `worker-runtime-fn`) from an `engine` descriptor built by `engine!`/
  `engine-from-session!`. Present only for a genuine `:cljs` quickjs-wasm
  engine (it rides along in `:quickjs.engine/meta`, the same bucket
  `execution/wasm-engine` already collects any non-core engine opts into)
  -- absent, hence nil, for the JVM `:clj` stub engine (whose `engine!`
  never sets it) and for any hand-rolled test-double `:engine` fn (a plain
  fn, not a map, so `map?` alone already excludes it). Callers thread the
  result into a session as `quickjs-execution/new-state`'s `:worker-fn`
  (via `:browser.session/worker-fn`), exactly the way a caller constructs
  and injects a real `browser.net.websocket/websocket-fn` as
  `:websocket-fn` -- see `test-cljs/browser/compat/quickjs_worker_smoke_test.cljs`."
  [engine]
  (when (map? engine)
    (get-in engine [:quickjs.engine/meta :worker-fn])))

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
      globalThis.__kotobaNextFetchId = 1;
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
      globalThis.__kotobaWorkers = globalThis.__kotobaWorkers || {};
      globalThis.__kotobaFetchPending = globalThis.__kotobaFetchPending || {};
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
      /* __kotobaMakeDeferred: a minimal, hand-rolled thenable -- NOT the
         engine's native Promise. QuickJS's native Promise queues .then()
         reactions as VM jobs that only run once the host calls
         runtime.executePendingJobs(), which this repo's eval-result/
         dump-requests never do (see quickjs_wasm.cljc). A native
         Promise.resolve()d from outside a running script eval (the whole
         point of fetch() delivery -- see webapi-shim-source's fetch
         delivery IIFE below) would therefore silently never invoke any
         .then() callback. This deferred settles its reactions
         SYNCHRONOUSLY instead, the moment resolve()/reject() is called --
         honest for this engine's already-synchronous-per-script-tag
         architecture (mirrors __kotobaScheduleMicrotask's host-mediated,
         not real-microtask-queued, callback delivery), not spec-compliant
         microtask timing. */
      function __kotobaMakeDeferred() {
        var state = 'pending';
        var value;
        var reactions = [];
        function run(reaction) {
          var handler = state === 'fulfilled' ? reaction.onFulfilled : reaction.onRejected;
          if (typeof handler !== 'function') {
            if (state === 'fulfilled') reaction.resolve(value);
            else reaction.reject(value);
            return;
          }
          try {
            reaction.resolve(handler(value));
          } catch (err) {
            reaction.reject(err);
          }
        }
        function settle(nextState, nextValue) {
          if (state !== 'pending') return;
          if (nextState === 'fulfilled' && nextValue && typeof nextValue.then === 'function') {
            nextValue.then(
              function(v) { settle('fulfilled', v); },
              function(e) { settle('rejected', e); }
            );
            return;
          }
          state = nextState;
          value = nextValue;
          var pending = reactions;
          reactions = [];
          for (var i = 0; i < pending.length; i++) run(pending[i]);
        }
        var deferred = {};
        deferred.resolve = function(v) { settle('fulfilled', v); };
        deferred.reject = function(e) { settle('rejected', e); };
        deferred.promise = {
          then: function(onFulfilled, onRejected) {
            var next = __kotobaMakeDeferred();
            var reaction = {
              onFulfilled: onFulfilled,
              onRejected: onRejected,
              resolve: next.resolve,
              reject: next.reject
            };
            if (state === 'pending') {
              reactions.push(reaction);
            } else {
              run(reaction);
            }
            return next.promise;
          }
        };
        deferred.promise.catch = function(onRejected) {
          return deferred.promise.then(undefined, onRejected);
        };
        return deferred;
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
        // Real HTML5 (confirmed against real Chrome before touching
        // source): an explicit selected attr wins outright regardless of
        // disabled -- previously this only returned early when the
        // selected candidate was ALSO enabled, so a select whose ONLY
        // selected option was disabled (the common disabled-placeholder-
        // with-a-real-value idiom) fell through and reported '' instead
        // of that option's own value. The fallback path below (no
        // explicit selected at all) is unaffected and already correctly
        // disabled-aware -- confirmed live that a disabled first option
        // with nothing explicitly selected defaults to the next, enabled
        // option, never the disabled one, and that an all-disabled
        // select with nothing selected reports '' (selects nothing at
        // all), not a fallback to the plain first option regardless.
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
              return __kotobaOptionValue(candidate);
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
      function __kotobaClearRadioGroupSiblings(node) {
        // Real HTML5: whenever a radio button's checkedness is set to true
        // (by script OR real user interaction), every OTHER checked radio
        // in the same name/form group is un-checked -- the exact real
        // pointer-click reduce-event pipeline in document_input.cljc
        // already does this (radio-group-node-ids); this JS-facing
        // shim previously had no equivalent for scripted .click()/
        // .checked= (confirmed via a real QuickJS smoke test: setting
        // one radio's .checked left a sibling radio in the same group
        // still wrongly checked too).
        var group = __kotobaRadioGroupNodes(node);
        for (var i = 0; i < group.length; i++) {
          if (group[i]['node/id'] !== node['node/id'] && __kotobaBoolAttr(group[i], 'checked')) {
            __kotobaRemoveAttribute({ nodeId: group[i]['node/id'] }, 'checked');
          }
        }
      }
      function __kotobaDispatchClickWithActivation(ref, event) {
        // Real checkbox/radio click activation behavior -- mirrors the
        // ALREADY-correct real pointer-click path in document_input.cljc
        // (checkbox toggles; radio checks itself and clears group
        // siblings, but only if not already checked), including real
        // click order (checked flips synchronously BEFORE click fires,
        // input/change fire only afterward) and real canceled-activation-
        // steps behavior (a click listener calling preventDefault()
        // reverts the tentative checked flip and fires neither input nor
        // change). Shared between click() and dispatchEvent() of a
        // script-constructed click event -- per real HTML5/DOM, this
        // activation behavior is part of the generic event-dispatch
        // algorithm for a `click` event, independent of whether dispatch
        // was triggered by .click(), a real user click, or
        // el.dispatchEvent(new MouseEvent('click')) (confirmed against
        // real Chrome: dispatchEvent-triggered clicks DO toggle a real
        // checkbox and fire input/change) -- previously only .click()
        // got this, dispatchEvent() only ever ran listeners, confirmed
        // via a real QuickJS smoke test.
        var node = __kotobaNodeById(__kotobaRefNodeId(ref));
        var tag = node && String(node.tag || '').toLowerCase();
        var type = node && String(__kotobaAttr(node, 'type') || 'text').toLowerCase();
        var stateChanged = false;
        var previousChecked = null;
        var previousGroupCheckedIds = null;
        if (node && !__kotobaDisabledControl(node)) {
          if (tag === 'input' && type === 'checkbox') {
            previousChecked = __kotobaBoolAttr(node, 'checked');
            __kotobaSetBooleanAttribute(ref, 'checked', !previousChecked);
            stateChanged = true;
          } else if (tag === 'input' && type === 'radio' && !__kotobaBoolAttr(node, 'checked')) {
            var group = __kotobaRadioGroupNodes(node);
            previousGroupCheckedIds = group.filter(function(n) { return __kotobaBoolAttr(n, 'checked'); })
              .map(function(n) { return n['node/id']; });
            __kotobaSetBooleanAttribute(ref, 'checked', true);
            __kotobaClearRadioGroupSiblings(node);
            stateChanged = true;
          }
        }
        var result = __kotobaDispatch(ref, event);
        if (stateChanged) {
          if (result) {
            __kotobaDispatch(ref, __kotobaEvent('input', { bubbles: true }));
            __kotobaDispatch(ref, __kotobaEvent('change', { bubbles: true }));
          } else {
            if (tag === 'input' && type === 'checkbox') {
              __kotobaSetBooleanAttribute(ref, 'checked', previousChecked);
            } else if (tag === 'input' && type === 'radio') {
              __kotobaSetBooleanAttribute(ref, 'checked', false);
              for (var i = 0; i < previousGroupCheckedIds.length; i++) {
                __kotobaSetBooleanAttribute({ nodeId: previousGroupCheckedIds[i] }, 'checked', true);
              }
            }
          }
        }
        return result;
      }
      function __kotobaControlValue(node) {
        var textValue = __kotobaAttr(node, 'text/value');
        if (textValue != null) return String(textValue);
        if (String(node && node.tag || '').toLowerCase() === 'select') return __kotobaSelectValue(node);
        var value = __kotobaAttr(node, 'value');
        return value == null ? '' : String(value);
      }
      function __kotobaParseNumber(v) {
        if (v == null) return NaN;
        var s = String(v).trim();
        return /^-?\\d+(\\.\\d+)?$/.test(s) ? parseFloat(s) : NaN;
      }
      function __kotobaTypeMismatch(type, value) {
        // Real HTML5 typeMismatch: a non-blank type=email/url value not
        // matching that type's own format -- previously an honest,
        // documented scope-cut here (patternMismatch's own fix landed a
        // prior cycle; typeMismatch was the other half of that same
        // scope-cut comment). email uses the real WHATWG spec regex
        // verbatim (not a hand-simplified approximation, except that the
        // multiple attribute's comma-separated-list form is out of
        // scope); url is a deliberately simplified absolute-URL-shape
        // check (scheme://...), since this engine has no real WHATWG URL
        // parser to match against.
        if (value.trim() === '') return false;
        if (type === 'email') {
          return !/^[a-zA-Z0-9.!#$%&'*+\\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/.test(value);
        }
        if (type === 'url') {
          return !/^[a-zA-Z][a-zA-Z0-9+.-]*:\\/\\/\\S+$/.test(value);
        }
        return false;
      }
      function __kotobaStepMismatch(type, value, node) {
        // Real HTML5 step-mismatch -- mirrors cssom.core's own
        // identically-scoped step-invalid?/browser.document-input's own
        // step-invalid? exactly (fixed together for the same reason
        // patternMismatch/typeMismatch above were). Real default step is
        // 1, a genuinely common surprise -- type=number with no step
        // attribute at all still requires an integer value -- and
        // step=any disables the check entirely. A step present but not
        // itself a valid positive number falls back to that same
        // default of 1, matching real HTML5's own defined fallback
        // (deliberately different from min/max, which have nothing
        // sensible to fall back to and so are simply dropped when
        // malformed).
        if (type !== 'number' && type !== 'range') return false;
        if (value.trim() === '') return false;
        var n = __kotobaParseNumber(value);
        if (Number.isNaN(n)) return false;
        var rawStep = __kotobaAttr(node, 'step');
        if (rawStep != null && String(rawStep).toLowerCase() === 'any') return false;
        var parsedStep = __kotobaParseNumber(rawStep);
        var step = (!Number.isNaN(parsedStep) && parsedStep > 0) ? parsedStep : 1;
        var base = __kotobaParseNumber(__kotobaAttr(node, 'min'));
        if (Number.isNaN(base)) base = 0;
        var steps = (n - base) / step;
        var frac = steps - Math.floor(steps);
        return frac > 1e-9 && frac < (1 - 1e-9);
      }
      function __kotobaApplyStep(node, delta) {
        // Real HTMLInputElement.stepUp()/stepDown() -- same number/range-
        // only scope as __kotobaStepMismatch above. `delta` already carries
        // the caller's own sign (positive for stepUp, negative for
        // stepDown) and multiplier (the optional `n` argument, default 1).
        // A real step=any control (the literal keyword any, unquoted here
        // to avoid a Clojure string-termination hazard this codebase has
        // hit before) makes stepUp()/stepDown() throw an InvalidStateError
        // in real browsers -- this engine has no DOMException type at all,
        // so it degrades to a silent no-op instead of crashing, matching
        // this codebase's own established degrade-don't-throw convention
        // for malformed/inapplicable constraint state elsewhere (malformed
        // pattern, step=any itself in __kotobaStepMismatch). A blank/
        // non-numeric current value
        // starts from `min` (or 0 if no `min`) rather than throwing, and
        // the result is clamped to `min`/`max` when present -- an honest
        // simplification of real HTML5's own step-aligned clamping (which
        // realigns to the nearest step-valid value at the boundary rather
        // than the bare boundary itself), reused across both real callers.
        var type = String(__kotobaAttr(node, 'type') || 'text').toLowerCase();
        if (type !== 'number' && type !== 'range') return null;
        var rawStep = __kotobaAttr(node, 'step');
        if (rawStep != null && String(rawStep).toLowerCase() === 'any') return null;
        var parsedStep = __kotobaParseNumber(rawStep);
        var step = (!Number.isNaN(parsedStep) && parsedStep > 0) ? parsedStep : 1;
        var min = __kotobaParseNumber(__kotobaAttr(node, 'min'));
        var max = __kotobaParseNumber(__kotobaAttr(node, 'max'));
        var current = __kotobaParseNumber(__kotobaControlValue(node));
        if (Number.isNaN(current)) current = Number.isNaN(min) ? 0 : min;
        var next = current + delta * step;
        if (!Number.isNaN(min) && next < min) next = min;
        if (!Number.isNaN(max) && next > max) next = max;
        return next;
      }
      function __kotobaCompilePattern(pattern) {
        // Malformed `pattern` degrades to NOT enforced (returns null),
        // matching this fn's own existing min/max/minlength/maxlength
        // degrade-don't-guess treatment -- not a crash. The `^(?:...)$`
        // wrap is real HTML5's own implicit anchoring of the `pattern`
        // attribute (a bare, unanchored regex would otherwise only need
        // to match a SUBSTRING of the value to pass.
        try {
          return new RegExp('^(?:' + pattern + ')$');
        } catch (e) {
          return null;
        }
      }
      function __kotobaValidationReason(node) {
        // real HTML5 ValidityState property names, in the exact same
        // precedence order as browser.document-input's own (CLJC,
        // form-submission-time) validation-reason -- this JS copy is what
        // both a real element.matches(':invalid') AND the new .validity/
        // checkValidity()/reportValidity() JS-facing surface see, and
        // previously had no min/max check at all.
        if (__kotobaConstraintValidationBarredControl(node)) return null;
        var value = __kotobaControlValue(node);
        var tag = String(node && node.tag || '').toLowerCase();
        var type = String(__kotobaAttr(node, 'type') || 'text').toLowerCase();
        var minlength = parseInt(__kotobaAttr(node, 'minlength'), 10);
        var maxlength = parseInt(__kotobaAttr(node, 'maxlength'), 10);
        var rangeValue = (tag === 'input' && (type === 'number' || type === 'range') && value.trim() !== '')
          ? __kotobaParseNumber(value) : NaN;
        var rangeMin = __kotobaParseNumber(__kotobaAttr(node, 'min'));
        var rangeMax = __kotobaParseNumber(__kotobaAttr(node, 'max'));
        // `pattern` is real HTML5's own restriction: only text/search/url/
        // tel/email/password <input>s (NOT <textarea>, despite an untyped
        // <textarea> also having no real `type` attribute of its own),
        // and only ever enforced against a non-blank value (an empty,
        // non-required field's own emptiness is `required`'s concern, not
        // `pattern`'s).
        var pattern = __kotobaAttr(node, 'pattern');
        var patternApplicable = tag === 'input' &&
          (type === 'text' || type === 'search' || type === 'url' || type === 'tel' ||
           type === 'email' || type === 'password') &&
          pattern != null && value.trim() !== '';
        var patternRegex = patternApplicable ? __kotobaCompilePattern(pattern) : null;
        if (__kotobaBoolAttr(node, 'required') &&
            ((tag === 'input' && type === 'checkbox' && !__kotobaBoolAttr(node, 'checked')) ||
             (tag === 'input' && type === 'radio' && !__kotobaRadioRequiredSatisfied(node)) ||
             (!(tag === 'input' && (type === 'checkbox' || type === 'radio')) && value.trim() === ''))) {
          return 'valueMissing';
        }
        if (!Number.isNaN(minlength) && value.length > 0 && value.length < minlength) return 'tooShort';
        if (!Number.isNaN(maxlength) && value.length > maxlength) return 'tooLong';
        if (tag === 'input' && __kotobaTypeMismatch(type, value)) return 'typeMismatch';
        if (patternRegex && !patternRegex.test(value)) return 'patternMismatch';
        if (!Number.isNaN(rangeValue) && !Number.isNaN(rangeMin) && rangeValue < rangeMin) return 'rangeUnderflow';
        if (!Number.isNaN(rangeValue) && !Number.isNaN(rangeMax) && rangeValue > rangeMax) return 'rangeOverflow';
        if (tag === 'input' && __kotobaStepMismatch(type, value, node)) return 'stepMismatch';
        return null;
      }
      function __kotobaConstraintInvalid(node) {
        if (__kotobaConstraintValidationBarredControl(node)) return false;
        return __kotobaBoolAttr(node, 'invalid') || __kotobaValidationReason(node) != null;
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
      function __kotobaWillValidate(node) {
        return __kotobaFormControl(node) &&
          !__kotobaDisabledControl(node) &&
          !__kotobaConstraintValidationBarredControl(node);
      }
      function __kotobaConstraintValid(node) {
        return __kotobaWillValidate(node) && !__kotobaConstraintInvalid(node);
      }
      function __kotobaValidityState(node) {
        // Real ValidityState always exposes every flag (false when not
        // applicable), so JS reading e.g. validity.stepMismatch on a
        // control this engine never flags gets a real `false`, not
        // `undefined`. Only the 8 reasons browser.document-input's own
        // validation-reason already computes are ever real (`valueMissing`/
        // `tooShort`/`tooLong`/`typeMismatch`/`patternMismatch`/
        // `rangeUnderflow`/`rangeOverflow`/`stepMismatch`) -- `badInput`/
        // `customError` are an honest, documented scope-cut (no
        // `setCustomValidity()` exists anywhere in this engine yet),
        // always `false`. Unlike the :invalid/:valid CSS pseudo-class
        // match (which also consults the historical, form-submission-time
        // `invalid` attr set by browser.document-input's dispatch-invalid-
        // events), .validity is real HTML5 semantics: always the LIVE,
        // freshly recomputed constraint state, never the historical
        // submit-time marker.
        var reason = __kotobaWillValidate(node) ? __kotobaValidationReason(node) : null;
        return {
          valueMissing: reason === 'valueMissing',
          typeMismatch: reason === 'typeMismatch',
          patternMismatch: reason === 'patternMismatch',
          tooShort: reason === 'tooShort',
          tooLong: reason === 'tooLong',
          rangeUnderflow: reason === 'rangeUnderflow',
          rangeOverflow: reason === 'rangeOverflow',
          stepMismatch: reason === 'stepMismatch',
          badInput: false,
          customError: false,
          valid: reason === null
        };
      }
      function __kotobaValidationMessage(node) {
        // Real HTML5 `.validationMessage` is a human-readable string
        // (locale/engine-specific by spec -- not required to match any
        // real browser's own copy byte-for-byte), always the empty string
        // whenever the control is either not a validation candidate at
        // all (__kotobaWillValidate false, e.g. disabled) or currently
        // satisfies every constraint __kotobaValidationReason evaluates
        // (reason === null) -- both already real, load-bearing states in
        // __kotobaValidityState above.
        if (!__kotobaWillValidate(node)) return '';
        var reason = __kotobaValidationReason(node);
        if (reason === null) return '';
        var value = __kotobaControlValue(node);
        var type = String(__kotobaAttr(node, 'type') || 'text').toLowerCase();
        switch (reason) {
          case 'valueMissing': return 'Please fill out this field.';
          case 'typeMismatch':
            if (type === 'email') return 'Please enter a valid email address.';
            if (type === 'url') return 'Please enter a valid URL.';
            return 'Please enter a valid value.';
          case 'patternMismatch': return 'Please match the requested format.';
          case 'tooShort':
            return 'Please lengthen this text to ' + parseInt(__kotobaAttr(node, 'minlength'), 10) +
              ' characters or more (you are currently using ' + value.length + ' characters).';
          case 'tooLong':
            return 'Please shorten this text to ' + parseInt(__kotobaAttr(node, 'maxlength'), 10) +
              ' characters or less (you are currently using ' + value.length + ' characters).';
          case 'rangeUnderflow':
            return 'Value must be greater than or equal to ' + __kotobaAttr(node, 'min') + '.';
          case 'rangeOverflow':
            return 'Value must be less than or equal to ' + __kotobaAttr(node, 'max') + '.';
          case 'stepMismatch': return 'Please enter a valid value.';
          default: return '';
        }
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
      // Real HTML has no default-value/scroll-top/selection-start/etc
      // attribute -- these are this engine's own internal bookkeeping,
      // stored via the exact same generic dom/set-attribute path real
      // attributes use (see htmldom.core/initialize-form-node,
      // document_input.cljc's selection/composition/validation/file-list
      // tracking) so JS-side property getters like .defaultValue/.scrollTop/
      // .selectionStart/.files can read them back via __kotobaAttr. Real
      // author-visible surfaces -- .attributes and innerHTML/outerHTML --
      // must not enumerate them; every style-namespaced cascade-resolved
      // longhand (a plain style/ prefix here, no colon) is the same kind
      // of internal-only value and gets the same treatment.
      var __kotobaInternalAttrNames = {
        'style-inline': true, 'style-inline-important': true,
        'default-value': true, 'default-checked': true, 'default-selected': true,
        'scroll-top': true, 'scroll-left': true,
        'selection-start': true, 'selection-end': true,
        'composition': true, 'composing': true,
        'invalid': true, 'validation-reason': true,
        'dirty-value': true, 'files': true
      };
      function __kotobaPublicAttrName(name) {
        return !Object.prototype.hasOwnProperty.call(__kotobaInternalAttrNames, name)
          && name.indexOf('style/') !== 0;
      }
      var __kotobaVoidTags = {
        area: true, base: true, br: true, col: true, embed: true, hr: true,
        img: true, input: true, link: true, meta: true, param: true,
        source: true, track: true, wbr: true
      };
      function __kotobaSerializeNode(node) {
        if (!node) return '';
        if (node['node/type'] === 'text') return __kotobaEscapeHtml(node.text || '');
        if (node['node/type'] === 'document-fragment') {
          return (node.children || []).map(function(id) { return __kotobaSerializeNode(__kotobaNodeById(id)); }).join('');
        }
        var tag = String(node.tag || '').toLowerCase();
        var attrs = node.attrs || {};
        var attrText = Object.keys(attrs).filter(__kotobaPublicAttrName).sort().map(function(name) {
          return ' ' + name + '=' + String.fromCharCode(34) + __kotobaEscapeHtml(attrs[name]) + String.fromCharCode(34);
        }).join('');
        if (Object.prototype.hasOwnProperty.call(__kotobaVoidTags, tag)) {
          return '<' + tag + attrText + '>';
        }
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
        var names = Object.keys(attrs).filter(__kotobaPublicAttrName).sort();
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
          replace: function(oldToken, newToken) {
            oldToken = String(oldToken);
            newToken = String(newToken);
            var next = tokens();
            var i = next.indexOf(oldToken);
            // Real spec: absent oldToken is a no-op (does NOT add newToken).
            if (i < 0) return false;
            next[i] = newToken;
            write(next);
            return true;
          },
          toString: function() { return tokens().join(' '); },
          get value() { return tokens().join(' '); }
        };
      }
      function __kotobaDataSet(ref) {
        // Real DOMStringMap enumerates like a plain object -- Object.keys(),
        // for...in, and object-spread must all see every real data-*
        // attribute, matching direct property access (el.dataset.foo),
        // which already worked correctly. Previously this Proxy had no
        // ownKeys/getOwnPropertyDescriptor traps at all, so every one of
        // those enumeration paths fell through to the Proxy's own
        // permanently-empty {} target instead, silently returning nothing
        // -- confirmed via a temporary CLJS/QuickJS smoke test before
        // touching source: Object.keys(el.dataset) on a real element with
        // two real data-* attributes read as an empty list even though
        // el.dataset.foo itself already read the right value.
        function dataKeys() {
          var node = __kotobaNodeById(__kotobaRefNodeId(ref));
          var attrs = node && node.attrs ? node.attrs : {};
          return Object.keys(attrs)
            .filter(function(name) { return name.indexOf('data-') === 0; })
            .map(__kotobaDatasetKey);
        }
        return new Proxy({}, {
          get: function(_, prop) {
            if (prop === 'toJSON') return function() {
              var node = __kotobaNodeById(__kotobaRefNodeId(ref));
              var attrs = node && node.attrs ? node.attrs : {};
              return dataKeys().reduce(function(out, key) {
                out[key] = String(__kotobaAttr(node, __kotobaDataAttrName(key)));
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
          },
          ownKeys: function() {
            return dataKeys();
          },
          getOwnPropertyDescriptor: function(_, prop) {
            if (typeof prop === 'symbol') return undefined;
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var value = __kotobaAttr(node, __kotobaDataAttrName(prop));
            // Required for the Proxy invariants: ownKeys' reported keys
            // must each have a corresponding descriptor, and it must be
            // configurable since the underlying target ({}) has no real
            // own property for it (a non-configurable descriptor for a
            // phantom property throws a real TypeError at the engine
            // level).
            if (value == null) return undefined;
            return { value: String(value), writable: true, enumerable: true, configurable: true };
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
            // cssText previously fell through to the generic per-property
            // path below, which namespaces it as the literal property name
            // style/css-text -- a fake property nothing else recognizes.
            // Real el.style.cssText reads/writes the element's raw inline
            // style TEXT, the exact same thing the already-correct
            // setAttribute('style', ...) path reads/writes via the plain,
            // un-namespaced style attr -- so this reuses that same attr
            // directly instead of inventing a new one.
            if (prop === 'cssText') {
              var node = __kotobaNodeById(__kotobaRefNodeId(ref));
              var value = __kotobaAttr(node, 'style');
              return value == null ? '' : String(value);
            }
            if (typeof prop === 'symbol') return undefined;
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var value = __kotobaAttr(node, __kotobaStyleAttrName(prop));
            return value == null ? '' : String(value);
          },
          set: function(_, prop, value) {
            if (prop === 'cssText') {
              __kotobaSetAttribute(ref, 'style', value);
              return true;
            }
            __kotobaSetAttribute(ref, __kotobaStyleAttrName(prop), value);
            return true;
          }
        });
      }
      function __kotobaComputedStyle(ref) {
        // Real getComputedStyle() is genuinely READ-ONLY -- unlike
        // __kotobaStyle above (element.style), this Proxy has no `set`
        // trap at all, so an assignment attempt falls through to the
        // default JS behavior (setting a plain, inert own-property on the
        // Proxy's empty `{}` target) rather than mutating the real node,
        // matching a real CSSStyleDeclaration returned by
        // getComputedStyle() being immune to writes. `:style/<prop>`
        // attrs are already the real, cascade-computed style -- rebuilt
        // every page commit by cssom.core/apply-cascade's style-element
        // from stylesheet rules + :style-inline, confirmed via direct
        // REPL reproduction: a real element with ONLY a stylesheet rule
        // (no inline style at all) already reads back the stylesheet's
        // own value through this exact attr lookup -- so this can reuse
        // __kotobaStyle's own get-trap logic verbatim rather than
        // needing any new host-side plumbing.
        return new Proxy({}, {
          get: function(_, prop) {
            if (prop === 'getPropertyValue') return function(name) {
              var node = __kotobaNodeById(__kotobaRefNodeId(ref));
              var value = __kotobaAttr(node, __kotobaStyleAttrName(name));
              return value == null ? '' : String(value);
            };
            if (typeof prop === 'symbol') return undefined;
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var value = __kotobaAttr(node, __kotobaStyleAttrName(prop));
            return value == null ? '' : String(value);
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
          // Real spec: stops propagation to ANCESTOR nodes only -- other
          // listeners already registered on the SAME target must still
          // run. Previously conflated with stopImmediatePropagation()
          // (which did not exist at all) via a single cancelBubble flag
          // that __kotobaDispatch's inner per-target listener loop also
          // checked, wrongly skipping sibling listeners on the same node
          // too -- confirmed via a real QuickJS smoke test.
          event.cancelBubble = true;
        };
        event.stopImmediatePropagation = function() {
          event.cancelBubble = true;
          event.immediatePropagationStopped = true;
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
          clientY: event && event.clientY != null ? Number(event.clientY) : null,
          shiftKey: Boolean(event && event.shiftKey),
          ctrlKey: Boolean(event && event.ctrlKey),
          altKey: Boolean(event && event.altKey),
          metaKey: Boolean(event && event.metaKey)
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
            if (event.immediatePropagationStopped) break;
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
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            if (value && node && String(node.tag || '').toLowerCase() === 'input' &&
                String(__kotobaAttr(node, 'type') || 'text').toLowerCase() === 'radio') {
              __kotobaClearRadioGroupSiblings(node);
            }
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
          get hidden() {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            return __kotobaBoolAttr(node, 'hidden');
          },
          set hidden(value) {
            __kotobaSetBooleanAttribute(ref, 'hidden', value);
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
          // selectionStart/selectionEnd/setSelectionRange previously wrote
          // straight to a bare node['selection-start']/node['selection-end']
          // property on the LOCAL snapshot object -- never through
          // setAttribute/__kotobaRequests, so the real host document's own
          // :selection-start/:selection-end attrs (what cssom.layout's
          // sel-ops paints the caret/selection highlight from, and what
          // org-w3-aria projects into the real accessibility tree) never
          // updated at all. The bug was invisible to the calling script
          // itself, since the getter read the very field the setter just
          // wrote, masking the divergence. Confirmed via direct Node
          // execution of the actual (pre-fix) function body before
          // touching source. Now routed through the exact same
          // setAttribute/__kotobaAttr path every sibling internal-
          // bookkeeping property (scrollTop/scrollLeft/defaultValue/...)
          // already correctly uses.
          // Real HTMLInputElement/HTMLTextAreaElement.selectionStart/
          // selectionEnd are ALWAYS clamped to [0, value.length] -- these
          // getters previously read the raw selection-start/selection-end
          // attrs straight through with no such clamp. This is the exact
          // same stale-selection root cause already fixed twice
          // downstream (cssom.layout's own paint sel-ops, org-w3-aria's
          // accessible-node), reached here through the direct scripting
          // API instead: set value(value) has no spec-mandated selection-
          // reset, so a real `el.select(); el.value = shorter;` idiom
          // left these getters reporting offsets exceeding the NEW,
          // shorter value's own length -- arguably the most consequential
          // of the three consumers, since a caller doing
          // `value.slice(el.selectionStart, el.selectionEnd)` right after
          // gets a silently wrong (empty, here) slice instead of a
          // clamped, sane one. Confirmed via direct Node execution of the
          // actual (pre-fix) getter bodies, copy-pasted verbatim, before
          // touching source.
          get selectionStart() {
            var value = __kotobaParseNumber(__kotobaAttr(__kotobaNodeById(__kotobaRefNodeId(ref)), 'selection-start'));
            var len = this.value.length;
            return Number.isNaN(value) ? len : Math.max(0, Math.min(len, value));
          },
          set selectionStart(value) {
            this.setSelectionRange(Number(value), this.selectionEnd);
          },
          get selectionEnd() {
            var value = __kotobaParseNumber(__kotobaAttr(__kotobaNodeById(__kotobaRefNodeId(ref)), 'selection-end'));
            var len = this.value.length;
            return Number.isNaN(value) ? len : Math.max(0, Math.min(len, value));
          },
          set selectionEnd(value) {
            this.setSelectionRange(this.selectionStart, Number(value));
          },
          get willValidate() {
            return __kotobaWillValidate(__kotobaNodeById(__kotobaRefNodeId(ref)));
          },
          get validity() {
            return __kotobaValidityState(__kotobaNodeById(__kotobaRefNodeId(ref)));
          },
          get validationMessage() {
            return __kotobaValidationMessage(__kotobaNodeById(__kotobaRefNodeId(ref)));
          },
          get scrollTop() {
            // The real host-bridged half of this already exists --
            // document_input.cljc's reduce-scroll-event writes the exact
            // same 'scroll-top' attr on every real wheel event, and
            // cssom.layout already reads it back to clip/offset scrollable
            // content at paint time -- only this JS-facing read/write
            // surface was missing.
            var value = __kotobaParseNumber(__kotobaAttr(__kotobaNodeById(__kotobaRefNodeId(ref)), 'scroll-top'));
            return Number.isNaN(value) ? 0 : value;
          },
          set scrollTop(value) {
            this.setAttribute('scroll-top', Math.max(0, Number(value) || 0));
          },
          get scrollLeft() {
            var value = __kotobaParseNumber(__kotobaAttr(__kotobaNodeById(__kotobaRefNodeId(ref)), 'scroll-left'));
            return Number.isNaN(value) ? 0 : value;
          },
          set scrollLeft(value) {
            this.setAttribute('scroll-left', Math.max(0, Number(value) || 0));
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
          insertAdjacentHTML: function(position, html) {
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'insert-adjacent-html',
              position: String(position),
              html: String(html)
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
          },
          insertAdjacentElement: function(position, element) {
            switch (String(position)) {
              case 'beforebegin': this.before(element); break;
              case 'afterbegin': this.prepend(element); break;
              case 'beforeend': this.append(element); break;
              case 'afterend': this.after(element); break;
            }
            return element;
          },
          insertAdjacentText: function(position, text) {
            var node = __kotobaTextNode(String(text));
            switch (String(position)) {
              case 'beforebegin': this.before(node); break;
              case 'afterbegin': this.prepend(node); break;
              case 'beforeend': this.append(node); break;
              case 'afterend': this.after(node); break;
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
            // Real ParentNode.replaceChildren(...nodes) accepts (Node or
            // DOMString) for each argument, exactly like append/prepend/
            // before/after/replaceWith below -- unlike those siblings,
            // this loop previously called appendChild directly without
            // wrapping a bare string into a real text node first via
            // __kotobaNodeArg. appendChild only recognizes a real node
            // (it reads child.__kotobaRef), so a plain string arg had no
            // __kotobaRef and was silently dropped: el.replaceChildren(
            // 'hello') cleared el's real children and then added
            // nothing at all, leaving el completely empty.
            for (var i = 0; i < arguments.length; i++) {
              this.appendChild(__kotobaNodeArg(arguments[i]));
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
          replaceChild: function(newChild, oldChild) {
            // parentNode.replaceChild(newChild, oldChild) -- called on the
            // PARENT, argument order (new, old) reversed vs. replaceWith's
            // own (old, new) shape above, and returns the REMOVED child
            // per spec, not the new one. Composes this.insertBefore/
            // this.removeChild verbatim, the same composition replaceWith
            // already uses.
            this.insertBefore(newChild, oldChild);
            this.removeChild(oldChild);
            return oldChild;
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
            var valueLength = this.value.length;
            var s = Math.max(0, Math.min(valueLength, Number(start) || 0));
            var e = Math.max(0, Math.min(valueLength, Number(end) || 0));
            this.setAttribute('selection-start', Math.min(s, e));
            this.setAttribute('selection-end', Math.max(s, e));
          },
          select: function() {
            // Real HTMLInputElement/HTMLTextAreaElement.select() -- selects
            // all of the control's own text, equivalent to a real
            // setSelectionRange(0, value.length), reused verbatim here
            // (matching this engine's own existing permissive posture --
            // setSelectionRange itself never gates on control type either,
            // e.g. a checkbox's own attribute value.length -- neither
            // visually matters here since layout-form-control's own sel-ops
            // only ever renders a caret/selection for a real text-entry
            // input, gated separately at the paint layer).
            this.setSelectionRange(0, this.value.length);
          },
          stepUp: function(n) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var next = __kotobaApplyStep(node, n == null ? 1 : Number(n));
            if (next != null) this.value = String(next);
          },
          stepDown: function(n) {
            var node = __kotobaNodeById(__kotobaRefNodeId(ref));
            var next = __kotobaApplyStep(node, -(n == null ? 1 : Number(n)));
            if (next != null) this.value = String(next);
          },
          checkValidity: function() {
            return __kotobaValidityState(__kotobaNodeById(__kotobaRefNodeId(ref))).valid;
          },
          reportValidity: function() {
            // Honest scope-cut: a real reportValidity() also shows native
            // validation-bubble UI and scrolls the first invalid control
            // into view (gated on a cancelable `invalid` event) -- this
            // engine has neither a validation-bubble UI nor scrollIntoView/
            // getBoundingClientRect geometry to scroll with. Best-effort
            // substitute: focus the control (already a real op) when
            // invalid, same as checkValidity() otherwise.
            var valid = this.checkValidity();
            if (!valid) this.focus();
            return valid;
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
            event = event || __kotobaEvent('event', {});
            var eventType = String(event['event/type'] || event.type || 'event');
            if (eventType === 'click') return __kotobaDispatchClickWithActivation(ref, event);
            return __kotobaDispatch(ref, event);
          },
          click: function() {
            return __kotobaDispatchClickWithActivation(
              ref, __kotobaEvent('click', { bubbles: true, cancelable: true }));
          },
          focus: function() {
            // Real element.focus() is a no-op on a disabled form control --
            // document.activeElement never becomes a disabled element.
            // Reuses the same __kotobaDisabledControl helper every other
            // disabled-gated behavior in this file already does
            // (checkValidity/:disabled pseudo-class matching/etc.).
            if (__kotobaDisabledControl(__kotobaNodeById(__kotobaRefNodeId(ref)))) return;
            // Real focus/blur events -- previously never dispatched at all
            // (only the host mutate request and __kotobaSnapshot.focus were
            // updated), unlike the ALREADY-correct real pointer-click focus
            // path in document_input.cljc (focus-editable/blur-focused),
            // which blurs whatever was previously focused THEN fires focus
            // on the new target, and is a no-op if the target is already
            // focused. Confirmed via a real QuickJS smoke test: a real
            // focus/blur listener pair never fired at all.
            var newFocusId = __kotobaRefNodeId(ref);
            var previousFocusId = globalThis.__kotobaSnapshot.focus;
            if (previousFocusId === newFocusId) return;
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'focus-node'
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
            if (previousFocusId != null) {
              __kotobaDispatch({ nodeId: previousFocusId }, __kotobaEvent('blur', {}));
            }
            globalThis.__kotobaSnapshot.focus = newFocusId;
            __kotobaDispatch(ref, __kotobaEvent('focus', {}));
          },
          blur: function() {
            // A real .blur() call on an element that is NOT the currently
            // focused element is a no-op (no event, no mutation) -- mirrors
            // the same was-this-element-actually-focused gate
            // document_input.cljc's blur-focused already applies.
            if (globalThis.__kotobaSnapshot.focus !== __kotobaRefNodeId(ref)) return;
            var request = {
              capability: 'dom/mutate',
              'dom/op': 'blur-node'
            };
            Object.assign(request, __kotobaNodeRequest(ref, 'node'));
            globalThis.__kotobaRequests.push(request);
            globalThis.__kotobaSnapshot.focus = null;
            __kotobaDispatch(ref, __kotobaEvent('blur', {}));
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
        get body() {
          // Real document.body is a live, null-safe accessor -- returns
          // null when no real <body> element exists at all (this engine's
          // own HTML parser never synthesizes an implicit <html>/<body>
          // wrapper, so a bodyless page, e.g. a bare root element with no
          // <body> tag anywhere, is a real, reachable case), matching
          // every sibling accessor's own established shape (documentElement/
          // head below). Previously a plain, once-evaluated DATA property
          // built from a selector ref -- __kotobaElement never itself
          // returns null, so `document.body` was always a truthy stub
          // object even on a document with no real <body> at all, the
          // only non-getter, non-null-checked accessor in this entire
          // object literal.
          var id = __kotobaElementByTag('body');
          return id == null ? null : __kotobaElement({ nodeId: id });
        },
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
          return globalThis.__kotobaCookieSnapshot != null ? String(globalThis.__kotobaCookieSnapshot) : '';
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
        event.shiftKey = Boolean(init.shiftKey);
        event.ctrlKey = Boolean(init.ctrlKey);
        event.altKey = Boolean(init.altKey);
        event.metaKey = Boolean(init.metaKey);
        return event;
      };
      globalThis.KeyboardEvent = function(type, init) {
        init = init || {};
        var event = __kotobaEvent(type, init);
        event.key = init.key == null ? '' : String(init.key);
        event.code = init.code == null ? '' : String(init.code);
        event.repeat = Boolean(init.repeat);
        event.shiftKey = Boolean(init.shiftKey);
        event.ctrlKey = Boolean(init.ctrlKey);
        event.altKey = Boolean(init.altKey);
        event.metaKey = Boolean(init.metaKey);
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
            var nodeId = __kotobaRefNodeId(target.__kotobaRef);
            var entry = {
              nodeId: nodeId,
              options: {
                attributes: Boolean(options.attributes),
                childList: Boolean(options.childList),
                characterData: Boolean(options.characterData),
                subtree: Boolean(options.subtree),
                attributeOldValue: Boolean(options.attributeOldValue),
                characterDataOldValue: Boolean(options.characterDataOldValue)
              }
            };
            // Real MutationObserver.observe() called AGAIN on a node
            // already being watched by THIS SAME observer replaces that
            // registration's options, it does not add a second one --
            // previously this unconditionally pushed a new entry, so
            // observing the same target twice (a real, common defensive/
            // re-init pattern) meant __kotobaQueueMutation's dispatch loop
            // (below) iterated over TWO entries for one real mutation,
            // delivering duplicate MutationRecords for a single change.
            var existingIndex = -1;
            for (var e = 0; e < observer.targets.length; e++) {
              if (observer.targets[e].nodeId === nodeId) {
                existingIndex = e;
                break;
              }
            }
            if (existingIndex >= 0) {
              observer.targets[existingIndex] = entry;
            } else {
              observer.targets.push(entry);
            }
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
      /* globalThis.customElements was declared in webapi-surface's :window
         list (compat/webapi.cljc) but no code anywhere ever installed it --
         any real page script calling customElements.define()/.get() (an
         extremely common feature-detection/registration pattern) crashed
         the whole script tag with a ReferenceError, not a spec-shaped
         error. This is a registration-only implementation: define/get/
         whenDefined work for real, matching real DOM semantics for name
         validation and duplicate detection, but -- unlike the fully self-
         contained MutationObserver above -- it deliberately does NOT
         upgrade already-created elements or fire connectedCallback/
         disconnectedCallback/attributeChangedCallback on them, since this
         shim's elements are plain objects rather than instances of a real
         HTMLElement class hierarchy (no globalThis.HTMLElement exists to
         subclass), and building that is a much larger, separate change. */
      globalThis.__kotobaCustomElementDefinitions = {};
      globalThis.__kotobaCustomElementWhenDefinedCallbacks = {};
      function __kotobaValidCustomElementName(name) {
        // Mirrors browser.compat.webcomponent/valid-name? and
        // reserved-names (Clojure-side registry model, unused by this
        // shim) -- keep both in sync if either's rules change.
        if (typeof name !== 'string' || name.indexOf('-') < 0) return false;
        if (name.slice(0, 3).toLowerCase() === 'xml') return false;
        var reserved = {
          'annotation-xml': true, 'color-profile': true, 'font-face': true,
          'font-face-src': true, 'font-face-uri': true, 'font-face-format': true,
          'font-face-name': true, 'missing-glyph': true
        };
        return !Object.prototype.hasOwnProperty.call(reserved, name);
      }
      globalThis.customElements = {
        define: function(name, constructor, options) {
          var key = String(name);
          if (!__kotobaValidCustomElementName(key)) {
            throw new TypeError('customElements.define(): \"' + key + '\" is not a valid custom element name');
          }
          if (typeof constructor !== 'function') {
            throw new TypeError('customElements.define(): constructor must be a function');
          }
          if (Object.prototype.hasOwnProperty.call(globalThis.__kotobaCustomElementDefinitions, key)) {
            throw new TypeError('customElements.define(): a custom element with name \"' + key + '\" is already defined');
          }
          for (var existingName in globalThis.__kotobaCustomElementDefinitions) {
            if (Object.prototype.hasOwnProperty.call(globalThis.__kotobaCustomElementDefinitions, existingName) &&
                globalThis.__kotobaCustomElementDefinitions[existingName].constructor === constructor) {
              throw new TypeError('customElements.define(): this constructor has already been used to define \"' + existingName + '\"');
            }
          }
          var observedAttributes = constructor.observedAttributes ? constructor.observedAttributes.slice() : [];
          globalThis.__kotobaCustomElementDefinitions[key] = {
            name: key,
            constructor: constructor,
            observedAttributes: observedAttributes
          };
          var pending = globalThis.__kotobaCustomElementWhenDefinedCallbacks[key];
          if (pending) {
            delete globalThis.__kotobaCustomElementWhenDefinedCallbacks[key];
            for (var i = 0; i < pending.length; i++) pending[i]();
          }
        },
        get: function(name) {
          var entry = globalThis.__kotobaCustomElementDefinitions[String(name)];
          return entry ? entry.constructor : undefined;
        },
        whenDefined: function(name) {
          var key = String(name);
          var deferred = __kotobaMakeDeferred();
          if (Object.prototype.hasOwnProperty.call(globalThis.__kotobaCustomElementDefinitions, key)) {
            deferred.resolve(undefined);
          } else if (!__kotobaValidCustomElementName(key)) {
            deferred.reject(new TypeError('whenDefined(): \"' + key + '\" is not a valid custom element name'));
          } else {
            globalThis.__kotobaCustomElementWhenDefinedCallbacks[key] = globalThis.__kotobaCustomElementWhenDefinedCallbacks[key] || [];
            globalThis.__kotobaCustomElementWhenDefinedCallbacks[key].push(function() { deferred.resolve(undefined); });
          }
          return deferred.promise;
        }
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
        for (var i = 0; i < listeners.length; i++) {
          listeners[i].call(event.currentTarget, event);
          if (event.immediatePropagationStopped) break;
        }
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
      globalThis.getComputedStyle = function(el) {
        return __kotobaComputedStyle(el && el.__kotobaRef);
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
      function __kotobaFetchResponse(entry) {
        var status = (entry && entry.status != null) ? Number(entry.status) : 0;
        var body = (entry && entry.body != null) ? String(entry.body) : '';
        var headers = (entry && entry.headers) || {};
        var response = {
          ok: status >= 200 && status <= 299,
          status: status,
          statusText: (entry && entry.statusText) || '',
          url: (entry && entry.url) || ''
        };
        response.text = function() {
          var d = __kotobaMakeDeferred();
          d.resolve(body);
          return d.promise;
        };
        response.json = function() {
          return response.text().then(function(text) { return JSON.parse(text); });
        };
        response.headers = {
          get: function(name) {
            var key = String(name).toLowerCase();
            var keys = Object.keys(headers);
            for (var i = 0; i < keys.length; i++) {
              if (String(keys[i]).toLowerCase() === key) {
                var value = headers[keys[i]];
                return Array.isArray(value) ? value.join(', ') : String(value);
              }
            }
            return null;
          }
        };
        return response;
      }
      globalThis.fetch = function(url, request) {
        var fetchId = 'fetch-' + globalThis.__kotobaNextFetchId;
        globalThis.__kotobaNextFetchId = globalThis.__kotobaNextFetchId + 1;
        globalThis.__kotobaRequests.push({
          capability: 'net/fetch',
          'request/id': fetchId,
          url: String(url),
          request: request || {}
        });
        var deferred = __kotobaMakeDeferred();
        globalThis.__kotobaFetchPending[fetchId] = deferred;
        // Same synchronous, byte-for-byte-unchanged fabricated fields this
        // always returned before real fetch() delivery existed (a script
        // reading `.ok`/`.status`/`.capability` synchronously, without ever
        // calling `.then()`, sees exactly what it always has -- fabricated
        // mode never populates :net/fetch-responses, so `.then()`/`.catch()`
        // below simply never settle in that mode, mirroring un-echoed
        // WebSocket sends and un-replied Worker messages). `.then()`/
        // `.catch()` are the new, real, opt-in delivery surface -- see the
        // fetch delivery IIFE at the bottom of this shim and
        // quickjs-execution/fetch-snapshot.
        var result = { ok: true, status: 0, capability: 'net/fetch' };
        result.then = function(onFulfilled, onRejected) {
          return deferred.promise.then(onFulfilled, onRejected);
        };
        result.catch = function(onRejected) {
          return deferred.promise.then(undefined, onRejected);
        };
        return result;
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
        // Real spec: location.href always reflects the CURRENT document's
        // URL, exactly like document.URL's own live getter above (see
        // get URL() nearby) -- previously a plain, never-updated data
        // property fixed at 'about:blank' forever, and none of pathname/
        // search/hash/host/protocol/origin existed at all. A getter here,
        // not a value captured once at init time, so it stays correct
        // across every navigation without needing this whole object
        // rebuilt. location.href = someUrl (a real, extremely common
        // navigation idiom, more common than calling .assign() directly)
        // was ALSO a silent no-op before -- a plain property assignment
        // with no navigation side effect at all -- now routed through the
        // same setter as .assign().
        get href() {
          return globalThis.__kotobaSnapshot && globalThis.__kotobaSnapshot.url != null
            ? String(globalThis.__kotobaSnapshot.url)
            : 'about:blank';
        },
        set href(url) {
          this.assign(url);
        },
        get protocol() {
          return __kotobaSplitUrl(this.href).protocol;
        },
        get host() {
          return __kotobaSplitUrl(this.href).authority;
        },
        get hostname() {
          return __kotobaSplitUrl(this.href).authority.split(':')[0] || '';
        },
        get port() {
          var hostParts = __kotobaSplitUrl(this.href).authority.split(':');
          return hostParts.length > 1 ? hostParts.slice(1).join(':') : '';
        },
        get pathname() {
          return __kotobaSplitUrl(this.href).pathname || '/';
        },
        get search() {
          return __kotobaSplitUrl(this.href).search;
        },
        get hash() {
          return __kotobaSplitUrl(this.href).hash;
        },
        get origin() {
          var parts = __kotobaSplitUrl(this.href);
          return parts.authority ? parts.protocol + '//' + parts.authority : 'null';
        },
        assign: function(url) {
          globalThis.__kotobaRequests.push({
            capability: 'location/assign',
            url: String(url)
          });
        },
        replace: function(url) {
          globalThis.__kotobaRequests.push({
            capability: 'location/replace',
            url: String(url)
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
        // Real spec: overwrite the FIRST matching pair's value in place and
        // drop any others, preserving that pair's position relative to
        // other keys -- delete-then-append (the previous implementation)
        // instead removed every same-name pair and re-appended a fresh one
        // at the end, silently reordering params whenever another key
        // interleaved with the one being set (e.g. 'a=1&b=2&a=3'.set('a',
        // '9') produced 'b=2&a=9' instead of the real 'a=9&b=2').
        name = String(name);
        value = String(value);
        var found = false;
        var next = [];
        for (var i = 0; i < this.__pairs.length; i++) {
          var pair = this.__pairs[i];
          if (pair[0] === name) {
            if (!found) {
              next.push([name, value]);
              found = true;
            }
          } else {
            next.push(pair);
          }
        }
        if (!found) next.push([name, value]);
        this.__pairs = next;
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
        },
        get length() {
          var snapshot = globalThis.__kotobaStorageSnapshot || {};
          return Object.keys(snapshot).length;
        },
        key: function(index) {
          var snapshot = globalThis.__kotobaStorageSnapshot || {};
          var keys = Object.keys(snapshot).sort();
          var i = Number(index);
          return i >= 0 && i < keys.length ? keys[i] : null;
        },
        clear: function() {
          // No dedicated storage/clear capability exists -- composed from
          // the already-real, per-key storage/delete op instead (the same
          // reuse-an-existing-primitive-over-adding-a-new-capability
          // posture other composed methods in this file already follow),
          // matching removeItem's own established behavior of never
          // optimistically mutating __kotobaStorageSnapshot locally --
          // the snapshot only ever reflects the host's own post-commit
          // truth, re-injected fresh before each script evaluates.
          var snapshot = globalThis.__kotobaStorageSnapshot || {};
          var keys = Object.keys(snapshot);
          for (var i = 0; i < keys.length; i++) {
            globalThis.__kotobaRequests.push({
              capability: 'storage/delete',
              'storage/key': keys[i]
            });
          }
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
        length: (globalThis.__kotobaHistoryLength || 0),
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
        /* Real navigator.clipboard.readText()/writeText() are
           Promise-returning (Promise<string>/Promise<void> respectively --
           see MDN). Both this permission decision AND the clipboard text are
           already known host-side, synchronously, before this script even
           starts running (globalThis.__kotobaClipboardSnapshot -- see
           quickjs-execution/clipboard-snapshot, the SAME permission-gated,
           host-computed-before-eval pattern navigator.geolocation.
           getCurrentPosition's __kotobaGeolocationSnapshot already
           established), so -- exactly like that snapshot's success/error
           callback dispatch -- there is nothing left to wait for: the
           returned deferred is resolved/rejected IMMEDIATELY, synchronously,
           in this same call, not left pending for some later delivery
           mechanism the way fetch()'s thenable is. The request is still
           queued either way, so a denied call still shows up in the real
           host-side audit trail (:capability/results), mirroring
           getCurrentPosition's request-still-queued-even-when-denied
           discipline. */
        readText: function() {
          globalThis.__kotobaRequests.push({
            capability: 'clipboard/read',
            'clipboard/format': 'text'
          });
          var snapshot = globalThis.__kotobaClipboardSnapshot || {};
          var read = snapshot.read || { granted: true };
          var deferred = __kotobaMakeDeferred();
          if (read.granted) {
            deferred.resolve(snapshot.text != null ? String(snapshot.text) : '');
          } else {
            deferred.reject({
              name: 'NotAllowedError',
              message: read.error || 'Read permission denied'
            });
          }
          return deferred.promise;
        },
        writeText: function(text) {
          var snapshot = globalThis.__kotobaClipboardSnapshot || {};
          var write = snapshot.write || { granted: true };
          var deferred = __kotobaMakeDeferred();
          globalThis.__kotobaRequests.push({
            capability: 'clipboard/write',
            'clipboard/format': 'text',
            text: String(text)
          });
          if (write.granted) {
            deferred.resolve(undefined);
          } else {
            deferred.reject({
              name: 'NotAllowedError',
              message: write.error || 'Write permission denied'
            });
          }
          return deferred.promise;
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
        /* Real getUserMedia() returns Promise<MediaStream> (MDN). This
           engine has no real camera/microphone capture pipeline at all --
           unlike clipboard/geolocation, there is no real device data to
           synchronously know host-side before the script runs, and (unlike
           Bug 2's clipboard fix) media permission-gating stays exactly where
           it already lived pre-fix: apply-capability's :media/capture case
           (media-capture-result), processed post-hoc for the host-side audit
           trail only -- mirroring WebSocket/Worker's existing
           fire-and-forget-from-the-calling-script's-perspective precedent,
           not geolocation's synchronous success/error dispatch. So this
           always RESOLVES (never rejects) with a placeholder, clearly
           simulated MediaStream-shaped object -- a policy-correct
           SIMULATION (this repo never claims real media capture), just
           correctly Promise-shaped now instead of a bare object a real
           `.then()`/`await` caller would crash on. */
        getUserMedia: function(constraints) {
          globalThis.__kotobaRequests.push({
            capability: 'media/capture',
            'media/op': 'get-user-media',
            'media/constraints': constraints || {}
          });
          var deferred = __kotobaMakeDeferred();
          deferred.resolve({
            capability: 'media/capture',
            simulated: true,
            getTracks: function() { return []; },
            getVideoTracks: function() { return []; },
            getAudioTracks: function() { return []; }
          });
          return deferred.promise;
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
      globalThis.Notification.permission = (globalThis.__kotobaNotificationSnapshot && globalThis.__kotobaNotificationSnapshot.permission) || 'default';
      globalThis.Notification.requestPermission = function(callback) {
        globalThis.__kotobaRequests.push({
          capability: 'notification/request-permission',
          'notification/op': 'request-permission'
        });
        var permission = (globalThis.__kotobaNotificationSnapshot && globalThis.__kotobaNotificationSnapshot.permission) || 'default';
        if (typeof callback === 'function') callback(permission);
        return permission;
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
      function __kotobaBase64Chars() {
        return 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
      }
      function __kotobaIsAsciiWhitespace(ch) {
        return ch === ' ' || ch === '\\t' || ch === '\\n' || ch === '\\f' || ch === '\\r';
      }
      globalThis.btoa = function(data) {
        // Real btoa() encodes a binary (Latin1) string -- every char code
        // must be 0-255, else a real InvalidCharacterError DOMException is
        // thrown (this engine has no DOMException type at all, so a plain
        // Error is thrown instead, an honest simplification matching this
        // file's own established posture elsewhere, e.g. MutationObserver's
        // plain TypeError). Deliberately avoids regex entirely (unlike a
        // naive implementation) to sidestep this file's own known Clojure-
        // string-escaping hazard for JS regex literals.
        var s = String(data);
        var chars = __kotobaBase64Chars();
        for (var i = 0; i < s.length; i++) {
          if (s.charCodeAt(i) > 255) {
            throw new Error('InvalidCharacterError: btoa() argument must be a Latin1 (binary) string');
          }
        }
        var result = '';
        for (var j = 0; j < s.length; j += 3) {
          var hasB1 = j + 1 < s.length;
          var hasB2 = j + 2 < s.length;
          var b0 = s.charCodeAt(j);
          var b1 = hasB1 ? s.charCodeAt(j + 1) : 0;
          var b2 = hasB2 ? s.charCodeAt(j + 2) : 0;
          var triplet = (b0 << 16) | (b1 << 8) | b2;
          result += chars.charAt((triplet >> 18) & 63);
          result += chars.charAt((triplet >> 12) & 63);
          result += hasB1 ? chars.charAt((triplet >> 6) & 63) : '=';
          result += hasB2 ? chars.charAt(triplet & 63) : '=';
        }
        return result;
      };
      globalThis.atob = function(data) {
        // Real atob() first strips all ASCII whitespace, then requires the
        // remainder to be legal base64 (only the 64-symbol alphabet plus
        // trailing '=' padding, at most 2 padding characters, and a real
        // length-mod-4 of 1 is never legal) -- any violation throws a real
        // InvalidCharacterError DOMException, mirrored here as a plain
        // Error for the same no-DOMException-type reason btoa() above is.
        var raw = String(data);
        var chars = __kotobaBase64Chars();
        var s = '';
        for (var i = 0; i < raw.length; i++) {
          if (!__kotobaIsAsciiWhitespace(raw.charAt(i))) s += raw.charAt(i);
        }
        var paddingStart = s.length;
        while (paddingStart > 0 && s.charAt(paddingStart - 1) === '=') paddingStart--;
        if (s.length - paddingStart > 2 || paddingStart % 4 === 1) {
          throw new Error('InvalidCharacterError: atob() argument is not correctly encoded');
        }
        for (var k = 0; k < paddingStart; k++) {
          if (chars.indexOf(s.charAt(k)) === -1) {
            throw new Error('InvalidCharacterError: atob() argument is not correctly encoded');
          }
        }
        var result = '';
        var buffer = 0;
        var bits = 0;
        for (var m = 0; m < paddingStart; m++) {
          buffer = (buffer << 6) | chars.indexOf(s.charAt(m));
          bits += 6;
          if (bits >= 8) {
            bits -= 8;
            result += String.fromCharCode((buffer >> bits) & 255);
          }
        }
        return result;
      };
      globalThis.Worker = function(url, options) {
        var workerId = 'worker-' + globalThis.__kotobaNextWorkerId;
        globalThis.__kotobaNextWorkerId = globalThis.__kotobaNextWorkerId + 1;
        this.__kotobaWorkerId = workerId;
        this.onmessage = null;
        this.onerror = null;
        globalThis.__kotobaWorkers[workerId] = this;
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
        delete globalThis.__kotobaWorkers[this.__kotobaWorkerId];
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
      })();
      (function() {
        var __kotobaWorkerSnap = globalThis.__kotobaWorkerSnapshot || {};
        var __kotobaWorkerRegistry = globalThis.__kotobaWorkers || {};
        var __kwoIds = Object.keys(__kotobaWorkerSnap);
        for (var __kwoi = 0; __kwoi < __kwoIds.length; __kwoi++) {
          var __kwoId = __kwoIds[__kwoi];
          var __kwoEntry = __kotobaWorkerSnap[__kwoId];
          var __kwoWorker = __kotobaWorkerRegistry[__kwoId];
          if (!__kwoWorker || !__kwoEntry) continue;
          var __kwoMessages = __kwoEntry.messages || [];
          for (var __kwoj = 0; __kwoj < __kwoMessages.length; __kwoj++) {
            if (typeof __kwoWorker.onmessage === 'function') {
              __kwoWorker.onmessage({ data: __kwoMessages[__kwoj] });
            }
          }
        }
      })();
      (function() {
        var __kotobaFetchSnap = globalThis.__kotobaFetchSnapshot || {};
        var __kotobaFetchPendingReg = globalThis.__kotobaFetchPending || {};
        var __kfIds = Object.keys(__kotobaFetchSnap);
        for (var __kfi = 0; __kfi < __kfIds.length; __kfi++) {
          var __kfId = __kfIds[__kfi];
          var __kfEntry = __kotobaFetchSnap[__kfId];
          var __kfDeferred = __kotobaFetchPendingReg[__kfId];
          if (!__kfDeferred || !__kfEntry) continue;
          delete __kotobaFetchPendingReg[__kfId];
          if (__kfEntry.error) {
            __kfDeferred.reject(new Error(String(__kfEntry.error)));
          } else {
            __kfDeferred.resolve(__kotobaFetchResponse(__kfEntry));
          }
        }
      })();")

(def worker-global-scope-source
  "JS source installed into a Worker's OWN, real, separate QuickJS context
  (see `create-worker-context`) instead of `webapi-shim-source` -- a
  minimal `DedicatedWorkerGlobalScope` shim, NOT the full page/window
  webapi shim: a real Worker's global scope has `self`/`postMessage`/
  `onmessage` but no `document`/`window`/DOM at all, so this deliberately
  does not reuse `webapi-shim-source`.

  `self` is `globalThis` itself (the worker context's own global object,
  matching a real Worker's `self === globalThis`). `postMessage` pushes
  onto `__kotobaWorkerOutbox`, a plain queue local to THIS context --
  there is no `__kotobaRequests` capability-request queue in a worker
  context at all (a worker's `postMessage` is not a host capability
  request the way `Worker.prototype.postMessage` on the MAIN thread is;
  it is real data the host drains straight out of this context, see
  `dump-worker-outbox`). `onmessage`/`onerror` are plain properties a
  worker script assigns a real function to, exactly like a real
  `DedicatedWorkerGlobalScope`. `__kotobaWorkerDeliver` is the host's
  entry point (see `context-worker-deliver-result`) for invoking a REAL,
  still-registered `onmessage` inside this real context, synchronously,
  whenever the host decides to deliver a message that arrived via
  `Worker.prototype.postMessage` on the main thread -- mirroring
  `webapi-shim-source`'s `__kotobaRunTask` entry point for timers/
  microtasks. `console.*` are no-op stubs (a worker's console output is
  not captured/proven anywhere in this repo -- a documented, accepted
  limitation, not a correctness claim)."
  "globalThis.self = globalThis;
      globalThis.__kotobaWorkerOutbox = [];
      globalThis.onmessage = null;
      globalThis.onerror = null;
      globalThis.postMessage = function(data) {
        globalThis.__kotobaWorkerOutbox.push(data);
      };
      globalThis.close = function() {};
      globalThis.console = {
        log: function() {},
        info: function() {},
        warn: function() {},
        error: function() {},
        debug: function() {}
      };
      globalThis.__kotobaWorkerDeliver = function(data) {
        if (typeof globalThis.onmessage === 'function') {
          globalThis.onmessage({ data: data });
        }
      };")

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
   (defn- enum-value-key?
     "True when `k`'s value is always an enum-like discriminator token (a
     small fixed set of known strings, e.g. `:query-selector`/`:get`/`:exit`)
     that MUST be keywordized for `capability-request-error`'s `=`/`case`
     shape checks to see the same values a hand-rolled `:engine` test-double's
     Clojure map already uses directly -- never free-form user/network data
     (a URL, a cookie VALUE, clipboard TEXT, etc.) that has to stay a string.

     `:capability` and `:dom/query` are enum-like but don't follow the naming
     convention below, so they're listed explicitly. Every OTHER such field in
     this namespace's webapi shim is named `<ns>/op` or `<ns>/format` --
     `dom/op`, `clipboard/format`, `cookie/op`, `location/op`,
     `geolocation/op`, `notification/op`, `fullscreen/op`, `media/op`,
     `crypto/op` (verified by inspecting every `'<ns>/op'`/`'<ns>/format'`
     field `webapi-shim-source` pushes -- none of them ever carries free
     text), so keying off `(name k)` being exactly `op` or `format` covers all
     of them AND any future one, without this set having to grow by one
     explicit entry per bug report the way `:clipboard/format` first did (see
     that fix's `quickjs_clipboard_media_promise_smoke_test.cljs`) before the
     remaining `cookie/op`/`location/op`/`geolocation/op`/`notification/op`/
     `fullscreen/op`/`media/op`/`crypto/op` instances of the identical bug
     class were found and closed together (see
     `quickjs_capability_op_field_keywordization_smoke_test.cljs`)."
     [k]
     (or (contains? #{:capability :dom/query} k)
         (contains? #{"op" "format"} (name k)))))

#?(:cljs
   (defn- keywordize-map-keys
     "Convert every key of map `m` to a keyword, leaving each value
     untouched (i.e. only ONE level deep -- a nested map VALUE, if any,
     stays exactly as `js->clj` produced it). Shared by every
     `nested-keywordize-key?` field below so they don't each hand-roll the
     same `(into {} (map ...))` shape."
     [m]
     (into {} (map (fn [[k v]] [(keyword k) v])) m)))

#?(:cljs
   (defn- nested-keywordize-key?
     "True when `k`'s value, if itself a map, is always a webapi-shim-
     constructed map with a small, fixed, spec-bounded key set whose OWN
     keys some `apply-capability`/`quickjs-execution` consumer destructures
     by KEYWORD -- `:request`'s RequestInit-shaped map (`method`/`headers`/
     `body`/...), `:event`'s internal `{event/type, default-prevented?}`
     map, and `:media/constraints`'s MediaStreamConstraints-shaped
     `{video, audio}` map that `quickjs-execution/media-required-
     capabilities`'s `(:video constraints)`/`(:audio constraints)` keyword
     lookups need keywordized the exact same way `:request`/`:event`'s
     consumers already did -- never a map whose OWN keys are free-form data
     the page script itself chose.

     This deliberately does NOT generalize to 'keywordize every nested
     map's keys, always': verified (by inspecting every nested-map-valued
     field `webapi-shim-source` pushes) counter-examples exist in this exact
     namespace -- `history.pushState`/`replaceState`'s `:state` and
     `Worker`/`BroadcastChannel` `postMessage`'s `:message` are passed
     through byte-for-byte (`capability-request-error`'s `:history/push-
     state`/`:history/replace-state`/`:worker/post-message`/`:broadcast/
     post-message` cases only ever `contains?`-check them, never destructure
     a nested key), because their whole POINT is to let the page author
     stash an arbitrary JSON-shaped payload of THEIR OWN choosing. Silently
     retyping those maps' keys to keywords would be a correctness bug in
     the other direction: it would not round-trip a page's own `pushState({
     myKey: 1})`/`postMessage({myKey: 1})` payload keys byte-for-byte. So,
     unlike `enum-value-key?`'s `/op`/`/format` suffix rule (verified safe
     for EVERY such field, present and future), this stays an explicit,
     verified allow-list rather than a naming-convention rule -- there is no
     naming convention in this webapi shim that distinguishes a fixed-shape
     internal map from an arbitrary user payload, so inventing one here
     would risk silently keywordizing a future arbitrary-payload field."
     [k]
     (contains? #{:request :event :media/constraints} k)))

#?(:cljs
(defn- normalize-request [request]
     (let [request (js->clj request)]
       (into {}
             (map (fn [[k v]]
                    (let [k (request-keyword k)]
                      [k (cond
                           (enum-value-key? k)
                           (keyword v)

                           (and (nested-keywordize-key? k) (map? v))
                           (keywordize-map-keys v)

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
   (defn- cookie-snapshot-source
     "JS source that installs the real, current cookie header string for this
     page's URL (`quickjs-execution/cookie-snapshot`, computed host-side from
     the persisted `:net/context` cookie store) as
     `globalThis.__kotobaCookieSnapshot`, so the webapi shim's real
     `document.cookie` getter can read it synchronously instead of always
     returning `''`. Mirrors `storage-snapshot-source`'s own bare-value
     treatment exactly (a plain string, no permission-decision wrapper --
     unlike clipboard/geolocation/notification, real cookies apply same-
     origin automatically with no permission-prompt concept)."
     [cookie]
     (str "globalThis.__kotobaCookieSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or cookie ""))))
          ";")))

#?(:cljs
   (defn- clipboard-snapshot-source
     "JS source that installs the real, current clipboard permission
     decisions + text (`quickjs-execution/clipboard-snapshot`, computed
     host-side from the persisted `:clipboard` atom -- see
     `browser.compat.quickjs-runner`'s `persistent-execution-keys` -- and the
     SAME `permission-decision-for` gate `apply-capability`'s
     `:clipboard/read`/`:clipboard/write` cases use) as
     `globalThis.__kotobaClipboardSnapshot`, so the webapi shim's
     `navigator.clipboard.readText`/`writeText` can synchronously
     resolve/reject the promise each returns with the REAL permission
     decision and clipboard text instead of always granting unconditionally.
     Mirrors `snapshot-source`/`storage-snapshot-source`/
     `geolocation-snapshot-source`, but -- like geolocation's -- the shape is
     necessarily a bit richer than storage's raw value: clipboard is
     permission-gated (independently for read and write), so this is ONE
     coherent map carrying the permission decisions and the text together,
     not a bolted-on second snapshot. Defaults to an all-granted, empty-text
     snapshot when no real snapshot was computed (e.g. a caller invoking this
     engine below `quickjs-execution/evaluate!`/`load-module!`, which always
     supplies a real one)."
     [clipboard]
     (str "globalThis.__kotobaClipboardSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or clipboard
                                                     {:text ""
                                                      :read {:granted true}
                                                      :write {:granted true}}))))
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
   (defn- worker-snapshot-source
     "JS source that installs the real, current per-Worker inbound-message
     snapshot (`quickjs-execution/worker-snapshot`, computed host-side from
     messages a worker's own real, separate QuickJS context genuinely
     produced -- see that fn and `context-worker-deliver-result`) as
     `globalThis.__kotobaWorkerSnapshot`, keyed by Worker id. Mirrors
     `websocket-snapshot-source` exactly: installing this snapshot is not
     enough on its own, the webapi shim below also actively DELIVERS it
     (see the bottom of `webapi-shim-source`) -- for each id present here
     with a still-registered live `Worker` instance
     (`globalThis.__kotobaWorkers`, persisted across script tags within a
     page the same way `globalThis.__kotobaWebSockets` already is), it
     invokes that instance's `onmessage` SYNCHRONOUSLY once per buffered
     message, in arrival order, before the script's own source runs."
     [worker-snapshot]
     (str "globalThis.__kotobaWorkerSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or worker-snapshot {}))))
          ";")))

#?(:cljs
   (defn- fetch-snapshot-source
     "JS source that installs the real, current per-fetch-request response
     snapshot (`quickjs-execution/fetch-snapshot`, computed host-side from a
     real `:fetch-fn`'s already-completed, synchronous HTTP call -- see that
     fn) as `globalThis.__kotobaFetchSnapshot`, keyed by the fetch call's
     own request id. Mirrors `worker-snapshot-source`: installing this
     snapshot is not enough on its own, the webapi shim below also actively
     DELIVERS it (see the fetch delivery IIFE at the bottom of
     `webapi-shim-source`) -- for each id present here with a still-pending
     entry in `globalThis.__kotobaFetchPending` (persisted across script
     tags within a page the same way `globalThis.__kotobaWebSockets`/
     `globalThis.__kotobaWorkers` already are), it resolves (or, for a
     genuine network-level failure, rejects) that `fetch()` call's pending
     `.then()` chain SYNCHRONOUSLY, before the script's own source runs."
     [fetch-snapshot]
     (str "globalThis.__kotobaFetchSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or fetch-snapshot {}))))
          ";")))

#?(:cljs
   (defn- notification-permission-snapshot-source
     "JS source that installs the real, current notification permission
     decision (`quickjs-execution/notification-permission-snapshot`, computed
     host-side from the active profile's permission grants -- and the SAME
     `permission-decision-for` gate `apply-capability`'s
     `:notification/request-permission`/`:notification/show` cases use) as
     `globalThis.__kotobaNotificationSnapshot`, so the webapi shim's
     `Notification.permission`/`Notification.requestPermission` can
     synchronously reflect/return the REAL permission decision instead of the
     hardcoded `'default'` literal. Mirrors `geolocation-snapshot-source`,
     but simpler -- there is only ever a single permission string here, no
     position/error pair, since `notification-permission-snapshot` never has
     anything else to report. Defaults to `{:permission \"default\"}` when no
     real snapshot was computed (e.g. a caller invoking this engine below
     `quickjs-execution/evaluate!`/`load-module!`, which always supplies a
     real one), mirroring a real, unconfigured `Notification.permission`'s
     actual default value."
     [notification-snapshot]
     (str "globalThis.__kotobaNotificationSnapshot = "
          (js/JSON.stringify (clj->js (jsonable (or notification-snapshot
                                                     {:permission "default"}))))
          ";")))

#?(:cljs
   (defn- history-length-snapshot-source
     "JS source that installs the real session-level navigation depth
     (`quickjs-execution/history-length-snapshot`, computed host-side from
     `browser.compat.quickjs-runner/run-script!`'s real `:browser.session/
     navigation` entry count -- see that fn) as
     `globalThis.__kotobaHistoryLength`, so the webapi shim's
     `globalThis.history` object can seed its `length` property's STARTING
     value from it instead of the hardcoded `0` literal, the same way
     `notification-permission-snapshot-source` seeds `Notification.
     permission` from a host-computed value instead of a hardcoded default.
     Unlike that one, there is no permission gate here (navigation depth
     isn't a permission-gated capability) and no map wrapper -- just a plain,
     JSON-safe integer. Defaults to `0` when no real snapshot was computed
     (e.g. a caller invoking this engine below `quickjs-execution/evaluate!`/
     `load-module!`, which always supplies a real one), mirroring a fresh,
     never-navigated real browsing context's actual `history.length`.

     Only ever OBSERVED at the moment `globalThis.history` is first
     constructed (`globalThis.history || {...}` in the webapi shim -- the
     object persists across every later `<script>` tag within the same page
     load, exactly like `globalThis.__kotobaWebSockets`/`__kotobaWorkers`
     do), so re-installing this global before a LATER script tag's eval is
     harmless: it cannot retroactively change `history.length` once the
     object already exists, only `pushState`/`replaceState`/`go` (which this
     change deliberately leaves untouched -- see the webapi shim's `history`
     shim) can move it from there. This engine's own sandboxed `history/
     push-state|replace-state|traverse` capability model
     (`history-push-state-result` et al., `:history/entries`/`:history/index`
     in `quickjs-execution/new-state`) tracks a SEPARATE, VM-local notion of
     history and is never reconciled with this REAL, session-level
     navigation depth -- see `history-length-snapshot`'s docstring for that
     known, deliberately out-of-scope gap."
     [history-length-snapshot]
     (str "globalThis.__kotobaHistoryLength = "
          (js/JSON.stringify (clj->js (jsonable (or history-length-snapshot 0))))
          ";")))

#?(:cljs
   (defn- dump-result
     ([module source url modules]
      (dump-result module source url modules nil false nil nil nil nil nil nil nil nil nil nil))
     ([module source url modules module-provider module?]
      (dump-result module source url modules module-provider module? nil nil nil nil nil nil nil nil nil nil))
     ([module source url modules module-provider module? document-snapshot storage-snapshot clipboard-snapshot geolocation-snapshot websocket-snapshot worker-snapshot fetch-snapshot notification-snapshot history-length-snapshot cookie-snapshot]
      (let [^js runtime (.newRuntime ^js module #js {:moduleLoader (module-loader modules module-provider)})
            ^js vm (.newContext runtime)
            _ (eval-dispose! vm (snapshot-source document-snapshot) "kotoba://quickjs/document-snapshot.js")
            _ (eval-dispose! vm (storage-snapshot-source storage-snapshot) "kotoba://quickjs/storage-snapshot.js")
            _ (eval-dispose! vm (clipboard-snapshot-source clipboard-snapshot) "kotoba://quickjs/clipboard-snapshot.js")
            _ (eval-dispose! vm (geolocation-snapshot-source geolocation-snapshot) "kotoba://quickjs/geolocation-snapshot.js")
            _ (eval-dispose! vm (notification-permission-snapshot-source notification-snapshot) "kotoba://quickjs/notification-snapshot.js")
            _ (eval-dispose! vm (websocket-snapshot-source websocket-snapshot) "kotoba://quickjs/websocket-snapshot.js")
            _ (eval-dispose! vm (worker-snapshot-source worker-snapshot) "kotoba://quickjs/worker-snapshot.js")
            _ (eval-dispose! vm (fetch-snapshot-source fetch-snapshot) "kotoba://quickjs/fetch-snapshot.js")
            _ (eval-dispose! vm (history-length-snapshot-source history-length-snapshot) "kotoba://quickjs/history-length-snapshot.js")
            _ (eval-dispose! vm (cookie-snapshot-source cookie-snapshot) "kotoba://quickjs/cookie-snapshot.js")
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
   (defn- install-document-shim! [vm document-snapshot storage-snapshot clipboard-snapshot geolocation-snapshot websocket-snapshot worker-snapshot fetch-snapshot notification-snapshot history-length-snapshot cookie-snapshot]
     (eval-dispose! vm (snapshot-source document-snapshot) "kotoba://quickjs/document-snapshot.js")
     (eval-dispose! vm (storage-snapshot-source storage-snapshot) "kotoba://quickjs/storage-snapshot.js")
     (eval-dispose! vm (clipboard-snapshot-source clipboard-snapshot) "kotoba://quickjs/clipboard-snapshot.js")
     (eval-dispose! vm (geolocation-snapshot-source geolocation-snapshot) "kotoba://quickjs/geolocation-snapshot.js")
     (eval-dispose! vm (notification-permission-snapshot-source notification-snapshot) "kotoba://quickjs/notification-snapshot.js")
     (eval-dispose! vm (websocket-snapshot-source websocket-snapshot) "kotoba://quickjs/websocket-snapshot.js")
     (eval-dispose! vm (worker-snapshot-source worker-snapshot) "kotoba://quickjs/worker-snapshot.js")
     (eval-dispose! vm (fetch-snapshot-source fetch-snapshot) "kotoba://quickjs/fetch-snapshot.js")
     (eval-dispose! vm (history-length-snapshot-source history-length-snapshot) "kotoba://quickjs/history-length-snapshot.js")
     (eval-dispose! vm (cookie-snapshot-source cookie-snapshot) "kotoba://quickjs/cookie-snapshot.js")
     (eval-dispose! vm webapi-shim-source "kotoba://quickjs/webapi-shim.js")))

#?(:cljs
   (defn- context-eval-result [context source url module? document-snapshot storage-snapshot clipboard-snapshot geolocation-snapshot websocket-snapshot worker-snapshot fetch-snapshot notification-snapshot history-length-snapshot cookie-snapshot]
     (let [vm (:vm context)
           _ (install-document-shim! vm document-snapshot storage-snapshot clipboard-snapshot geolocation-snapshot websocket-snapshot worker-snapshot fetch-snapshot notification-snapshot history-length-snapshot cookie-snapshot)
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

;; ---------------------------------------------------------------------
;; Real Worker execution: a SECOND, independent QuickJS context, alongside
;; the page's main context, genuinely running a fetched worker script.
;;
;; `create-context`/`ensure-context!` above already prove `module` (one
;; compiled WASM module instance) can back any number of independent
;; `{:runtime :vm}` pairs -- the page's main context is just the first,
;; page-lifetime-cached one. A Worker is a SECOND such pair, created on
;; `:worker/create` and kept alive (not disposed) across script tags until
;; a real `:worker/terminate`, so the SAME worker context -- and its
;; script's real, still-registered `self.onmessage` -- observes every
;; `postMessage` sent to it over its whole lifetime, the same way the
;; page's main context persists across `<script>` tags.
;; ---------------------------------------------------------------------

#?(:cljs
   (defn- create-worker-context
     "Create a real, independent QuickJS context (its own `newRuntime` +
     `newContext` -- NOT the page's `context-atom`) sharing the already-
     compiled `module`, install `worker-global-scope-source` (a minimal
     `self`/`postMessage`/`onmessage` shim, NOT the page's DOM/window
     `webapi-shim-source` -- a worker has no `document`/`window`), then
     REALLY EVALUATE `source` (the worker script's real, host-fetched
     bytes) in it. No `moduleLoader` is installed here: `importScripts`/
     module workers are not supported by this first pass (see this
     namespace's `worker-fn` docstring for the full list of accepted
     limitations)."
     [module source]
     (let [^js runtime (.newRuntime ^js module)
           ^js vm (.newContext runtime)]
       (eval-dispose! vm worker-global-scope-source "kotoba://quickjs/worker-global-scope.js")
       (let [init-result (eval-result vm source "kotoba://quickjs/worker.js" false)]
         {:runtime runtime
          :vm vm
          :init-result init-result}))))

#?(:cljs
   (defn- dump-worker-outbox
     "Pop every real value the worker context's script has genuinely queued
     via `self.postMessage` so far (see `worker-global-scope-source`),
     draining `globalThis.__kotobaWorkerOutbox` inside `vm` the same way
     `dump-requests` drains the page context's `__kotobaRequests`."
     [vm]
     (let [^js result (.evalCode ^js vm
                                  "(function(){ var m = globalThis.__kotobaWorkerOutbox || []; globalThis.__kotobaWorkerOutbox = []; return m; })()"
                                  "kotoba://quickjs/worker-outbox.js")]
       (try
         (if (.-error result)
           []
           (vec (.dump ^js vm (.-value result))))
         (finally
           (when (.-error result)
             (.dispose ^js (.-error result)))
           (when (.-value result)
             (.dispose ^js (.-value result))))))))

#?(:cljs
   (defn- context-worker-deliver-result
     "Deliver `data` (a real message sent via `Worker.prototype.postMessage`
     on the MAIN thread) into the worker's own real context by invoking
     `__kotobaWorkerDeliver` -- this is a REAL, synchronous eval into a
     SECOND, already-existing, currently-idle context (not a running
     script's context -- see `worker-fn`'s docstring for why this can
     happen immediately, unlike WebSocket's real inbound data), so if the
     worker's script's `self.onmessage` calls `self.postMessage(...)`
     synchronously in response (the common case), that reply is already
     sitting in `__kotobaWorkerOutbox` by the time this returns. Mirrors
     `context-run-task-result`'s `__kotobaRunTask(...)` invocation shape."
     [context data]
     (let [vm (:vm context)
           source (str "__kotobaWorkerDeliver("
                       (js/JSON.stringify (clj->js (jsonable data)))
                       ");")]
       (eval-dispose! vm source "kotoba://quickjs/worker-deliver.js")
       (dump-worker-outbox vm))))

#?(:cljs
   (defn- worker-runtime-fn
     "Return a single dispatch function -- the shape
     `browser.compat.quickjs-execution`'s `:worker-fn` state key expects,
     mirroring `browser.net.websocket/websocket-fn`'s `{:op ...}` shape --
     backed by REAL, independent QuickJS contexts sharing `module` (the
     same compiled WASM module instance the page's own context uses).
     `worker-registry` is a page-lifetime atom (`{id context}`) this
     engine's `:dispose` uses to clean up any worker contexts still alive
     when the whole engine (and its page) goes away -- callers never see
     or use it directly, they only ever get `context` back wrapped inside
     an opaque `:handle` map.

     `{:op :create :url ... :source ...}` -- `source` is the REAL script
     text the host already fetched via `:fetch-fn`
     (`quickjs-execution/worker-create-result`); creates a real second
     context and evaluates `source` in it for real (see
     `create-worker-context`), draining whatever the script's OWN
     synchronous top-level execution already `postMessage`d (e.g. a worker
     that posts a ready message immediately on load). Returns `{:ok? true
     :handle {...} :messages [...]}` or, if the host eval itself throws
     (as opposed to a normal in-VM JS exception, which this does not treat
     as a creation failure -- see the docstring note below),
     `{:ok? false :error :worker/eval-failed :error/message ...}`.

     `{:op :post-message :handle ... :data ...}` -- delivers `data` into
     the worker's real, still-registered `self.onmessage` SYNCHRONOUSLY
     (see `context-worker-deliver-result`) and returns any real reply
     messages the worker's script queued in response: `{:ok? true
     :messages [...]}`.

     `{:op :terminate :handle ...}` -- disposes the worker's real context
     (`dispose-context!`) for real, freeing its WASM memory, and forgets
     it in `worker-registry`. Returns `{:ok? true}`.

     Known, accepted limitations of this first pass (documented, not
     silently missing): a worker script that throws synchronously at its
     own top level does not surface a real `worker.onerror` on the main
     thread (the context is still created -- a real worker in this state
     also 'exists but is broken'; this repo just does not yet propagate
     the error). No `importScripts`/module workers. No real OS-thread
     parallelism -- see this namespace's `engine!` docstring and
     `quickjs-execution/worker-snapshot` for what IS genuinely real here."
     [module worker-registry]
     (fn real-worker-fn [{:keys [op] :as request}]
       (case op
         :create
         (try
           (let [context (create-worker-context module (:source request))
                 id (str (count @worker-registry) "-" (random-uuid))]
             (swap! worker-registry assoc id context)
             {:ok? true
              :handle {:worker/registry-id id :context context}
              :messages (dump-worker-outbox (:vm context))})
           (catch :default e
             {:ok? false :error :worker/eval-failed :error/message (str (.-message e))}))

         :post-message
         (try
           {:ok? true
            :messages (context-worker-deliver-result (:context (:handle request)) (:data request))}
           (catch :default e
             {:ok? false :error :worker/post-message-failed :error/message (str (.-message e))}))

         :terminate
         (try
           (let [{:worker/keys [registry-id] :keys [context]} (:handle request)]
             (dispose-context! context)
             (swap! worker-registry dissoc registry-id)
             {:ok? true})
           (catch :default e
             {:ok? false :error :worker/terminate-failed :error/message (str (.-message e))}))

         {:ok? false :error :worker/unsupported-op}))))

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
     Once resolved, `execution/evaluate!` can call the engine synchronously.

     The returned descriptor's `:quickjs.engine/meta` carries a real
     `:worker-fn` (see `worker-runtime-fn`) -- a genuine capability of this
     ENGINE (running more real JS inside the same sandboxed WASM engine has
     no external side effect, unlike real network I/O), not something that
     needs its own opt-in flag the way `:fetch-fn`/`:websocket-fn` do.
     `:worker/create` only actually USES it once a real `:fetch-fn` is ALSO
     injected into `quickjs-execution/new-state` as `:worker-fn` (see
     `worker-fn` below and `quickjs-execution/worker-create-result`) -- a
     hand-rolled test-double `:engine` fn (every existing JVM test, and any
     `:cljs` test that never calls `worker-fn`) simply never has this key,
     so `(worker-fn engine)` is nil and every existing fabricated-mode
     caller/test is unaffected."
     ([] (engine! {}))
     ([{:keys [modules module-provider signal] :or {modules {}}}]
      (if (aborted? signal)
        (abort-rejection)
      (-> (module!)
          (.then (fn [module]
                   (when (aborted? signal)
                     (throw (js/Error. "QuickJS WASM initialization aborted")))
                   (let [context-atom (atom nil)
                         worker-registry (atom {})]
                     (execution/wasm-engine
                      {:binary (binary-descriptor)
                       :manifest (runtime/component-manifest (runtime/quickjs))
                       :quickjs.wasm/descriptor (assoc (descriptor)
                                                       :quickjs.wasm/modules (vec (keys modules))
                                                       :quickjs.wasm/module-provider? (boolean module-provider)
                                                       :quickjs.wasm/context :reusable)
                       :worker-fn (worker-runtime-fn module worker-registry)
                       :dispose (fn [_engine]
                                  (doseq [[_ context] @worker-registry]
                                    (dispose-context! context))
                                  (reset! worker-registry {})
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
                                                          (:websocket/snapshot request)
                                                          (:worker/snapshot request)
                                                          (:fetch/snapshot request)
                                                          (:notification/snapshot request)
                                                          (:history/snapshot request)
                                                          (:cookie/snapshot request))

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
                                                            (:websocket/snapshot request)
                                                            (:worker/snapshot request)
                                                            (:fetch/snapshot request)
                                                            (:notification/snapshot request)
                                                            (:history/snapshot request)
                                                            (:cookie/snapshot request))
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
