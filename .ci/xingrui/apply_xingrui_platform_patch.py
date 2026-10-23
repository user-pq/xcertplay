#!/usr/bin/env python3
"""Explicitly authorized E02 platform test variant. Signing stays outside public CI."""
from pathlib import Path
import sys
from apply_xingrui_runtime_patch import replace

def main():
    root=Path(sys.argv[1])
    build=root/'mobile/build.gradle.kts'
    replace(build, 'applicationIdSuffix = ".xingrui"', 'applicationIdSuffix = ".xingrui.platform"')
    replace(build, 'versionNameSuffix = "-xingrui-r6"', 'versionNameSuffix = "-xingrui-r6-platform"')
    replace(build, 'versionCode = 43', 'versionCode = 44')
    manifest=root/'mobile/src/main/AndroidManifest.xml'
    replace(manifest, 'xmlns:tools="http://schemas.android.com/tools">',
        'xmlns:tools="http://schemas.android.com/tools" android:sharedUserId="android.uid.system">')
    replace(manifest, 'android:label="DiPlay 星瑞 R6"', 'android:label="DiPlay 星瑞 平台版"')
    common=root/'common/src/main/AndroidManifest.xml'
    replace(common, '    <application>', '    <uses-permission android:name="android.permission.CHANGE_COMPONENT_ENABLED_STATE" tools:ignore="ProtectedPermissions" />\n    <application>')
    replace(common, '    </application>', '''        <receiver
            android:name="com.shilapi.xcertplay.XingruiGuardBootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    </application>''')
    print('E02 platform test variant applied; sign only locally with verified certificate')

if __name__=='__main__': main()
