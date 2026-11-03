#!/usr/bin/env python3
"""Test a pinned broker against a test-only loopback HTTPS receiver.

The self-signed certificate exception is confined to a fresh CDP browser.
All non-fixture HTTPS requests are blocked; this is not TLS qualification.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
BROKER_SHA = '7ce9367be68652b30fb15eb3eb385968792774006bb3d67d56646803e2bb36cc'
spec = importlib.util.spec_from_file_location('native_live', ROOT / 'scripts/check-local-native-live.py')
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--browser', required=True, type=Path)
    p.add_argument('--broker', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    negatives = p.add_mutually_exclusive_group()
    negatives.add_argument('--negative-no-cert-exception', action='store_true', help='verify the self-signed endpoint is refused without the test-only key exception')
    negatives.add_argument('--negative-no-cors', action='store_true', help='verify a contacted service without CORS approval cannot supply a response')
    a = p.parse_args()
    output = a.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    data = a.broker.resolve(strict=True).read_bytes()
    assert sha(data) == BROKER_SHA, 'broker pin mismatch'
    cert, key = output / 'fixture-cert.pem', output / 'fixture-key.pem'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                    '-keyout', str(key), '-out', str(cert), '-days', '1',
                    '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1'],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    public = subprocess.run(['openssl', 'x509', '-in', str(cert), '-pubkey', '-noout'], check=True, capture_output=True).stdout
    spki = subprocess.run(['openssl', 'pkey', '-pubin', '-outform', 'DER'], input=public, check=True, capture_output=True).stdout
    cert_pin = base64.b64encode(hashlib.sha256(spki).digest()).decode()
    requests = []
    body = '<p>loopback 承認済み</p>'
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append({'method':self.command, 'path':self.path,
                             'headers':sorted(k.lower() for k in self.headers)})
            if self.path != '/page':
                self.send_error(404); return
            payload = body.encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            if not a.negative_no_cors: self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers(); self.wfile.write(payload)
        def log_message(self, *_): pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(cert, key)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    origin = f'https://127.0.0.1:{server.server_port}'
    stage = output / 'app'; stage.mkdir()
    sources = [ROOT / 'test/browser-native/local_broker_response.html', ROOT / 'test/browser-native/local_broker_response_test.js', ROOT / 'src/browser/local_html_response.js']
    for path in sources:
        content = path.read_bytes().replace(b'https://service.invalid', origin.encode())
        (stage / path.name).write_bytes(content)
    (stage / 'canonical-client-runtime.js').write_bytes(data)
    observed = []; failures = []
    try:
        with tempfile.TemporaryDirectory(prefix='profile-', dir=output) as profile:
            pipe = live.Pipe(str(a.browser.resolve(strict=True)), profile, host_rules='MAP * ~NOTFOUND, EXCLUDE 127.0.0.1',
                             browser_args=[] if a.negative_no_cert_exception else [f'--ignore-certificate-errors-spki-list={cert_pin}'])
            try:
                version = pipe.call('Browser.getVersion')['product']
                target = pipe.call('Target.createTarget', {'url':'about:blank'})['targetId']
                session = pipe.call('Target.attachToTarget', {'targetId':target, 'flatten':True})['sessionId']
                pipe.call('Network.enable', {}, session)
                pipe.call('Fetch.enable', {'patterns':[{'urlPattern':'https://*', 'requestStage':'Request'}]}, session)
                def pump():
                    while pipe.events:
                        event = pipe.events.pop(0)
                        if event.get('method') == 'Network.loadingFailed': failures.append(event['params'])
                        if event.get('method') != 'Fetch.requestPaused': continue
                        params = event['params']; request = params['request']; owner = event.get('sessionId')
                        observed.append({'url':request['url'], 'method':request['method']})
                        if owner == session and request['url'] == origin + '/page' and request['method'] == 'GET':
                            pipe.send('Fetch.continueRequest', {'requestId':params['requestId']}, owner)
                        else:
                            pipe.send('Fetch.failRequest', {'requestId':params['requestId'], 'errorReason':'BlockedByClient'}, owner)
                def evaluate(expression):
                    result = pipe.call('Runtime.evaluate', {'expression':expression, 'returnByValue':True}, session)
                    pump()
                    if 'exceptionDetails' in result: raise RuntimeError(result['exceptionDetails'])
                    return result['result'].get('value')
                def wait(predicate):
                    deadline = time.monotonic() + 30; state = None
                    while time.monotonic() < deadline:
                        state = evaluate('window.brokerFixture?.state() ?? null')
                        if state and predicate(state): return state
                        time.sleep(.05)
                    raise TimeoutError(('broker fixture', state))
                def click(index):
                    pos = evaluate(f'''(()=>{{const e=document.querySelectorAll('dialog.kotoba-client-consent button')[{index}];const r=e.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]}})()''')
                    for kind in ('mousePressed','mouseReleased'):
                        pipe.call('Input.dispatchMouseEvent', {'type':kind,'x':pos[0],'y':pos[1],'button':'left','clickCount':1}, session); pump()
                pipe.call('Page.navigate', {'url':(stage / sources[0].name).as_uri()}, session)
                wait(lambda s:s['status']=='ready')
                evaluate("window.brokerFixture.start('/other')")
                denied = wait(lambda s:s['status']=='error')
                assert 'declared' in denied['message'] and not observed and not requests
                evaluate("window.brokerFixture.start('/page')"); wait(lambda s:s['open']); click(0)
                denied = wait(lambda s:s['status']=='error')
                assert 'not approved' in denied['message'] and not observed and not requests
                time.sleep(.25)
                evaluate("window.brokerFixture.start('/page')"); wait(lambda s:s['open'])
                evaluate("document.querySelectorAll('dialog.kotoba-client-consent button')[1].click()")
                assert evaluate('window.brokerFixture.state()')['open'] and not requests
                click(1); allowed = wait(lambda s:s['status'] in ('ok','error'))
                if a.negative_no_cert_exception:
                    assert allowed['status']=='error' and len(observed)==1 and not requests, (allowed, observed, requests, failures)
                    assert any('CERT_AUTHORITY_INVALID' in f.get('errorText', '') for f in failures), failures
                    (output / 'negative-receipt.json').write_text(json.dumps({'status':'PASS_SELF_SIGNED_CERT_REFUSED',
                        'browser':version, 'failures':failures, 'serverRequests':requests}, indent=2) + '\n')
                    print('PASS_SELF_SIGNED_CERT_REFUSED', version)
                    return
                if a.negative_no_cors:
                    assert allowed['status']=='error' and len(observed)==1 and len(requests)==1, (allowed, observed, requests, failures)
                    assert requests[0]['path']=='/page' and not allowed['encoded'], (allowed, requests)
                    assert any(f.get('corsErrorStatus') for f in failures), failures
                    (output / 'cors-receipt.json').write_text(json.dumps({'status':'PASS_CORS_RESPONSE_REFUSED',
                        'browser':version, 'brokerSha256':BROKER_SHA, 'serverRequests':requests, 'failures':failures,
                        'limitations':['test-only loopback HTTPS; automated consent; product integration unreviewed',
                                       'server received request; CORS only refused response access',
                                       'test certificate exception; no real service or production qualification']}, indent=2) + '\n')
                    print('PASS_CORS_RESPONSE_REFUSED', version)
                    return
                assert allowed['status']=='ok', (allowed, observed, requests, failures)
                assert len(observed)==1 and len(requests)==1 and requests[0]['path']=='/page'
                assert not any(h in requests[0]['headers'] for h in ('authorization','cookie','referer'))
                length, payload = allowed['encoded'].split('\n', 1)
                url = origin + '/page'
                assert int(length)==len(payload.encode()) and payload == f'H1|4|1|tab-1|{len(url.encode())}|{url}' + body
                time.sleep(.25)
                evaluate("window.brokerFixture.start('/page')"); wait(lambda s:s['open']); click(0)
                assert wait(lambda s:s['status']=='error') and len(requests)==1 and len(observed)==1
            finally:
                pipe.close()
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)
        key.unlink(missing_ok=True)
    receipt = {'status':'PASS_LOOPBACK_HTTPS_PINNED_BROKER', 'browser':version,
               'brokerSha256':BROKER_SHA, 'origin':origin, 'serverRequests':requests, 'browserRequests':observed,
               'sources':{str(path.relative_to(ROOT)):sha(path.read_bytes()) for path in [*sources, ROOT / 'scripts/check-local-native-live.py', Path(__file__).resolve()]},
               'stagedSources':{path.name:sha(path.read_bytes()) for path in sorted(stage.iterdir())},
               'limitations':['self-signed certificate exception scoped to fixture public key in isolated test browser; no TLS trust qualification',
                              'CDP restricts HTTPS to one loopback URL and supplies automated clicks',
                              'canonical broker fixture; shared runtime qualification and product integration remain open',
                              'no human consent, real service, production host or native actor']}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], version, 'server requests:', len(requests))


if __name__ == '__main__': main()
