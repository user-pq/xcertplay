#!/usr/bin/env python3
"""Explicitly authorized E02 platform test variant. Signing stays outside public CI."""
from pathlib import Path
import sys
from apply_xingrui_runtime_patch import replace

def main():
    root=Path(sys.argv[1])
    build=root/'mobile/build.gradle.kts'
    replace(build, 'applicationIdSuffix = ".xingrui"', 'applicationIdSuffix = ".xingrui.platform"')
    replace(build, 'versionNameSuffix = "-xingrui-r5"', 'versionNameSuffix = "-xingrui-r5-platform"')
    replace(build, 'versionCode = 42', 'versionCode = 43')
    manifest=root/'mobile/src/main/AndroidManifest.xml'
    replace(manifest, 'xmlns:tools="http://schemas.android.com/tools">',
        'xmlns:tools="http://schemas.android.com/tools" android:sharedUserId="android.uid.system">')
    replace(manifest, 'android:label="DiPlay 星瑞 R5"', 'android:label="DiPlay 星瑞 平台版"')
    print('E02 platform test variant applied; sign only locally with verified certificate')

if __name__=='__main__': main()
