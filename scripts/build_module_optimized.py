import os
import shutil
import subprocess
import glob
import zipfile

ROOT = "/home/paradox/youtube-ultra-offline-20261118"
DEPS = f"{ROOT}/.deps"
OUT = f"{ROOT}/build/module_opt"
SDK = "/home/paradox/Android/Sdk"
AJ = f"{SDK}/platforms/android-35/android.jar"
D8 = f"{SDK}/build-tools/35.0.0/lib/d8.jar"
JAVA = "/home/paradox/jdk21/bin/java"
JAVAC = "/home/paradox/jdk21/bin/javac"

os.environ["PATH"] = f"/home/paradox/jdk21/bin:{os.environ.get('PATH', '')}"

assert os.path.exists(AJ), f"Missing {AJ}"
assert os.path.exists(D8), f"Missing {D8}"

LIBS = [
    f"{DEPS}/libs/extractor.jar",
    f"{DEPS}/libs/nanojson.jar",
    f"{DEPS}/libs/jsoup.jar",
    f"{DEPS}/libs/protobuf-javalite.jar",
    f"{DEPS}/libs/rhino.jar",
    f"{DEPS}/libs/jsr305.jar"
]
CP = ":".join(LIBS)
JJ = ":".join(glob.glob(f"{DEPS}/jarjar/*"))

if os.path.exists(OUT):
    shutil.rmtree(OUT)
os.makedirs(f"{OUT}/classes", exist_ok=True)
os.makedirs(f"{OUT}/combined", exist_ok=True)
os.makedirs(f"{OUT}/dex", exist_ok=True)
os.makedirs(f"{OUT}/tools", exist_ok=True)

# 1. Compile Java sources
print("1. Compiling module/src...")
java_srcs = []
for root, _, files in os.walk(f"{ROOT}/module/src"):
    for f in files:
        if f.endswith(".java"):
            java_srcs.append(os.path.join(root, f))

cmd_javac = [JAVAC, "-nowarn", "--release", "8", "-cp", f"{AJ}:{CP}", "-d", f"{OUT}/classes"] + java_srcs
subprocess.check_call(cmd_javac)

# 2. Extract libs and combine with compiled classes
print("2. Combining classes and libraries...")
comb_dir = f"{OUT}/combined"
for jar in LIBS:
    with zipfile.ZipFile(jar, "r") as z:
        for info in z.infolist():
            if info.filename.startswith("META-INF/") or info.filename == "module-info.class":
                continue
            z.extract(info, comb_dir)

# Copy compiled classes into combined
for root, _, files in os.walk(f"{OUT}/classes"):
    rel = os.path.relpath(root, f"{OUT}/classes")
    dst_dir = os.path.join(comb_dir, rel) if rel != "." else comb_dir
    os.makedirs(dst_dir, exist_ok=True)
    for f in files:
        if f != "module-info.class":
            shutil.copy2(os.path.join(root, f), os.path.join(dst_dir, f))

# Create combined.jar
comb_jar = f"{OUT}/combined.jar"
with zipfile.ZipFile(comb_jar, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(comb_dir):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, comb_dir)
            z.write(full, rel)

# 3. JarJar shading
print("3. Running JarJar rules...")
shaded_jar = f"{OUT}/shaded.jar"
cmd_jj = [JAVA, "-cp", JJ, "org.pantsbuild.jarjar.Main", "process", f"{ROOT}/module/jarjar-rules.txt", comb_jar, shaded_jar]
subprocess.check_call(cmd_jj)

# 4. Remap and Check tools
print("4. Remapping classes...")
cmd_tools_javac = [JAVAC, "-cp", JJ, "-d", f"{OUT}/tools", f"{ROOT}/module/tools/Remap.java", f"{ROOT}/module/tools/Check.java"]
subprocess.check_call(cmd_tools_javac)

shaded2_jar = f"{OUT}/shaded2.jar"
cmd_remap = [JAVA, "-cp", f"{JJ}:{OUT}/tools", "Remap", shaded_jar, shaded2_jar]
subprocess.check_call(cmd_remap)

# 5. D8 Dexing
print("5. Running D8...")
cmd_d8 = [JAVA, "-cp", D8, "com.android.tools.r8.D8", "--release", "--min-api", "26", "--lib", AJ, "--output", f"{OUT}/dex", shaded2_jar]
subprocess.check_call(cmd_d8)

dex_file = f"{OUT}/dex/classes.dex"
assert os.path.exists(dex_file), "D8 failed to output classes.dex"
shutil.copy2(dex_file, f"{OUT}/classes.dex")
print(f"Build successful! Output classes.dex size: {os.path.getsize(f'{OUT}/classes.dex')} bytes")
