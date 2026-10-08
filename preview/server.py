#!/usr/bin/env python3
"""Serve the exact APK interface, with an explicitly labelled browser-only demo adapter."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'app/src/main/assets'
class Handler(SimpleHTTPRequestHandler):
    def translate_path(self, path):
        if path.split('?')[0] == '/preview-adapter.js':
            return str(ROOT / 'preview/preview-adapter.js')
        relative = path.split('?')[0].lstrip('/') or 'index.html'
        target = (ASSETS / relative).resolve()
        if not target.is_relative_to(ASSETS.resolve()):
            return str(ASSETS / '__not_found__')
        return str(target)
    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()
if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8765);args=p.parse_args()
    print(f'MultiSense UI preview: http://127.0.0.1:{args.port} (simulated data only)',flush=True)
    ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
