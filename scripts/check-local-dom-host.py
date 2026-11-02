#!/usr/bin/env python3
"""Run the offline DOM host fixture in a real Chromium-family browser."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--wire', type=Path, help='native-emitted JSON operation batches')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    suffix = 'local_dom_wire_host' if args.wire else 'local_dom_host'
    html = root / ('test/browser-native/' + suffix + '.html')
    test = root / ('test/browser-native/' + suffix + '_test.js')
    host = root / 'src/browser/local_dom_host.js'
    sources = [html, test, host]
    fixture = html
    wire_hash = None
    if args.wire:
        raw = args.wire.read_bytes()
        assert len(raw) <= 1048576 and isinstance(json.loads(raw), list)
        wire_hash = hashlib.sha256(raw).hexdigest()
        stage = output / 'app'
        for source in sources:
            target = stage / source.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        fixture = stage / html.relative_to(root)
        (fixture.parent / 'wire_fixture.js').write_text(
            'export default JSON.parse(' + json.dumps(raw.decode('utf-8'), ensure_ascii=True) + ');\n')
    browser = args.browser.resolve()
    command = [str(browser), '--headless', '--disable-gpu', '--no-first-run',
               '--disable-background-networking', '--disable-default-apps', '--disable-sync',
               '--no-default-browser-check', '--host-resolver-rules=MAP * ~NOTFOUND',
               '--allow-file-access-from-files', '--user-data-dir=' + str(output / 'profile'),
               '--virtual-time-budget=5000', '--dump-dom', fixture.as_uri()]
    version = subprocess.run([str(browser), '--version'], capture_output=True, text=True, check=True, timeout=30)
    proc = subprocess.run(command, capture_output=True, text=True, timeout=90)
    (output / 'rendered.html').write_text(proc.stdout)
    (output / 'browser.stderr').write_text(proc.stderr)
    assert proc.returncode == 0 and '<pre id="result">PASS</pre>' in proc.stdout, (proc.returncode, proc.stdout[-2000:])
    receipt = dict(status='PASS_OFFLINE_DOM_HOST_FIXTURE', browser=version.stdout.strip(),
                   sources={str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
                   wireSha256=wire_hash, externalConnections='CSP connect-src none and DNS resolver refusal',
                   qualified=['detached projection commit', 'malformed transaction rejection'],
                   notQualified=['supplied wire provenance', 'Wasm document ABI', 'host event routing',
                                 'network and consent', 'dapp integration', 'real-user interaction'])
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], receipt['browser'])


if __name__ == '__main__':
    main()
