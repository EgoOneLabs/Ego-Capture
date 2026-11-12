#!/usr/bin/env python3
"""Verify the published code16 source and native build inputs without a phone."""
import hashlib
import json
from pathlib import Path
import re
import sys


def verify(root):
    root = Path(root).resolve()
    manifest = json.loads((root / 'docs/code16-source-manifest.json').read_text())
    problems = []
    for name, expected in manifest['files'].items():
        path = (root / name).resolve()
        if root not in path.parents or not path.is_file():
            problems.append('Missing/unsafe source: ' + name)
        elif hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            problems.append('Source differs from code16 baseline: ' + name)
    for name in ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar',
                 'gradle/wrapper/gradle-wrapper.properties', 'settings.gradle.kts',
                 'build.gradle.kts', 'third_party/uvcandroid/LICENSE-APACHE-2.0.txt',
                 'third_party/uvcandroid/LICENSE-libjpeg-turbo.md',
                 'third_party/uvcandroid/LICENSE-libuvc.txt']:
        if not (root / name).is_file():
            problems.append('Missing build/license input: ' + name)
    jni = root / 'third_party/uvcandroid/libuvccamera/src/main/jni'
    for name in re.findall(r'include\s+\$\(PROJ_PATH\)/([^\r\n]+)', (jni / 'Android.mk').read_text()):
        if not (jni / name.strip()).is_file():
            problems.append('Missing native makefile: ' + name)
    if problems:
        raise ValueError('\n'.join(problems))
    return {'status': 'SOURCE_AND_LOCAL_BUILD_INPUTS_VERIFIED',
            'baseline_files': len(manifest['files']),
            'external_toolchain': 'JDK17, SDK35, NDK27.3.13750724; Gradle/Maven downloads need network',
            'android_build_run': False, 'device_acceptance_run': False}


if __name__ == '__main__':
    try:
        print(json.dumps(verify(Path(__file__).resolve().parents[1]), indent=2))
    except Exception as exc:
        sys.exit(str(exc))
