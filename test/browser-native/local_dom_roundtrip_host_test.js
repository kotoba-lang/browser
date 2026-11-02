import { commitLocalProjection } from "../../src/browser/local_dom_host.js";
import batches from "./wire_fixture.js";

const mount = document.getElementById("mount");
const result = document.getElementById("result");
try {
  const old = document.createElement("input");
  old.setAttribute("data-role", "address");
  old.value = "A😀B";
  mount.appendChild(old);
  old.focus();
  old.setSelectionRange(1, 3, "backward");
  commitLocalProjection(mount, batches);
  const input = mount.querySelector('[data-role="address"]');
  if (mount.querySelectorAll("nav button").length !== 6 || document.activeElement !== input ||
      input.value !== "A😀B" || input.selectionStart !== 3 || input.selectionEnd !== 3 ||
      mount.querySelector("script")) throw new Error("event result mismatch");
  result.textContent = "PASS";
} catch (error) {
  result.textContent = "FAIL: " + error.message;
}
