#!/usr/bin/env python3
"""Build device-only static Bionic fixtures outside the repository; no product artifacts change."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ndk', required=True, type=Path)
parser.add_argument('--output-dir', required=True, type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
lock = json.loads((repo / 'workspace/proot-lock.json').read_text())
assert 'Pkg.Revision = ' + lock['build']['ndkRevision'] in (args.ndk / 'source.properties').read_text()
output = args.output_dir.resolve()
assert output != repo and repo not in output.parents, 'Fixture output must be outside repository'
output.mkdir(parents=True, exist_ok=True)
toolchain = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
for abi, target in [('x86_64', 'x86_64-linux-android'), ('arm64-v8a', 'aarch64-linux-android')]:
    binary = output / ('proot-behavior-' + abi)
    # posix_spawn requires API 28. This fixture does not change the product's API 24 floor.
    subprocess.run([str(toolchain / (target + '28-clang')), '-static', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-Wl,-z,max-page-size=16384', str(Path(__file__).with_name('proot-behavior-probe.c')),
                    '-o', str(binary)], check=True)
    subprocess.run([str(toolchain / 'llvm-strip'), '--strip-unneeded', str(binary)], check=True)
    print(abi, binary, hashlib.sha256(binary.read_bytes()).hexdigest(), flush=True)
    native = output / ('native-behavior-' + abi)
    subprocess.run([str(toolchain / (target + '28-clang')), '-fPIE', '-pie', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-Wl,-z,max-page-size=16384', str(Path(__file__).with_name('proot-behavior-probe.c')),
                    '-o', str(native)], check=True)
    subprocess.run([str(toolchain / 'llvm-strip'), '--strip-unneeded', str(native)], check=True)
    print(abi, native, hashlib.sha256(native.read_bytes()).hexdigest(), flush=True)
