#!/usr/bin/env python3
"""Test the local Browser actor against a test-only loopback HTTPS service.

No external endpoint is enabled. A fresh test-key SPKI exception is necessary
for the self-signed fixture and is not evidence of normal TLS trust.
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

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('html_live', ROOT / 'scripts/check-local-html-live.py')
html_live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(html_live)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--chrome', required=True)
    p.add_argument('--actor-command-file', type=Path, required=True)
    p.add_argument('--actor-cwd', required=True)
    p.add_argument('--broker', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    output = a.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    requests = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append({'path':self.path, 'method':self.command, 'headers':sorted(k.lower() for k in self.headers)})
            if self.path not in ('/page', '/next'):
                self.send_error(404); return
            body = (html_live.HTML_FIXTURE if self.path == '/page' else html_live.NEXT_HTML_FIXTURE).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body)
        def log_message(self, *_): pass
    with tempfile.TemporaryDirectory(prefix='tls-fixture-', dir=output) as temp:
        cert, key = Path(temp) / 'certificate.pem', Path(temp) / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                        '-keyout', str(key), '-out', str(cert), '-days', '1',
                        '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        public = subprocess.run(['openssl', 'x509', '-in', str(cert), '-pubkey', '-noout'], check=True, capture_output=True).stdout
        spki = subprocess.run(['openssl', 'pkey', '-pubin', '-outform', 'DER'], input=public, check=True, capture_output=True).stdout
        cert_pin = base64.b64encode(hashlib.sha256(spki).digest()).decode()
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(cert, key)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            origin = f'https://127.0.0.1:{server.server_port}'
            args = argparse.Namespace(chrome=a.chrome, actor_command_file=a.actor_command_file,
                                      actor_cwd=a.actor_cwd, broker=a.broker,
                                      log=output / 'actor.log', receipt=output / 'actor-receipt.json',
                                      loopback_origin=origin, loopback_spki=cert_pin)
            html_live.run(args)
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=5)
    assert [item['path'] for item in requests] == ['/page', '/next', '/page', '/next', '/page'], requests
    assert all(item['method'] == 'GET' and not any(h in item['headers'] for h in ('cookie','authorization','referer')) for item in requests)
    receipt = json.loads((output / 'actor-receipt.json').read_text())
    assert receipt['status'] == 'PASS_LOOPBACK_HTTPS_HTML_LIVE' and not receipt['syntheticResponse']
    receipt.update({'status':'PASS_LOOPBACK_HTTPS_ACTOR_SERVER', 'serverRequests':requests,
                    'wrapperSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    'limitations':receipt['limitations'] + ['archived broker bytes; canonical source review incomplete',
                        'self-signed certificate exception is test-only; no TLS trust qualification', 'no human consent or real external service']})
    (output / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
    print(receipt['status'], receipt['chrome'], 'server requests:', len(requests))


if __name__ == '__main__': main()
