#!/usr/bin/env python3
"""Exercise the offline page projection host in Chrome; no page network."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    fixture = root / 'test/browser-native/local_page_host.html'
    sources = [fixture, root / 'test/browser-native/local_page_host_test.js',
               root / 'test/browser-native/page_fixture.js', root / 'src/browser/local_page_host.js']
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('native_live', root / 'scripts/check-local-native-live.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with tempfile.TemporaryDirectory(prefix='profile-', dir=output) as profile:
        browser = module.Pipe(str(args.browser.resolve(strict=True)), profile)
        try:
            version = browser.call('Browser.getVersion')['product']
            target = browser.call('Target.createTarget', {'url': 'about:blank'})['targetId']
            session = browser.call('Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
            browser.call('Page.navigate', {'url': fixture.as_uri()}, session)
            deadline = time.monotonic() + 30
            while True:
                response = browser.call('Runtime.evaluate', {'expression': 'document.getElementById("result")?.textContent ?? "WAIT"', 'returnByValue': True}, session)
                value = response['result'].get('value')
                if value != 'WAIT' or time.monotonic() >= deadline:
                    break
                time.sleep(.1)
            html = browser.call('Runtime.evaluate', {'expression': 'document.documentElement.outerHTML', 'returnByValue': True}, session)['result'].get('value', '')
            (output / 'rendered.html').write_text(html)
            assert value == 'PASS', value
        finally:
            browser.close()
    receipt = {'status': 'PASS_OFFLINE_PAGE_HOST_FIXTURE', 'browser': version,
               'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
               'fixtureProvenance': 'checked-in captured native output; separately verify native source lock',
               'notQualified': ['live parser integration', 'navigation', 'network consent', 'Kotoba layout', 'general HTML isolation']}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], receipt['browser'])


if __name__ == '__main__':
    main()
