#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Pinned TornadoVM Maven source rebuild plan; no GPU claim or model extraction."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--scope', choices=('targeted', 'full'), default='targeted')
    parser.add_argument('--build', action='store_true', help='explicit opt-in Maven reactor build')
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    wrapper = root / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    parent = root / 'pom.xml'
    model = root / 'synexia-cpu-model' / 'pom.xml'
    for source in (wrapper, parent, model):
        if not source.is_file() or source.is_symlink():
            raise ValueError('unrecognized TornadoVM checkout')
    command = [str(wrapper), '-B', '-ntp']
    if args.scope == 'targeted':
        command.extend(['-pl', 'synexia-cpu-model', '-am'])
    command.append('verify')
    original = {str(p.relative_to(root)): digest(p) for p in (wrapper, parent, model)}
    if not args.build:
        print(json.dumps({'status': 'PLAN_ONLY', 'command': command,
                          'source_sha256': original, 'model_weights_used': False}, sort_keys=True))
        return
    result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=900, check=False)
    if result.returncode:
        raise RuntimeError('Maven build failed: return code ' + str(result.returncode)
                           + '; tail: ' + result.stdout[-2000:])
    if original != {str(p.relative_to(root)): digest(p) for p in (wrapper, parent, model)}:
        raise RuntimeError('Maven wrapper or POM changed during build')
    receipt = {'status': 'SOURCE_REACTOR_VERIFIED_NO_HARDWARE_PARITY_CLAIM',
               'command': command, 'source_sha256': original,
               'maven_exit': result.returncode, 'scope': args.scope,
               'tornadovm_gpu_parity': 'NOT_RUN', 'model_weights_used': False}
    dest = root / 'target' / 'm3-recompile'
    dest.mkdir(parents=True, exist_ok=True)
    (dest / 'BUILD_RECEIPT.json').write_text(json.dumps(receipt, indent=2, sort_keys=True)+'\n')
    print(json.dumps(receipt, sort_keys=True))


if __name__ == '__main__':
    main()
