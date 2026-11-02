#!/usr/bin/env python3
"""Measure a test-only slow HTTPS body on the local Browser host boundary.

Uses an isolated Chrome profile, loopback-only request gate, automated CDP
clicks and an ephemeral self-signed test-key exception. No real site is used.
"""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
BROKER_SHA = '7f760128c3a58098123c31ec3dd34b9c871932e3933f2c952facc4ab0e647212'
spec = importlib.util.spec_from_file_location('native_live', ROOT / 'scripts/check-local-native-live.py')
live = importlib.util.module_from_spec(spec); spec.loader.exec_module(live)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--browser', type=Path, required=True)
    p.add_argument('--broker', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--host-source', type=Path, default=ROOT / 'src/browser/local_actor_host.js')
    p.add_argument('--response-source', type=Path, default=ROOT / 'src/browser/local_html_response.js')
    p.add_argument('--expect-old-behavior', action='store_true')
    a = p.parse_args()
    output = a.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    broker = a.broker.resolve(strict=True).read_bytes()
    assert hashlib.sha256(broker).hexdigest() == BROKER_SHA
    requests = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            entry = {'path':self.path, 'method':self.command, 'headers':sorted(k.lower() for k in self.headers), 'bytes':0, 'result':'streaming'}
            requests.append(entry)
            if self.path != '/slow': self.send_error(404); entry['result'] = 'bad-path'; return
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Content-Length', str(3 + 60 * 512 + 4))
            self.end_headers()
            try:
                self.wfile.write(b'<p>'); self.wfile.flush(); entry['bytes'] += 3
                for _ in range(60):
                    time.sleep(.04)
                    self.wfile.write(b'x' * 512); self.wfile.flush(); entry['bytes'] += 512
                self.wfile.write(b'</p>'); self.wfile.flush(); entry['bytes'] += 4
                entry['result'] = 'finished'
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError, OSError):
                entry['result'] = 'disconnected'
        def log_message(self, *_): pass
    with tempfile.TemporaryDirectory(prefix='slow-tls-', dir=output) as tmp:
        cert, key = Path(tmp) / 'cert.pem', Path(tmp) / 'key.pem'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),
                        '-days','1','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        pub = subprocess.run(['openssl','x509','-in',str(cert),'-pubkey','-noout'],check=True,capture_output=True).stdout
        der = subprocess.run(['openssl','pkey','-pubin','-outform','DER'],input=pub,check=True,capture_output=True).stdout
        pin = base64.b64encode(hashlib.sha256(der).digest()).decode()
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(cert, key)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        origin = f'https://127.0.0.1:{server.server_port}'
        stage = output / 'app'
        files = ['test/browser-native/local_slow_html.html', 'test/browser-native/local_slow_html_test.js',
                 'src/browser/local_dom_host.js', 'src/browser/local_page_host.js']
        for name in files:
            dst = stage / name; dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes((ROOT / name).read_bytes().replace(b'https://service.invalid', origin.encode()))
        (stage / 'src/browser/local_actor_host.js').write_bytes(a.host_source.read_bytes())
        (stage / 'src/browser/local_html_response.js').write_bytes(a.response_source.read_bytes())
        (stage / 'test/browser-native/canonical-client-runtime.js').write_bytes(broker)
        observed = []
        try:
            with tempfile.TemporaryDirectory(prefix='profile-', dir=output) as profile:
                pipe = live.Pipe(str(a.browser.resolve(strict=True)), profile,
                    host_rules='MAP * ~NOTFOUND, EXCLUDE 127.0.0.1', browser_args=[f'--ignore-certificate-errors-spki-list={pin}'])
                try:
                    version = pipe.call('Browser.getVersion')['product']
                    target = pipe.call('Target.createTarget', {'url':'about:blank'})['targetId']
                    session = pipe.call('Target.attachToTarget', {'targetId':target,'flatten':True})['sessionId']
                    pipe.call('Fetch.enable', {'patterns':[{'urlPattern':'https://*','requestStage':'Request'}]}, session)
                    def pump():
                        while pipe.events:
                            event = pipe.events.pop(0)
                            if event.get('method') != 'Fetch.requestPaused': continue
                            params = event['params']; req = params['request']; owner = event.get('sessionId')
                            observed.append({'url':req['url'], 'method':req['method']})
                            if owner == session and req['url'] == origin + '/slow' and req['method'] == 'GET':
                                pipe.send('Fetch.continueRequest', {'requestId':params['requestId']}, owner)
                            else: pipe.send('Fetch.failRequest', {'requestId':params['requestId'],'errorReason':'BlockedByClient'}, owner)
                    def evaluate(expr):
                        result = pipe.call('Runtime.evaluate', {'expression':expr,'returnByValue':True}, session); pump()
                        if 'exceptionDetails' in result: raise RuntimeError(result['exceptionDetails'])
                        return result['result'].get('value')
                    def wait(predicate, seconds=10):
                        deadline = time.monotonic() + seconds; state = None
                        while time.monotonic() < deadline:
                            state = evaluate('window.slowFixture?.state() ?? null')
                            if state and predicate(state): return state
                            time.sleep(.03)
                        raise TimeoutError(('slow fixture', state, requests))
                    def click(index):
                        pos = evaluate(f'''(()=>{{const e=document.querySelectorAll('dialog.kotoba-client-consent button')[{index}];const r=e.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]}})()''')
                        for kind in ('mousePressed','mouseReleased'):
                            pipe.call('Input.dispatchMouseEvent', {'type':kind,'x':pos[0],'y':pos[1],'button':'left','clickCount':1}, session); pump()
                    fixture = (stage / 'test/browser-native/local_slow_html.html').as_uri()
                    pipe.call('Page.navigate', {'url':fixture}, session); wait(lambda x:x['state']=='ready')
                    evaluate('window.slowFixture.start()'); wait(lambda x:x['state']=='pending'); click(0)
                    assert wait(lambda x:x['state']=='rejected') and not requests and not observed
                    time.sleep(.25)
                    evaluate('window.slowFixture.start()'); wait(lambda x:x['state']=='pending'); click(1)
                    wait(lambda x:x['state']=='reading'); assert len(requests)==1
                    assert evaluate('window.slowFixture.cancel()') is True
                    if a.expect_old_behavior:
                        time.sleep(.5); assert evaluate('window.slowFixture.state()')['state']=='reading'
                        wait(lambda x:x['state']=='rejected', 8)
                    else:
                        cancelled = wait(lambda x:x['state']=='rejected', 1.5)
                        assert 'HTML' not in cancelled['writes'] and 'CANCEL' in cancelled['writes']
                    deadline = time.monotonic() + 8
                    while requests[0]['result']=='streaming' and time.monotonic()<deadline: time.sleep(.03)
                    assert requests[0]['result'] == ('finished' if a.expect_old_behavior else 'disconnected'), requests
                    pipe.call('Page.navigate', {'url':fixture}, session); wait(lambda x:x['state']=='ready')
                    time.sleep(.25); evaluate('window.slowFixture.start()'); wait(lambda x:x['state']=='pending'); click(1)
                    normal = wait(lambda x:x['state']=='delivered', 8)
                    assert 'HTML' in normal['writes'] and len(requests)==2 and requests[1]['result']=='finished', (normal, requests)
                finally: pipe.close()
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=5)
    assert len(observed) == 2 and all(not any(h in item['headers'] for h in ('cookie','authorization','referer')) for item in requests)
    result = {'status':'PASS_OLD_READER_CONTINUES' if a.expect_old_behavior else 'PASS_SLOW_HTTPS_READER_CANCEL',
              'browser':version, 'brokerSha256':BROKER_SHA, 'serverRequests':requests, 'browserRequests':observed,
              'hostSha256':hashlib.sha256(a.host_source.read_bytes()).hexdigest(),
              'responseSha256':hashlib.sha256(a.response_source.read_bytes()).hexdigest(),
              'wrapperSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'transportSha256':hashlib.sha256((ROOT / 'scripts/check-local-native-live.py').read_bytes()).hexdigest(),
              'sources':{name:hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in files},
              'limitations':['one local Chrome and a synthetic fake actor', 'CDP gates requests and supplies automated clicks',
                             'self-signed test-key exception; no normal TLS trust qualification',
                             'server disconnect is not proof of remote processing rollback', 'archived broker; production integration unreviewed']}
    (output / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    print(result['status'], version, [(x['result'], x['bytes']) for x in requests])


if __name__ == '__main__': main()
