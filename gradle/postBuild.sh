#!/bin/bash
# postBuild.sh - Fix APK padding caused by AGP zipalign
# This script re-zips the APK to remove empty padding gaps

APK_PATH="$1"

if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
    echo "Usage: postBuild.sh <apk_path>"
    exit 1
fi

FIXED_PATH="${APK_PATH%.apk}-fixed.apk"

python3 << 'PYEOF'
import zipfile
import sys
import os

apk_path = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('APK_PATH', '')
fixed_path = apk_path.replace('.apk', '-fixed.apk') if apk_path else ''

if not apk_path or not os.path.exists(apk_path):
    print(f"Error: APK not found at {apk_path}")
    sys.exit(1)

print(f"Fixing padding in: {apk_path}")
print(f"Original size: {os.path.getsize(apk_path)} bytes")

with zipfile.ZipFile(apk_path, 'r') as zin:
    with zipfile.ZipFile(fixed_path, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))

fixed_size = os.path.getsize(fixed_path)
original_size = os.path.getsize(apk_path)
savings = original_size - fixed_size

print(f"Fixed size: {fixed_size} bytes")
print(f"Savings: {savings} bytes ({savings/1024/1024:.2f} MB)")

# Replace original with fixed
os.replace(fixed_path, apk_path)
print(f"Replaced original APK")
PYEOF
