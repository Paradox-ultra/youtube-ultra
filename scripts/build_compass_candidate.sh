#!/usr/bin/env bash
# scripts/build_compass_candidate.sh: Explicit isolated reproducible build entrypoint for compass candidate.
# Applies patches/compass/apply_compass.sh, compiles DEX, applies resources, builds unsigned APK, and zipaligns.
# Strictly NO signing.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS="$ROOT/.deps"
W="$ROOT/build/work"
OUT_DIR="${1:-"$ROOT/build/baseline/candidate-rebuilt"}"
JAVA="${JAVA:-/home/paradox/jdk21/bin/java}"
BT="${ANDROID_BUILD_TOOLS:-/home/paradox/Android/Sdk/build-tools/35.0.0}"

echo "=== 1/5 Applying isolated compass patches via patches/compass/apply_compass.sh ==="
"$ROOT/patches/compass/apply_compass.sh" "$W/smali"

echo "=== 2/5 Reassembling modified smali classes (classes3 and classes12) ==="
"$JAVA" -jar "$DEPS/smali.jar" a -a 26 "$W/smali/classes3" -o "$W/app/classes3.dex"
"$JAVA" -jar "$DEPS/smali.jar" a -a 26 "$W/smali/classes12" -o "$W/app/classes12.dex"

echo "=== 3/5 Resource application, synchronization & validation ==="
# 1. If tree has not been branded, run full apply_resources.py
if grep -q 'android:label="@string/morphe_custom_branding_name_custom"' "$W/app/AndroidManifest.xml"; then
    echo "[compass-build] Running full apply_resources.py on unbranded tree..."
    python3 "$ROOT/patches/apply_resources.py" "$W/app"
fi

# 2. Unconditionally sync all persisted custom resource files from patches/res/ into $W/app/res/
echo "[compass-build] Syncing persisted custom resources from patches/res/ to $W/app/res/..."
cp "$ROOT/patches/res/drawable/ytultra_settings_key_icon.xml" "$W/app/res/drawable/ytultra_settings_key_icon.xml"
cp "$ROOT/patches/res/layout/ytultra_preference_with_icon.xml" "$W/app/res/layout/ytultra_preference_with_icon.xml"
cp "$ROOT/patches/res/xml/ytultra_prefs.xml" "$W/app/res/xml/ytultra_prefs.xml"

# 3. Validate that $W/app/res matches patches/res exactly (fail-closed if any mismatch)
for res_rel in "drawable/ytultra_settings_key_icon.xml" "layout/ytultra_preference_with_icon.xml" "xml/ytultra_prefs.xml"; do
    SRC="$ROOT/patches/res/$res_rel"
    DST="$W/app/res/$res_rel"
    if [ ! -f "$DST" ]; then
        echo "[compass-build] Error: target resource $DST does not exist! Failing closed." >&2
        exit 1
    fi
    SRC_HASH=$(sha256sum "$SRC" | awk '{print $1}')
    DST_HASH=$(sha256sum "$DST" | awk '{print $1}')
    if [ "$SRC_HASH" != "$DST_HASH" ]; then
        echo "[compass-build] Error: resource hash mismatch for $res_rel!" >&2
        echo "  Source (patches): $SRC_HASH" >&2
        echo "  Target (app):     $DST_HASH" >&2
        exit 1
    fi
done
echo "[compass-build] Resource synchronization verified: 100% hash parity with persisted resources."

echo "=== 4/5 Building unsigned APK with apktool (clean build cache) ==="
rm -rf "$W/app/build"
mkdir -p "$OUT_DIR"
"$JAVA" -jar "$DEPS/apktool.jar" b -f -j 4 -o "$OUT_DIR/rebuilt-unsigned.apk" "$W/app"

echo "=== 5/5 Zipaligning (4-byte alignment, NO signing) ==="
"$BT/zipalign" -p -f 4 "$OUT_DIR/rebuilt-unsigned.apk" "$OUT_DIR/aligned-unsigned.apk"

echo "=== 6/6 Verifying candidate alignment and DEX integrity ==="
"$BT/zipalign" -c 4 "$OUT_DIR/aligned-unsigned.apk"

python3 -c '
import zipfile, hashlib, sys
apk_path, app_dir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk_path) as z:
    for dex_name in ["classes3.dex", "classes12.dex"]:
        apk_h = hashlib.sha256(z.read(dex_name)).hexdigest()
        disk_h = hashlib.sha256(open(f"{app_dir}/{dex_name}", "rb").read()).hexdigest()
        if apk_h != disk_h:
            print(f"[compass-build] ERROR: {dex_name} mismatch: APK={apk_h} disk={disk_h}", file=sys.stderr)
            sys.exit(1)
        print(f"[compass-build] VERIFIED: {dex_name} matches disk ({disk_h})")
' "$OUT_DIR/aligned-unsigned.apk" "$W/app"

echo "[compass-build] SUCCESS: Candidate built, aligned, and hash-verified at $OUT_DIR/aligned-unsigned.apk"
sha256sum "$OUT_DIR/aligned-unsigned.apk"
