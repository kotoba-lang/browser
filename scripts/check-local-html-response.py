#!/usr/bin/env python3
"""Offline synthetic Response validation in a real browser, without fetch."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('native_live', ROOT / 'scripts/check-local-native-live.py')
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--browser', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    output = a.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    sources = [ROOT / 'test/browser-native/local_html_response.html', ROOT / 'test/browser-native/local_html_response_test.js', ROOT / 'src/browser/local_html_response.js', ROOT / 'scripts/check-local-native-live.py']
    with tempfile.TemporaryDirectory(prefix='profile-', dir=output) as profile:
        browser = live.Pipe(str(a.browser.resolve(strict=True)), profile)
        try:
            version = browser.call('Browser.getVersion')['product']
            target = browser.call('Target.createTarget', {'url':'about:blank'})['targetId']
            session = browser.call('Target.attachToTarget', {'targetId':target, 'flatten':True})['sessionId']
            browser.call('Page.navigate', {'url':sources[0].as_uri()}, session)
            deadline = time.monotonic() + 30
            while True:
                r = browser.call('Runtime.evaluate', {'expression':'document.getElementById("result")?.textContent ?? "RUNNING"', 'returnByValue':True}, session)
                value = r['result'].get('value')
                if value != 'RUNNING' or time.monotonic() >= deadline: break
                time.sleep(.1)
            assert value == 'PASS', value
        finally: browser.close()
    receipt = {'status':'PASS_SYNTHETIC_HTML_RESPONSE', 'browser':version,
               'sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
               'limitations':['synthetic Response objects', 'no broker provenance', 'no real service', 'no user consent']}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], version)


if __name__ == '__main__': main()
