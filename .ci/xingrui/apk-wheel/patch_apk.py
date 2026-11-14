#!/usr/bin/env python3
"""Patch one known APK. Resources, manifest, native libraries and assets stay byte-identical.
Never signs, downloads, uploads, or changes system-UID permissions.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

INPUT_SHA256 = '885dd544394c8d0e9f96050c090a4ec587217a57dca27d9808513d436ae41672'
APKTOOL_SHA256 = 'dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'
HOST = 'com/ganfandw/carplay/CarPlayHostActivity.smali'
BRIDGE = 'Lcom/ganfandw/carplay/patch/XingruiWheelBridge;'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def hook(text, signature, insertion, anchor=None):
    pattern = r'(?m)^\.method ' + re.escape(signature) + r'\n.*?^\.end method$'
    matches = list(re.finditer(pattern, text, re.S | re.M))
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one method: {signature}; got {len(matches)}')
    match = matches[0]
    body = match.group()
    if anchor:
        if body.count(anchor) != 1:
            raise ValueError(f'Expected one anchor inside {signature}')
        replacement = body.replace(anchor, anchor + insertion, 1)
    else:
        replacement, count = re.subn(r'(    \.locals \d+\n)', lambda m: m[1] + insertion, body, count=1)
        if count != 1:
            raise ValueError('Missing locals declaration')
    return text[:match.start()] + replacement + text[match.end():]

def patch_host(text):
    text = hook(text, 'private final startMediaGuard()V',
        f'\n    invoke-static {{p0}}, {BRIDGE}->start(Ljava/lang/Object;)V\n',
        '    :cond_0\n')
    text = hook(text, 'private final stopMediaGuard()V',
        f'\n    invoke-static {{p0}}, {BRIDGE}->stop(Ljava/lang/Object;)V\n')
    text = hook(text, 'protected onDestroy()V',
        f'\n    invoke-static {{p0}}, {BRIDGE}->stop(Ljava/lang/Object;)V\n')
    text = hook(text, 'public dispatchKeyEvent(Landroid/view/KeyEvent;)Z', f'''
    invoke-static {{p0, p1}}, {BRIDGE}->dispatch(Ljava/lang/Object;Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :xingrui_original_dispatch
    return v0
    :xingrui_original_dispatch
''')
    return text

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True, help='New empty scratch directory')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--apktool', type=Path, required=True)
    parser.add_argument('--d8-classpath', type=Path, required=True, help='JADX 1.5.6 all jar (contains D8 9.1.31)')
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        raise ValueError('Output must not replace original APK')
    assert digest(args.input.read_bytes()) == INPUT_SHA256, 'Unknown input APK; refusing to patch'
    assert digest(args.apktool.read_bytes()) == APKTOOL_SHA256, 'Unexpected Apktool jar'
    if args.work.exists():
        raise ValueError('Scratch directory already exists; choose a new --work path')
    args.work.mkdir(parents=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    java = str(args.java_home / 'bin/java')
    javac = str(args.java_home / 'bin/javac')
    run = lambda cmd: subprocess.run(cmd, check=True)
    decoded = args.work / 'decoded'
    run([java, '-jar', str(args.apktool), 'd', '-r', '--no-assets', '-o', str(decoded), str(args.input)])
    target = decoded / 'smali_classes2' / HOST
    before = {str(p.relative_to(decoded)): digest(p.read_bytes()) for p in decoded.glob('smali*/**/*.smali')}
    target.write_text(patch_host(target.read_text()))
    changed = [name for name, value in before.items() if digest((decoded / name).read_bytes()) != value]
    assert changed == ['smali_classes2/' + HOST], changed
    run([java, '-jar', str(args.apktool), 'b', '--no-apk', str(decoded)])
    classes = args.work / 'classes'
    classes.mkdir()
    sources = sorted((Path(__file__).parent / 'src').rglob('*.java'))
    run([javac, '--release', '8', '-d', str(classes)] + [str(p) for p in sources])
    class_jar = args.work / 'bridge.jar'
    with zipfile.ZipFile(class_jar, 'w') as z:
        for p in sorted(classes.rglob('*.class')):
            z.write(p, str(p.relative_to(classes)))
    dex = args.work / 'dex'
    dex.mkdir()
    run([java, '-cp', str(args.d8_classpath), 'com.android.tools.r8.D8', '--release', '--min-api', '25',
         '--output', str(dex), str(class_jar)])
    new_dex = (dex / 'classes.dex').read_bytes()
    replacement = (decoded / 'build/apk/classes2.dex').read_bytes()
    # Repack original entries, replacing only classes2.dex and adding classes4.dex.
    # APK v2/v3 signing blocks are intentionally absent: an authorized platform signer is still required.
    manifest = []
    removed = []
    with zipfile.ZipFile(args.input) as source, zipfile.ZipFile(args.output, 'w') as output:
        assert 'classes4.dex' not in source.namelist()
        for entry in source.infolist():
            if re.match(r'META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|MANIFEST.MF)$', entry.filename, re.I):
                removed.append(entry.filename)
                continue
            original = source.read(entry.filename)
            payload = replacement if entry.filename == 'classes2.dex' else original
            # Stored native libraries must remain page aligned; manifest/resources need 4-byte alignment.
            info = zipfile.ZipInfo(entry.filename, entry.date_time)
            info.compress_type = entry.compress_type
            info.external_attr = entry.external_attr
            info.create_system = entry.create_system
            if info.compress_type == zipfile.ZIP_STORED:
                alignment = 4096 if info.filename.endswith('.so') else 4
                offset = output.fp.tell() + 30 + len(info.filename.encode('utf-8'))
                padding = (-offset) % alignment
                if 0 < padding < 4:
                    padding += alignment
                if padding:
                    info.extra = struct.pack('<HH', 0xd935, padding - 4) + bytes(padding - 4)
            output.writestr(info, payload)
            manifest.append({'entry': entry.filename, 'original_sha256': digest(original),
                'patched_sha256': digest(payload), 'changed': payload != original})
        output.writestr('classes4.dex', new_dex, compress_type=zipfile.ZIP_DEFLATED)
    assert [m['entry'] for m in manifest if m['changed']] == ['classes2.dex']
    report = {'input_sha256': INPUT_SHA256, 'output_sha256': digest(args.output.read_bytes()),
        'signed': False, 'requires': 'Matching authorized E02 platform signing certificate; then vehicle test',
        'changed_original_smali': changed, 'added_dex': 'classes4.dex',
        'removed_obsolete_v1_entries': removed, 'entries': manifest}
    args.output.with_suffix('.verification.json').write_text(json.dumps(report, indent=2))
    print('Patched unsigned APK:', args.output)
    print('Preserved every original ZIP payload except classes2.dex; added classes4.dex. Platform signature required.')

if __name__ == '__main__':
    main()
