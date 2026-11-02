import { commitLocalProjection } from "../../src/browser/local_dom_host.js";

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
  commitLocalProjection(mount, [shell.slice(0, 32), shell.slice(32)]);
  check(mount.querySelectorAll("nav button").length === 4, "shell tabs");
  check(mount.querySelector('[data-tab-id="tab-1"]').getAttribute("aria-selected") === "true", "selected tab");
  check(mount.querySelector('[data-role="address"]').value === "https://example.com/", "shell input");
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
  result.textContent = "PASS";
} catch (error) {
  result.textContent = "FAIL: " + error.message;
}
