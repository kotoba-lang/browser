#!/usr/bin/env python3
"""Run the offline DOM host fixture in a real Chromium-family browser."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    fixture = root / 'test/browser-native/local_dom_host.html'
    sources = [fixture, fixture.with_name('local_dom_host_test.js'), root / 'src/browser/local_dom_host.js']
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
                   externalConnections='CSP connect-src none and DNS resolver refusal',
                   qualified=['detached projection commit', 'malformed transaction rejection'],
                   notQualified=['Kotoba document wire encoding', 'guest provenance', 'host event routing',
                                 'network and consent', 'dapp integration', 'real-user interaction'])
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], receipt['browser'])


if __name__ == '__main__':
    main()
