#!/usr/bin/env bash
# apply_compass.sh: Applies isolated compass-interception patches to decoded smali trees.
# Usage: patches/compass/apply_compass.sh [smali_root_dir]
# If no smali_root_dir is given, defaults to build/work/smali.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SMALI_ROOT="${1:-"$(cd "$SCRIPT_DIR/../../build/work/smali" && pwd)"}"

echo "[compass] Verifying isolated patches for $SMALI_ROOT..."

if [ ! -d "$SMALI_ROOT" ]; then
    echo "[compass] Error: target directory $SMALI_ROOT does not exist." >&2
    exit 1
fi

DIFF_1="$SCRIPT_DIR/01-classes12-oymb.diff"
DIFF_2="$SCRIPT_DIR/02-classes3-gqg.diff"

for diff_file in "$DIFF_1" "$DIFF_2"; do
    if [ ! -f "$diff_file" ]; then
        echo "[compass] Error: diff file $diff_file not found." >&2
        exit 1
    fi
done

FILE_1="$SMALI_ROOT/classes12/app/morphe/extension/youtube/patches/OverrideYouTubeMusicButtonsPatch.smali"
FILE_2="$SMALI_ROOT/classes3/gqg.smali"

# 1. Reject preexisting rejects before mutation (preserving existing files)
PREEXISTING_REJ=$(find "$SMALI_ROOT" -name "*.rej" 2>/dev/null | head -n 1)
if [ -n "$PREEXISTING_REJ" ]; then
    echo "[compass] Error: preexisting reject file detected: $PREEXISTING_REJ. Failing closed before mutation." >&2
    exit 1
fi

# Target files must exist
if [ ! -f "$FILE_1" ] || [ ! -f "$FILE_2" ]; then
    echo "[compass] Error: required target files do not exist in $SMALI_ROOT." >&2
    exit 1
fi

# Expected SHA-256 hashes
H1_BASE="f0394c46697e964dc5841af346fc8e7b673e5836d3128ac7489ab87b2fa6a4ff"
H1_PATCH="8d0bd60772a4802dcaabe6918a109c1f11048da0414f381bf93eb1c16188ac3c"

H2_BASE="e610afa9bdbd6dc9e98d1e1287b52cb716e2f248c14827470e6125dcf97c5bd1"
H2_PATCH="148f4ff1cc62137bc71cefb5fc1b99adbd9cc05d90f634a129096a8fbb769ba5"

H1_CUR=$(sha256sum "$FILE_1" | awk '{print $1}')
H2_CUR=$(sha256sum "$FILE_2" | awk '{print $1}')

# Check for repeat application: both files already fully patched
if [ "$H1_CUR" = "$H1_PATCH" ] && [ "$H2_CUR" = "$H2_PATCH" ]; then
    echo "[compass] Both target files already match verified patched SHA-256 hashes."
    echo "[compass] Repeat application is safe: no mutation needed."
    exit 0
fi

# Never accept partial state
if [ "$H1_CUR" = "$H1_PATCH" ] || [ "$H2_CUR" = "$H2_PATCH" ]; then
    echo "[compass] Error: detected partial patch state in $SMALI_ROOT. Failing closed before mutation." >&2
    exit 1
fi

# Both files must match clean baseline
if [ "$H1_CUR" != "$H1_BASE" ] || [ "$H2_CUR" != "$H2_BASE" ]; then
    echo "[compass] Error: target files do not match clean baseline SHA-256 hashes. Failing closed before mutation." >&2
    exit 1
fi

# Stage in temp directory for atomic mutation and validation
TMP_STAGE=$(mktemp -d /tmp/compass_stage.XXXXXX)
trap 'rm -rf "$TMP_STAGE"' EXIT

mkdir -p "$TMP_STAGE/classes12/app/morphe/extension/youtube/patches"
mkdir -p "$TMP_STAGE/classes3"

cp "$FILE_1" "$TMP_STAGE/classes12/app/morphe/extension/youtube/patches/OverrideYouTubeMusicButtonsPatch.smali"
cp "$FILE_2" "$TMP_STAGE/classes3/gqg.smali"

echo "[compass] Applying isolated patches in staging directory..."
if ! ( cd "$TMP_STAGE" && patch -p1 --forward --batch --no-backup-if-mismatch < "$DIFF_1" ); then
    echo "[compass] Error: 01-classes12-oymb.diff failed in staging. Failing closed." >&2
    exit 1
fi

if ! ( cd "$TMP_STAGE" && patch -p1 --forward --batch --no-backup-if-mismatch < "$DIFF_2" ); then
    echo "[compass] Error: 02-classes3-gqg.diff failed in staging. Failing closed." >&2
    exit 1
fi

# Verify no rejects created in staging
STAGE_REJ=$(find "$TMP_STAGE" -name "*.rej" | head -n 1)
if [ -n "$STAGE_REJ" ]; then
    echo "[compass] Error: reject file produced in staging: $STAGE_REJ. Failing closed." >&2
    exit 1
fi

# Verify staged patched hashes
H1_STAGED=$(sha256sum "$TMP_STAGE/classes12/app/morphe/extension/youtube/patches/OverrideYouTubeMusicButtonsPatch.smali" | awk '{print $1}')
H2_STAGED=$(sha256sum "$TMP_STAGE/classes3/gqg.smali" | awk '{print $1}')

if [ "$H1_STAGED" != "$H1_PATCH" ] || [ "$H2_STAGED" != "$H2_PATCH" ]; then
    echo "[compass] Error: staged files hash verification failed. Failing closed." >&2
    exit 1
fi

# Land atomically
cp "$TMP_STAGE/classes12/app/morphe/extension/youtube/patches/OverrideYouTubeMusicButtonsPatch.smali" "$FILE_1"
cp "$TMP_STAGE/classes3/gqg.smali" "$FILE_2"

echo "[compass] Isolated compass patches applied and verified atomically via SHA-256 hashes."
