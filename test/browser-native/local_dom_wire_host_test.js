import { commitLocalProjection } from "../../src/browser/local_dom_host.js";
import batches from "./wire_fixture.js";

const mount = document.getElementById("mount");
const result = document.getElementById("result");
try {
  const previous = document.createElement("input");
  previous.setAttribute("data-role", "address");
  mount.appendChild(previous);
  previous.focus();
  commitLocalProjection(mount, batches);
  const address = mount.querySelector('[data-role="address"]');
  if (mount.querySelectorAll("nav button").length !== 4 ||
      mount.querySelector('[data-tab-id="tab-2"]').getAttribute("aria-selected") !== "true" ||
      address.value !== "https://two.example/" || document.activeElement !== address ||
      address.selectionStart !== address.value.length || address.selectionEnd !== address.value.length ||
      mount.querySelector("pre").textContent !== '<script>"safe"</script>\n日本\t' ||
      mount.querySelector("script")) throw new Error("rendered projection mismatch");
  result.textContent = "PASS";
} catch (error) {
  result.textContent = "FAIL: " + error.message;
}
