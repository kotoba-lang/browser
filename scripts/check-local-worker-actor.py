#!/usr/bin/env python3
"""Exercise Browser actor glue with the source-pinned Amu local stdio queue."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import time
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
PIN = 'ed5d4d75bf3a60f9e457e12c02887d9f301602f256dd7eb19aed0b7c53c137a7'

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--browser', required=True, type=Path)
    p.add_argument('--stdio', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args(); out = a.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    assert hashlib.sha256(a.stdio.read_bytes()).hexdigest() == PIN, 'Amu stdio source pin mismatch'
    names = ['test/browser-native/local_worker_actor.html','test/browser-native/local_worker_actor_test.js',
             'src/browser/local_worker_actor.js','src/browser/local_actor_host.js','src/browser/local_dom_host.js',
             'src/browser/local_page_host.js','src/browser/local_html_response.js']
    stage = out / 'stage'
    for name in names:
        dest = stage / name; dest.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(ROOT / name, dest)
    shutil.copyfile(a.stdio, stage / 'test/browser-native/amu-local-stdio.js')
    spec = importlib.util.spec_from_file_location('live', ROOT / 'scripts/check-local-native-live.py')
    live = importlib.util.module_from_spec(spec); spec.loader.exec_module(live)
    with tempfile.TemporaryDirectory(prefix='profile-', dir=out) as profile:
        browser = live.Pipe(str(a.browser.resolve(strict=True)), profile)
        try:
            version = browser.call('Browser.getVersion')['product']
            target = browser.call('Target.createTarget', {'url':(stage / names[0]).as_uri()})['targetId']
            session = browser.call('Target.attachToTarget', {'targetId':target,'flatten':True})['sessionId']
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                value = browser.call('Runtime.evaluate', {'expression':'document.getElementById("result")?.textContent ?? "WAIT"','returnByValue':True}, session)['result'].get('value')
                if value != 'WAIT': break
                time.sleep(.05)
            assert value == 'PASS', value
        finally: browser.close()
    receipt = {'status':'PASS_LOCAL_WORKER_ACTOR_STDIO','browser':version,'amuStdioSha256':PIN,
               'sources':{name:hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in names},
               'limitations':['simulated Worker messages, no native or Wasm actor executed',
                              'Amu source is from an unmerged draft; no artifact authentication or production packaging',
                              'no network or service consent qualification']}
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], version)

if __name__ == '__main__': main()
