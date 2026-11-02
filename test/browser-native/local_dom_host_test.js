import { bindLocalInput, commitLocalProjection, encodeLocalEvent } from "../../src/browser/local_dom_host.js";

const mount = document.getElementById("mount");
const result = document.getElementById("result");
const op = [
  [":dom/create-element", 1, ":main"],
  [":dom/set-root", 1],
  [":dom/create-element", 2, ":input"],
  [":dom/set-attr", 2, ":value", "日本"],
  [":dom/append-child", 1, 2],
  [":dom/create-element", 3, ":pre"],
  [":dom/create-text", 4, "<script>not markup</script>"],
  [":dom/append-child", 3, 4],
  [":dom/append-child", 1, 3],
];
// Mirrors the shell's emitted operation vocabulary, split across batches;
// this is a host fixture, not evidence of the yet-unbuilt Kotoba wire.
const shell = [
  [":dom/create-element", 1, ":main"], [":dom/set-root", 1],
  [":dom/create-element", 2, ":nav"], [":dom/set-attr", 2, ":aria-label", "Tabs"], [":dom/append-child", 1, 2],
  [":dom/create-element", 3, ":button"], [":dom/set-attr", 3, ":data-tab-id", "tab-1"],
  [":dom/set-attr", 3, ":aria-selected", true], [":dom/create-text", 4, "First"],
  [":dom/append-child", 3, 4], [":dom/append-child", 2, 3],
  [":dom/create-element", 5, ":button"], [":dom/set-attr", 5, ":data-tab-id", "tab-2"],
  [":dom/set-attr", 5, ":aria-selected", false], [":dom/create-text", 6, "Second"],
  [":dom/append-child", 5, 6], [":dom/append-child", 2, 5],
  [":dom/create-element", 7, ":button"], [":dom/set-attr", 7, ":data-action", ":tab/new"],
  [":dom/create-text", 8, "New tab"], [":dom/append-child", 7, 8], [":dom/append-child", 2, 7],
  [":dom/create-element", 9, ":button"], [":dom/set-attr", 9, ":data-action", ":tab/close-active"],
  [":dom/create-text", 10, "Close tab"], [":dom/append-child", 9, 10], [":dom/append-child", 2, 9],
  [":dom/create-element", 11, ":input"], [":dom/set-attr", 11, ":aria-label", "Address"],
  [":dom/set-attr", 11, ":data-role", "address"], [":dom/set-attr", 11, ":value", "https://example.com/"],
  [":dom/append-child", 1, 11], [":dom/create-element", 12, ":section"],
  [":dom/set-attr", 12, ":data-role", "page"], [":dom/append-child", 1, 12],
];
function check(condition, message) { if (!condition) throw new Error(message); }
function reject(batches, label) {
  const before = mount.firstChild;
  let refused = false;
  try { commitLocalProjection(mount, batches); } catch (_) { refused = true; }
  check(refused && mount.firstChild === before, label);
}

try {
  commitLocalProjection(mount, [op.slice(0, 5), op.slice(5)]);
  check(mount.querySelector("input").value === "日本", "input value");
  check(mount.querySelector("pre").textContent === "<script>not markup</script>", "text");
  check(!mount.querySelector("script"), "markup executed");
  const projection = commitLocalProjection(mount, [shell.slice(0, 32), shell.slice(32)]);
  check(mount.querySelectorAll("nav button").length === 4, "shell tabs");
  check(mount.querySelector('[data-tab-id="tab-1"]').getAttribute("aria-selected") === "true", "selected tab");
  check(mount.querySelector('[data-role="address"]').value === "https://example.com/", "shell input");
  const received = [];
  const unbind = bindLocalInput(mount, projection, message => received.push(message));
  const input = mount.querySelector('[data-role="address"]');
  mount.querySelector('[data-tab-id="tab-2"]').click();
  input.setSelectionRange(1, 3, "backward");
  const insert = new InputEvent("beforeinput", { bubbles: true, cancelable: true, inputType: "insertText", data: "😀" });
  input.dispatchEvent(insert);
  const del = new InputEvent("beforeinput", { bubbles: true, cancelable: true, inputType: "deleteContentForward" });
  input.dispatchEvent(del);
  const left = new KeyboardEvent("keydown", { bubbles: true, cancelable: true, key: "ArrowLeft", shiftKey: true });
  input.dispatchEvent(left);
  input.dispatchEvent(new CompositionEvent("compositionstart", { bubbles: true, data: "" }));
  input.dispatchEvent(new CompositionEvent("compositionupdate", { bubbles: true, data: "日本" }));
  input.dispatchEvent(new CompositionEvent("compositionend", { bubbles: true, data: "日本" }));
  const clipboard = new DataTransfer();
  clipboard.setData("text/plain", "貼付");
  const paste = new ClipboardEvent("paste", { bubbles: true, cancelable: true, clipboardData: clipboard });
  input.dispatchEvent(paste);
  const enter = new KeyboardEvent("keydown", { bubbles: true, cancelable: true, key: "Enter" });
  input.dispatchEvent(enter);
  const unsupported = new InputEvent("beforeinput", { bubbles: true, cancelable: true, inputType: "historyUndo" });
  input.dispatchEvent(unsupported);
  check(received[0].type === ":ui/click" && received[0].target === 5, "button event");
  check(insert.defaultPrevented && received[1].type === ":ui/text" && received[1].text === "😀" &&
        received[1]["selection-anchor"] === 3 && received[1]["selection-focus"] === 1, "text selection event");
  const encodedInput = encodeLocalEvent(received[1]);
  check(encodedInput === "B1|:ui/text|11|3|1|0|0|😀", "event wire");
  check(encodeLocalEvent({ type: ":ui/text", target: 11, text: "a|b\n日本" }).endsWith("|a|b\n日本"), "event text framing");
  let invalidEvent = false;
  try { encodeLocalEvent({ type: ":ui/submit", target: 11, text: "x" }); } catch (_) { invalidEvent = true; }
  check(invalidEvent, "invalid event wire");
  const wire = document.createElement("pre");
  wire.id = "event-wire";
  wire.textContent = encodedInput;
  document.body.appendChild(wire);
  check(del.defaultPrevented && received[2].type === ":ui/delete", "forward delete event");
  check(left.defaultPrevented && received[3].type === ":ui/move" && received[3].delta === -1 && received[3].extend, "move event");
  check(received[4].type === ":ui/composition-start" && received[5].text === "日本" && received[6].type === ":ui/composition-end", "composition events");
  check(paste.defaultPrevented && received[7].type === ":ui/text" && received[7].text === "貼付", "paste event");
  check(enter.defaultPrevented && received[8].type === ":ui/submit", "submit event");
  check(unsupported.defaultPrevented && received.length === 9, "unsupported edit");
  reject([[...op, [":dom/create-element", 5, ":script"]]], "script tag");
  reject([[...op, [":dom/set-attr", 1, ":onclick", "alert(1)"]]], "event attribute");
  reject([[...op, [":dom/set-attr", 1, ":data-action", ":navigation/confirm"]]], "forged action");
  reject([[...op, [":dom/append-child", 3, 1]]], "cycle");
  reject([[...op, [":dom/create-element", 2, ":button"]]], "duplicate id");
  reject([[...op, [":dom/create-text", 5, "orphan"]]], "orphan");
  reject([[...op, [":dom/append-child", 1, 99]]], "missing child");
  reject([[...op, [":dom/unknown", 1]]], "unknown operation");
  reject([[...op, [":dom/set-attr", 2, ":value", "x".repeat(1_048_577)]]], "oversized value");
  reject([[]], "empty batch");
  reject([op, op, op, op, op, op, op, op, op], "too many batches");
  commitLocalProjection(mount, [op]);
  check(projection.resolve(input) === null, "stale projection target");
  unbind();
  const firstFocus = commitLocalProjection(mount, [shell.slice(0, 32), shell.slice(32)]);
  const oldAddress = mount.querySelector('[data-role="address"]');
  oldAddress.focus();
  check(document.activeElement === oldAddress, "initial focus");
  const next = shell.map(row => row.slice());
  next.find(row => row[0] === ":dom/set-attr" && row[2] === ":value")[3] = "A😀B";
  next.push([":dom/set-attr", 11, ":data-selection-start", 1],
            [":dom/set-attr", 11, ":data-selection-end", 3],
            [":dom/set-attr", 11, ":data-caret", 1]);
  const nextBatches = [next.slice(0, 32), next.slice(32)];
  const nextProjection = commitLocalProjection(mount, nextBatches);
  const newAddress = mount.querySelector('[data-role="address"]');
  check(document.activeElement === newAddress && newAddress.value === "A😀B" &&
        newAddress.selectionStart === 1 && newAddress.selectionEnd === 3 &&
        newAddress.selectionDirection === "backward", "restore focused selection");
  const invalidSelection = next.map(row => row.slice());
  invalidSelection.find(row => row[0] === ":dom/set-attr" && row[2] === ":data-caret")[3] = 2;
  reject([invalidSelection.slice(0, 32), invalidSelection.slice(32)], "invalid surrogate selection");
  const stopComposition = bindLocalInput(mount, nextProjection, () => {});
  let duplicateRefused = false;
  try { bindLocalInput(mount, nextProjection, () => {}); } catch (_) { duplicateRefused = true; }
  check(duplicateRefused, "duplicate input binding");
  newAddress.dispatchEvent(new CompositionEvent("compositionstart", { bubbles: true }));
  reject(nextBatches, "composition redraw");
  let prematureUnbind = false;
  try { stopComposition(); } catch (_) { prematureUnbind = true; }
  check(prematureUnbind, "composition unbind");
  newAddress.dispatchEvent(new CompositionEvent("compositionend", { bubbles: true, data: "日本" }));
  commitLocalProjection(mount, nextBatches);
  stopComposition();
  const cleanProjection = commitLocalProjection(mount, nextBatches);
  const cleanAddress = mount.querySelector('[data-role="address"]');
  let staleEvents = 0;
  const cancelBinding = bindLocalInput(mount, cleanProjection, () => staleEvents++);
  cleanAddress.dispatchEvent(new CompositionEvent("compositionstart", { bubbles: true }));
  cancelBinding(true);
  const replacement = bindLocalInput(mount, cleanProjection, () => {});
  cancelBinding(true); // An old disposer must not remove a newer binding.
  let stillBound = false;
  try { bindLocalInput(mount, cleanProjection, () => {}); } catch (_) { stillBound = true; }
  cleanAddress.dispatchEvent(new CompositionEvent("compositionend", { bubbles: true }));
  check(stillBound && staleEvents === 1, "forced composition cleanup and stale disposer");
  replacement();
  commitLocalProjection(mount, nextBatches);
  check(firstFocus.resolve(oldAddress) === null, "old focused input stale");
  result.textContent = "PASS";
} catch (error) {
  result.textContent = "FAIL: " + error.message;
}
