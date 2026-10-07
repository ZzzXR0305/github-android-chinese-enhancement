#!/usr/bin/env python3
"""Build the synthetic Android checks; --run explicitly opts into one device run."""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

sys.dont_write_bytecode = True
REPOSITORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPOSITORY / "tools/updates"))
from build import executable, installed_certificate, run, sign

TEST_PACKAGE = "cn.local.githubcn.check"
CLONE_PACKAGE = "com.github.android.chinese"
RUNNER = TEST_PACKAGE + "/" + TEST_PACKAGE + ".ImmersiveCheck"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def resolve_tools(args):
    """Tests need only the SDK/JDK; the shared APK updater additionally requires Apktool."""
    sdk_value = args.sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_value:
        raise ValueError("Set ANDROID_SDK_ROOT or provide --sdk")
    sdk = Path(sdk_value).expanduser().resolve()
    java_value = args.java_home or os.environ.get("JAVA_HOME")
    java_found = shutil.which("java") if not java_value else None
    java_bin = Path(java_value).expanduser().resolve() / "bin" if java_value else Path(java_found).resolve().parent if java_found else None
    if java_bin is None:
        raise ValueError("Set JAVA_HOME or provide --java-home (JDK 17)")
    candidates = [path for path in (sdk / "build-tools").glob("*") if path.is_dir() and re.fullmatch(r"\d+(?:\.\d+)+", path.name)]
    if not candidates:
        raise ValueError("Android SDK build-tools are missing")
    build_tools = sdk / "build-tools" / args.build_tools if args.build_tools else max(candidates, key=lambda path: tuple(map(int, path.name.split("."))))
    platforms = [path for path in (sdk / "platforms").glob("android-*") if (path / "android.jar").is_file() and path.name[8:].isdigit() and int(path.name[8:]) >= 36]
    if not platforms:
        raise ValueError("Android SDK platform 36 or newer is required")
    tools = {name: executable(java_bin / name) for name in ("java", "javac", "jar")}
    tools.update({name: executable(build_tools / name) for name in ("aapt2", "zipalign")})
    tools["android_jar"] = max(platforms, key=lambda path: int(path.name[8:])) / "android.jar"
    for name in ("d8", "apksigner"):
        tools[name] = build_tools / "lib" / (name + ".jar")
        if not tools[name].is_file():
            raise ValueError("SDK tool is missing: " + str(tools[name]))
    adb = args.adb or shutil.which("adb")
    if not adb and args.run:
        adb = executable(sdk / "platform-tools/adb")
    tools["adb"] = [str(adb)] + (["-s", args.serial] if args.serial else []) if adb else None
    return tools


def build_checks(tools, work, args):
    source = REPOSITORY / "tests/android"
    manifest = source / "AndroidManifest.xml"
    document = ET.parse(manifest).getroot()
    instrumentation = document.find("instrumentation")
    if document.get("package") != TEST_PACKAGE or instrumentation is None or instrumentation.get(ANDROID + "targetPackage") != CLONE_PACKAGE or instrumentation.get(ANDROID + "name") != TEST_PACKAGE + ".ImmersiveCheck":
        raise ValueError("Unexpected test package/runner/target in AndroidManifest.xml")
    files = sorted((source / "src").rglob("*.java"))
    if not files:
        raise ValueError("Android test sources are missing")
    classes, dex = work / "classes", work / "dex"
    classes.mkdir(); dex.mkdir()
    run([tools["javac"], "-J-Dfile.encoding=UTF-8", "--release", "8", "-encoding", "UTF-8", "-cp", tools["android_jar"], "-d", classes] + files)
    jar = work / "checks.jar"
    run([tools["jar"], "cf", jar, "-C", classes, "."])
    run([tools["java"], "-cp", tools["d8"], "com.android.tools.r8.D8", "--min-api", "29", "--lib", tools["android_jar"], "--output", dex, jar])
    unsigned = work / "unsigned.apk"
    run([tools["aapt2"], "link", "-o", unsigned, "--manifest", manifest, "-I", tools["android_jar"], "--min-sdk-version", "29", "--target-sdk-version", "36"])
    with zipfile.ZipFile(unsigned, "a") as apk:
        apk.write(dex / "classes.dex", "classes.dex", compress_type=zipfile.ZIP_STORED)
    output = work / "instrumentation.apk"
    fingerprint = sign(tools, unsigned, output, args)
    return output, fingerprint


def run_checks(tools, apk, fingerprint, work):
    installed, metadata = installed_certificate(tools, CLONE_PACKAGE, work)
    if installed is None:
        raise ValueError("The enhanced clone must already be installed; this tool installs only the temporary tests")
    if installed != fingerprint or metadata.get("name") != CLONE_PACKAGE:
        raise ValueError("Test signing key does not match the installed clone; no test APK was installed")
    installed_test = False
    try:
        run(tools["adb"] + ["install", "-r", apk])
        installed_test = True
        command = tools["adb"] + ["shell", "am", "instrument", "-w", "-r", RUNNER]
        # The runner has a 180s deadline; allow a short ADB startup/teardown margin.
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", timeout=190)
        (work / "instrumentation-result.txt").write_text(result.stdout, encoding="utf-8")
        print(result.stdout.strip())
        if result.returncode or not re.search(r"(?m)^INSTRUMENTATION_RESULT: passed=true\s*$", result.stdout) or not re.search(r"(?m)^INSTRUMENTATION_CODE: -1\s*$", result.stdout):
            raise RuntimeError("Device checks did not report passed=true / RESULT_OK; see instrumentation-result.txt")
    finally:
        if installed_test:
            run(tools["adb"] + ["uninstall", TEST_PACKAGE])


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", help="Android SDK; otherwise ANDROID_SDK_ROOT or ANDROID_HOME")
    parser.add_argument("--java-home", help="JDK 17 directory; otherwise JAVA_HOME or PATH")
    parser.add_argument("--build-tools", help="SDK build-tools version; otherwise newest stable installed version")
    parser.add_argument("--keystore", required=True, type=Path, help="Clone's original signing key outside this repository")
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--store-pass-env", default="GITHUBCN_KEYSTORE_PASSWORD")
    parser.add_argument("--key-pass-env", default="GITHUBCN_KEY_PASSWORD")
    parser.add_argument("--work-dir", type=Path, help="New output directory outside this repository; defaults to system temporary directory")
    parser.add_argument("--adb", help="ADB executable, needed only with --run")
    parser.add_argument("--serial", help="ADB device serial, needed only with --run")
    parser.add_argument("--run", action="store_true", help="Verify clone signer, install/run tests, then uninstall only cn.local.githubcn.check")
    args = parser.parse_args()
    work = None
    try:
        tools = resolve_tools(args)
        args.keystore = args.keystore.expanduser().resolve()
        if REPOSITORY == args.keystore or REPOSITORY in args.keystore.parents or not args.keystore.is_file():
            raise ValueError("Provide an existing signing keystore outside this repository")
        for name in (args.store_pass_env, args.key_pass_env):
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) or name not in os.environ:
                raise ValueError("Set password environment variable " + name + "; passwords are not printed")
        work = args.work_dir.expanduser().resolve() if args.work_dir else Path(tempfile.mkdtemp(prefix="githubcn-checks-"))
        if work == REPOSITORY or REPOSITORY in work.parents:
            raise ValueError("Test APKs and build cache must stay outside this repository")
        if args.work_dir:
            work.mkdir(parents=True, exist_ok=False)
        print("Private test work directory: " + str(work))
        apk, fingerprint = build_checks(tools, work, args)
        if args.run:
            run_checks(tools, apk, fingerprint, work)
        report = {"apk": str(apk), "certificate_sha256": fingerprint, "device_checks_passed": bool(args.run), "device_accessed": bool(args.run)}
        (work / "check-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report, indent=2))
        return 0
    except Exception as error:
        print("Device check tool failed: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
