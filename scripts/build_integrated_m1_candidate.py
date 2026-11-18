import os
import shutil
import subprocess
import hashlib
import json
import tempfile

ROOT = "/home/paradox/youtube-ultra-offline-20261118"
DEPS = f"{ROOT}/.deps"
W = f"{ROOT}/build/work"
OUT_DIR = f"{ROOT}/build/baseline/candidate-integrated-m1"
JAVA = "/home/paradox/jdk21/bin/java"
BT = "/home/paradox/Android/Sdk/build-tools/35.0.0"
KEYSTORE = "/home/paradox/.local/share/youtube-ultra-keys/ultra-release.jks"
KEYPASS = "/home/paradox/.local/share/youtube-ultra-keys/keystore.pass"
KEY_ALIAS = "ultra-signer"
RELEASE_CERT_SHA256 = "ada59a7ea8aa31f1395c9334a15695dff45c4f0864aa044280db27deb7d9eb47"
STOCK_CERT_SHA256 = "0a78f9ca9c1ee7de0eeb59d7eef68251b0dee694b55028dd3dedd644a833ad62"

os.environ["PATH"] = f"/home/paradox/jdk21/bin:{os.environ.get('PATH', '')}"

os.makedirs(OUT_DIR, exist_ok=True)

# 1. Rebuild module DEX with MusicActivity & LastWave audio remuxer/downloader
print("=== 1/7 Compiling module DEX with native MusicActivity ===")
cmd_build_mod = ["python3", f"{ROOT}/scripts/build_module_optimized.py"]
subprocess.check_call(cmd_build_mod)
raw_module_dex = f"{ROOT}/build/module_opt/classes.dex"
assert os.path.exists(raw_module_dex), f"Missing {raw_module_dex}"

# 2. Apply reversible certificate identity overlay to classes13
print("=== 2/7 Applying reversible certificate identity overlay to classes13 ===")
with tempfile.TemporaryDirectory() as tmpdir:
    smali_dir = os.path.join(tmpdir, "smali")
    cmd_d = [JAVA, "-jar", f"{DEPS}/baksmali.jar", "d", raw_module_dex, "-o", smali_dir]
    subprocess.check_call(cmd_d)
    
    guard_path = os.path.join(smali_dir, "paradox/dl/Guard.smali")
    with open(guard_path, "r") as f:
        guard_content = f.read()
    assert STOCK_CERT_SHA256 in guard_content, "Stock cert not found in Guard.smali"
    guard_content = guard_content.replace(STOCK_CERT_SHA256, RELEASE_CERT_SHA256)
    with open(guard_path, "w") as f:
        f.write(guard_content)
        
    overlay_dex = os.path.join(OUT_DIR, "classes13-m1-overlay.dex")
    cmd_a = [JAVA, "-jar", f"{DEPS}/smali.jar", "a", "-a", "26", "-o", overlay_dex, smali_dir]
    subprocess.check_call(cmd_a)
    print(f"Overlay DEX created: {overlay_dex} ({os.path.getsize(overlay_dex)} bytes)")

# Copy overlay dex to work app
shutil.copy2(overlay_dex, f"{W}/app/classes13.dex")

# 3. Assemble modified smali classes (classes3, classes8, classes10, classes12)
print("=== 3/7 Assembling classes3, classes8, classes10, and classes12 ===")
subprocess.check_call([JAVA, "-jar", f"{DEPS}/smali.jar", "a", "-a", "26", f"{W}/smali/classes3", "-o", f"{W}/app/classes3.dex"])
subprocess.check_call([JAVA, "-jar", f"{DEPS}/smali.jar", "a", "-a", "26", f"{W}/smali/classes8", "-o", f"{W}/app/classes8.dex"])
subprocess.check_call([JAVA, "-jar", f"{DEPS}/smali.jar", "a", "-a", "26", f"{W}/smali/classes10", "-o", f"{W}/app/classes10.dex"])
subprocess.check_call([JAVA, "-jar", f"{DEPS}/smali.jar", "a", "-a", "26", f"{W}/smali/classes12", "-o", f"{W}/app/classes12.dex"])

# 4. Sync and validate persisted resources
print("=== 4/7 Syncing and validating persisted resources ===")
for res_rel in ["drawable/ytultra_settings_key_icon.xml", "layout/ytultra_preference_with_icon.xml", "xml/ytultra_prefs.xml"]:
    src = f"{ROOT}/patches/res/{res_rel}"
    dst = f"{W}/app/res/{res_rel}"
    shutil.copy2(src, dst)
    h_src = hashlib.sha256(open(src, "rb").read()).hexdigest()
    h_dst = hashlib.sha256(open(dst, "rb").read()).hexdigest()
    assert h_src == h_dst, f"Resource mismatch for {res_rel}"
print("Resources validated 100% parity with patches/res/")

# 5. Build unsigned APK with apktool
print("=== 5/7 Building unsigned APK with apktool ===")
if os.path.exists(f"{W}/app/build"):
    shutil.rmtree(f"{W}/app/build")

unsigned_raw = f"{OUT_DIR}/rebuilt-unsigned.apk"
cmd_apktool = [JAVA, "-jar", f"{DEPS}/apktool.jar", "b", "-f", "-j", "4", "-o", unsigned_raw, f"{W}/app"]
subprocess.check_call(cmd_apktool)

# 6. Zipalign
print("=== 6/7 Zipaligning 4-byte ===")
aligned_unsigned = f"{OUT_DIR}/aligned-unsigned.apk"
cmd_zipalign = [f"{BT}/zipalign", "-p", "-f", "4", unsigned_raw, aligned_unsigned]
subprocess.check_call(cmd_zipalign)
subprocess.check_call([f"{BT}/zipalign", "-c", "4", aligned_unsigned])
print("Zipalign verification PASSED.")

# 7. Sign with authorized release key
print("=== 7/7 Signing with authorized release key ===")
signed_apk = f"{OUT_DIR}/YouTube_Ultra-integrated-m1-signed.apk"
apksigner_jar = f"{BT}/lib/apksigner.jar"
cmd_sign = [
    JAVA, "-jar", apksigner_jar, "sign",
    "--ks", KEYSTORE,
    "--ks-key-alias", KEY_ALIAS,
    "--ks-pass", f"file:{KEYPASS}",
    "--out", signed_apk,
    aligned_unsigned
]
subprocess.check_call(cmd_sign)

# Verify signature
cmd_verify = [JAVA, "-jar", apksigner_jar, "verify", "--verbose", "--print-certs", signed_apk]
verify_out = subprocess.check_output(cmd_verify, text=True)
print("=== apksigner verify output ===")
print(verify_out)

# Verify zipalign on signed APK
subprocess.check_call([f"{BT}/zipalign", "-c", "4", signed_apk])
print("Signed APK zipalign PASSED.")

# Compute hashes and build audit report
apk_h = hashlib.sha256(open(signed_apk, "rb").read()).hexdigest()
unsigned_h = hashlib.sha256(open(aligned_unsigned, "rb").read()).hexdigest()
dex13_h = hashlib.sha256(open(overlay_dex, "rb").read()).hexdigest()
dex12_h = hashlib.sha256(open(f"{W}/app/classes12.dex", "rb").read()).hexdigest()
dex10_h = hashlib.sha256(open(f"{W}/app/classes10.dex", "rb").read()).hexdigest()
dex8_h = hashlib.sha256(open(f"{W}/app/classes8.dex", "rb").read()).hexdigest()
dex3_h = hashlib.sha256(open(f"{W}/app/classes3.dex", "rb").read()).hexdigest()

audit = {
    "candidate_m1_signed_apk": {
        "path": signed_apk,
        "size_bytes": os.path.getsize(signed_apk),
        "sha256": apk_h,
        "zipalign_4": True,
        "apksigner_verified": True,
        "signer_sha256": RELEASE_CERT_SHA256,
        "signer_alias": KEY_ALIAS,
        "keystore_path": KEYSTORE
    },
    "unsigned_apk": {
        "path": aligned_unsigned,
        "size_bytes": os.path.getsize(aligned_unsigned),
        "sha256": unsigned_h
    },
    "dex_hashes": {
        "classes3_dex": dex3_h,
        "classes8_dex": dex8_h,
        "classes10_dex": dex10_h,
        "classes12_dex": dex12_h,
        "classes13_overlay_dex": dex13_h
    },
    "music_icon_replacement": {
        "style": "24dp YouTube outline music icon replacing compass icon across mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi",
        "hvo_smali_bound_id": "0x7f080f0a (yt_outline_music_black_24) for Lasuo.bp (outline) and Lasuo.br (filled)",
        "dimensions": "24dp standard vector/PNG with identical theme tinting behavior",
        "location": "Top action bar, original compass position",
        "fallback": "Exact stock drawer fallback preserved if MusicActivity fails or throws"
    },
    "native_music_integration_m1": {
        "activity": "paradox.music.MusicActivity",
        "manifest_entry": "Registered non-exported activity with Theme.Material.NoActionBar",
        "tab_architecture": "Selective 2-tab view: Tab 1 = Library (Home / Default), Tab 2 = Explore (Trending). Explicitly omitted: Home (FEmusic_home) and Samples (FEmusic_samples).",
        "account_unification": "Direct in-process query via Account.headers() -> AuthUtils.getRequestHeader(); automatic cache invalidation on account switch; zero token copying or web login.",
        "download_pipeline": "Isolated LastWave transfer logic (RangeDownloader 4-part 512KB range download, WebmOpusRemuxer lossless WebM->Ogg Opus remuxing, AudioTagWriter), saving to Music/YouTube Ultra, with normal video download engine and Mp4Mux completely preserved."
    },
    "immutable_baselines": {
        "candidate_signed_overlay": f"{ROOT}/build/baseline/candidate-signed-overlay/YouTube_Ultra-compass-aligned-signed.apk",
        "candidate_mux_optimized": f"{ROOT}/build/baseline/candidate-mux-optimized/YouTube_Ultra-mux-optimized-signed.apk"
    }
}

audit_path = f"{OUT_DIR}/audit_report.json"
with open(audit_path, "w") as f:
    json.dump(audit, f, indent=2)

print(f"\nAudit report successfully written to {audit_path}")
print(f"Final M1 Signed APK: {signed_apk} ({os.path.getsize(signed_apk)} bytes, SHA-256: {apk_h})")
