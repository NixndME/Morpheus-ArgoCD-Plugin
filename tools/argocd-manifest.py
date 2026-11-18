#!/usr/bin/env python3
"""Bundle an Argo CD release into the plugin: download its install.yaml and save it as JSON.

  python3 tools/argocd-manifest.py v3.6.1

Writes plugin/src/main/resources/argocd/install.json (a JSON list of Kubernetes objects).
The plugin reads the Argo CD version from the argocd image tag in that file.
"""
import json, os, sys, urllib.request
import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'plugin/src/main/resources/argocd/install.json')

version = sys.argv[1] if len(sys.argv) > 1 else sys.exit(__doc__)
url = f'https://raw.githubusercontent.com/argoproj/argo-cd/{version}/manifests/install.yaml'
text = urllib.request.urlopen(url, timeout=60).read().decode()
docs = [d for d in yaml.safe_load_all(text) if d]
images = sorted({c['image'] for d in docs for c in (d.get('spec', {}).get('template', {}).get('spec', {}).get('containers') or [])})
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, 'w') as f:
    json.dump(docs, f, separators=(',', ':'))
print(f'{len(docs)} objects, {os.path.getsize(OUT)} bytes -> {OUT}')
print('images:', ', '.join(images))
