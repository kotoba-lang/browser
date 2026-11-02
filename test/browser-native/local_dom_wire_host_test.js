import { commitLocalProjection } from "../../src/browser/local_dom_host.js";
import batches from "./wire_fixture.js";

const mount = document.getElementById("mount");
const result = document.getElementById("result");
try {
  commitLocalProjection(mount, batches);
  if (mount.querySelectorAll("nav button").length !== 4 ||
      mount.querySelector('[data-tab-id="tab-2"]').getAttribute("aria-selected") !== "true" ||
      mount.querySelector('[data-role="address"]').value !== "https://two.example/" ||
      mount.querySelector("pre").textContent !== '<script>"safe"</script>\n日本\t' ||
      mount.querySelector("script")) throw new Error("rendered projection mismatch");
  result.textContent = "PASS";
} catch (error) {
  result.textContent = "FAIL: " + error.message;
}
