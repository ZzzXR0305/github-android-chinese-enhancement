#!/usr/bin/env python3
"""Locally reapply a verified profile to user-supplied official APKs.

No official APK, signing key, account, or token is shipped or downloaded by this
tool. --from-device only reads pm paths and pulls installed APK files. Installing
the clone is opt-in; unsupported versions stop before patching or signing.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

from patch import ANDROID, ValidationError, apply_plan, make_plan
from resources import preserve_resources

REPOSITORY = Path(__file__).resolve().parents[2]
HELPER_PACKAGE = "cn.local.repochinese"


def run(command, quiet=False):
    command = [str(part) for part in command]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError("Command failed (" + Path(command[0]).name + "): " + result.stdout.strip())
    if not quiet and result.stdout.strip():
        print(result.stdout.strip())
    return result.stdout


def executable(path):
    path = Path(str(path) + (".exe" if os.name == "nt" else ""))
    if not path.is_file():
        raise ValueError("Required tool not found: " + str(path))
    return path


def resolve_tools(args):
    sdk_value = args.sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_value:
        raise ValueError("Set ANDROID_SDK_ROOT or provide --sdk")
    sdk = Path(sdk_value).expanduser().resolve()
    java_value = args.java_home or os.environ.get("JAVA_HOME")
    java_found = shutil.which("java") if not java_value else None
    java_bin = Path(java_value).expanduser().resolve() / "bin" if java_value else Path(java_found).resolve().parent if java_found else None
    if java_bin is None:
        raise ValueError("Set JAVA_HOME or provide --java-home (JDK 17 required)")
    apktool_value = args.apktool or os.environ.get("APKTOOL_JAR")
    if not apktool_value or not Path(apktool_value).expanduser().is_file():
        raise ValueError("Provide --apktool or APKTOOL_JAR; tested with Apktool 3.0.3")
    candidates = [item for item in (sdk / "build-tools").iterdir() if item.is_dir() and re.fullmatch(r"\d+(?:\.\d+)+", item.name)]
    build_tools = sdk / "build-tools" / args.build_tools if args.build_tools else max(candidates, key=lambda path: tuple(map(int, path.name.split("."))))
    platforms = [item for item in (sdk / "platforms").glob("android-*") if (item / "android.jar").is_file() and item.name[8:].isdigit()]
    android_jar = max(platforms, key=lambda path: int(path.name[8:])) / "android.jar"
    tools = {"java": executable(java_bin / "java"), "javac": executable(java_bin / "javac"), "jar": executable(java_bin / "jar"), "aapt2": executable(build_tools / "aapt2"), "zipalign": executable(build_tools / "zipalign"), "apktool": Path(apktool_value).expanduser().resolve(), "android_jar": android_jar, "d8": build_tools / "lib/d8.jar", "apksigner": build_tools / "lib/apksigner.jar"}
    for name in ("d8", "apksigner"):
        if not tools[name].is_file():
            raise ValueError("SDK is missing " + str(tools[name]))
    adb_value = args.adb or shutil.which("adb")
    if not adb_value and (sdk / "platform-tools").is_dir():
        adb_value = executable(sdk / "platform-tools/adb")
    tools["adb"] = [str(adb_value)] + (["-s", args.serial] if args.serial else []) if adb_value else None
    return tools


def apk_metadata(tools, apk):
    text = run([tools["aapt2"], "dump", "badging", apk], quiet=True)
    package_line = next((line for line in text.splitlines() if line.startswith("package:")), None)
    if package_line is None:
        raise ValueError("Cannot read APK package/version: " + str(apk))
    return dict(re.findall(r"([A-Za-z_]+)='([^']*)'", package_line))


def certificate(tools, apk):
    text = run([tools["java"], "-jar", tools["apksigner"], "verify", "--print-certs", apk], quiet=True)
    fingerprints = re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", text)
    if len(fingerprints) != 1:
        raise ValueError("Expected one verified APK signer: " + str(apk))
    return fingerprints[0].lower()


def paths_from_device(tools, package):
    if not tools["adb"]:
        raise ValueError("ADB is required for --from-device or --install")
    text = run(tools["adb"] + ["shell", "pm", "path", package], quiet=True)
    return [line.strip()[8:] for line in text.splitlines() if line.strip().startswith("package:")]


def extract_official(tools, output):
    paths = paths_from_device(tools, "com.github.android")
    if not paths:
        raise ValueError("Official com.github.android is not installed; update/install it through its official store first")
    output.mkdir()
    files = []
    for source in paths:
        target = output / PurePosixPath(source).name
        if target.exists():
            raise ValueError("Device returned duplicate APK file names")
        run(tools["adb"] + ["pull", source, target], quiet=True)
        files.append(target)
    print("Read-only extraction: " + str(len(files)) + " official installed APK files")
    return files


def decode(tools, apk, output, resources_only=False):
    print("Decoding local APK: " + Path(apk).name, flush=True)
    command = [tools["java"], "-Dfile.encoding=UTF-8", "-jar", tools["apktool"], "d", apk, "-o", output]
    if resources_only:
        command.append("--no-src")
    run(command)


def resource_dump(tools, apk):
    return run([tools["aapt2"], "dump", "resources", apk], quiet=True)


def check_resources(tools, original, rebuilt):
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(rebuilt) as compiled:
        before_table = "resources.arsc" in source.namelist()
        after_table = "resources.arsc" in compiled.namelist()
    if before_table != after_table:
        raise ValueError("Resource table presence changed during APK rebuild")
    if not before_table:
        return
    before, after = resource_dump(tools, original), resource_dump(tools, rebuilt)
    original_ids = set(re.findall(r"resource (0x[0-9a-fA-F]+)", before))
    rebuilt_ids = set(re.findall(r"resource (0x[0-9a-fA-F]+)", after))
    if original_ids != rebuilt_ids:
        raise ValueError("Resource IDs changed during APK rebuild")
    references = set(re.findall(r"@(0x[0-9a-fA-F]+)", before))
    if references - set(re.findall(r"@(0x[0-9a-fA-F]+)", after)):
        raise ValueError("Resource table references disappeared during APK rebuild")


def compile_payload(tools, work, profile, patch_version, repository_name):
    source = REPOSITORY / "payload/src"
    files = [item for item in source.rglob("*.java") if item.name not in ("BuildInfo.java", "SearchQueryCheck.java")]
    required = {"Hooks.java", "Immersive.java", "NativeText.java", "SearchActivity.java", "SearchQuery.java", "UpdateActivity.java"}
    if not required.issubset({item.name for item in files}):
        raise ValueError("Missing own payload sources: " + str(sorted(required - {item.name for item in files})))
    generated = work / "generated/BuildInfo.java"
    generated.parent.mkdir()
    generated.write_text("package cn.local.githubcn;\npublic final class BuildInfo {\n" + "\n".join([
        "public static final String OFFICIAL_VERSION = " + json.dumps(profile["version_name"]) + ";",
        "public static final long OFFICIAL_CODE = " + str(profile["version_code"]) + ";",
        "public static final String PATCH_VERSION = " + json.dumps(patch_version) + ";",
        "public static final String RELEASE_TAG = " + json.dumps("v" + profile["version_name"] + "-" + patch_version) + ";",
        "public static final String REPOSITORY = " + json.dumps(repository_name) + ";", "private BuildInfo() {}"] ) + "\n}\n", encoding="utf-8")
    classes, dex = work / "payload-classes", work / "payload-dex"
    classes.mkdir(); dex.mkdir()
    run([tools["javac"], "-J-Dfile.encoding=UTF-8", "--release", "8", "-encoding", "UTF-8", "-cp", tools["android_jar"], "-d", classes] + files + [generated])
    jar = work / "payload.jar"
    run([tools["jar"], "cf", jar, "-C", classes, "."])
    run([tools["java"], "-cp", tools["d8"], "com.android.tools.r8.D8", "--min-api", "29", "--lib", tools["android_jar"], "--output", dex, jar])
    return dex / "classes.dex"


def inject_payload(base, dex, asset):
    temporary = base.with_suffix(".payload.apk")
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(temporary, "w") as target:
        names = source.namelist()
        indices = [1 if name == "classes.dex" else int(re.fullmatch(r"classes(\d+)\.dex", name).group(1)) for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
        if "assets/github-immersive.js" in names:
            raise ValueError("Payload asset already exists; input was not fresh")
        for entry in source.infolist():
            target.writestr(entry, source.read(entry))
        target.write(dex, "classes" + str(max(indices) + 1) + ".dex", compress_type=zipfile.ZIP_STORED)
        target.write(asset, "assets/github-immersive.js", compress_type=zipfile.ZIP_DEFLATED)
    temporary.replace(base)


def sign(tools, apk, output, args):
    aligned = output.with_suffix(".aligned.apk")
    run([tools["zipalign"], "-f", "-p", "4", apk, aligned])
    command = [tools["java"], "-jar", tools["apksigner"], "sign", "--ks", args.keystore, "--ks-key-alias", args.key_alias,
        "--ks-pass", "env:" + args.store_pass_env, "--key-pass", "env:" + args.key_pass_env, "--out", output, aligned]
    run(command)
    fingerprint = certificate(tools, output)
    aligned.unlink()
    return fingerprint


def installed_certificate(tools, package, work):
    paths = paths_from_device(tools, package)
    if not paths:
        return None, None
    base = next((path for path in paths if PurePosixPath(path).name == "base.apk"), paths[0])
    output = work / (package + "-installed.apk")
    run(tools["adb"] + ["pull", base, output], quiet=True)
    return certificate(tools, output), apk_metadata(tools, output)


def install(tools, signed, helper, fingerprint, profile, work):
    clone_cert, clone_meta = installed_certificate(tools, profile["clone_package"], work)
    if clone_cert is not None and clone_cert != fingerprint:
        raise ValueError("Installed clone uses a different signing key. Keep its original external key; no uninstall or data clearing was performed")
    if clone_meta and int(clone_meta["versionCode"]) > profile["version_code"]:
        raise ValueError("Refusing to downgrade the clone versionCode")
    helper_cert, _ = installed_certificate(tools, HELPER_PACKAGE, work)
    if helper_cert is not None and helper_cert != fingerprint:
        raise ValueError("Installed helper signing certificate differs from the clone key; use the original key for both")
    if helper is None and helper_cert != fingerprint:
        raise ValueError("The same-key helper is not installed. Build the self-owned helper source and supply --helper-apk")
    if helper is not None:
        run(tools["adb"] + ["install", "-r", helper])
    run(tools["adb"] + ["install-multiple", "-r"] + signed)
    print("Clone upgraded with -r; no account files, tokens, uninstall, or data-clear operation was used")


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--apks-dir", type=Path, help="External directory containing original base.apk and every installed split")
    inputs.add_argument("--from-device", action="store_true", help="Read-only pull of all installed official com.github.android APKs")
    parser.add_argument("--work-dir", type=Path, help="New private directory outside this repository; defaults to system temporary directory")
    parser.add_argument("--sdk"); parser.add_argument("--java-home"); parser.add_argument("--apktool")
    parser.add_argument("--build-tools"); parser.add_argument("--adb"); parser.add_argument("--serial")
    parser.add_argument("--keystore", required=True, type=Path, help="Stable signing key outside this repository")
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--store-pass-env", default="GITHUBCN_KEYSTORE_PASSWORD")
    parser.add_argument("--key-pass-env", default="GITHUBCN_KEY_PASSWORD")
    parser.add_argument("--helper-apk", type=Path, help="Self-owned helper APK, re-signed with the same external key")
    parser.add_argument("--patch-version", default=os.environ.get("PATCH_VERSION", "cn2"))
    parser.add_argument("--repository", default="ZzzXR0305/github-android-chinese-enhancement")
    parser.add_argument("--install", action="store_true", help="Opt-in same-package, same-key -r upgrade after every validation passes")
    args = parser.parse_args()
    work = None
    work_ready = False
    try:
        tools = resolve_tools(args)
        args.keystore = args.keystore.expanduser().resolve()
        if REPOSITORY == args.keystore or REPOSITORY in args.keystore.parents or not args.keystore.is_file():
            raise ValueError("A real stable keystore must be supplied outside the source repository")
        for name in (args.store_pass_env, args.key_pass_env):
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) or name not in os.environ:
                raise ValueError("Set password environment variable " + name + "; passwords are never printed or committed")
        if not re.fullmatch(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+", args.repository):
            raise ValueError("--repository must be owner/repository")
        work = args.work_dir.expanduser().resolve() if args.work_dir else Path(tempfile.mkdtemp(prefix="githubcn-local-"))
        if work == REPOSITORY or REPOSITORY in work.parents:
            raise ValueError("Build cache and APKs must stay outside the public repository")
        if args.work_dir:
            work.mkdir(parents=True, exist_ok=False)
        work_ready = True
        print("Private local work directory: " + str(work))
        files = extract_official(tools, work / "official-apks") if args.from_device else sorted(args.apks_dir.expanduser().resolve().glob("*.apk"))
        if not files:
            raise ValueError("No official APKs supplied")
        if not args.from_device:
            for apk in files:
                if REPOSITORY in apk.parents:
                    raise ValueError("Keep official APK inputs outside the public source repository")
        metadata = {file: apk_metadata(tools, file) for file in files}
        bases = [file for file, info in metadata.items() if not info.get("split")]
        if len(bases) != 1:
            raise ValueError("Expected one base APK and its complete split set")
        base, base_info = bases[0], metadata[bases[0]]
        profile_paths = sorted((Path(__file__).parent / "profiles").glob("*.json"))
        profiles = [json.loads(path.read_text(encoding="utf-8")) for path in profile_paths]
        matches = [item for item in profiles if str(item["version_code"]) == base_info.get("versionCode") and item["version_name"] == base_info.get("versionName")]
        if len(matches) != 1:
            raise ValueError("Unsupported official version " + str(base_info.get("versionName")) + "/" + str(base_info.get("versionCode")) + "; no verified hook profile. Nothing was patched, signed, or installed")
        profile = matches[0]
        for file, info in metadata.items():
            if info.get("name") != profile["original_package"] or info.get("versionCode") != str(profile["version_code"]):
                raise ValueError("Package/version mismatch in official APK set: " + file.name)
            if certificate(tools, file) != profile["official_signer_sha256"]:
                raise ValueError("Official APK signing certificate does not match the verified version profile: " + file.name)
        asset = REPOSITORY / "payload/assets/github-immersive.js"
        if not asset.is_file():
            raise ValueError("Self-owned immersive payload asset is missing")
        if args.helper_apk and apk_metadata(tools, args.helper_apk).get("name") != HELPER_PACKAGE:
            raise ValueError("--helper-apk must be the self-owned " + HELPER_PACKAGE + " APK")
        decoded = work / "decoded"; decoded.mkdir()
        decode(tools, base, decoded / "base")
        plan = make_plan(decoded / "base", profile, args.patch_version)
        split_documents = []
        for index, apk in enumerate(file for file in files if file != base):
            directory = decoded / ("split-" + str(index))
            decode(tools, apk, directory, resources_only=True)
            document = ET.parse(directory / "AndroidManifest.xml")
            element, application = document.getroot(), document.getroot().find("application")
            if element.get("package") != profile["original_package"] or not element.get("split") or application is None or application.get(ANDROID + "hasCode") != "false":
                raise ValueError("Unsupported split structure (only code-free official config splits are supported): " + apk.name)
            split_documents.append((apk, directory, document))
        required = set(ET.parse(decoded / "base/AndroidManifest.xml").getroot().get(ANDROID + "requiredSplitTypes", "").split(",")) - {""}
        provided = {kind for _, _, document in split_documents for kind in document.getroot().get(ANDROID + "splitTypes", "").split(",")}
        if required - provided:
            raise ValueError("Incomplete official split set; missing " + str(sorted(required - provided)))
        # All hook, identity, version, signer, source and split checks finish before a decoded tree is patched.
        print("Full preflight passed; compiling self-owned payload", flush=True)
        dex = compile_payload(tools, work, profile, args.patch_version, args.repository)
        (work / "preflight.json").write_text(json.dumps(plan["report"], ensure_ascii=False, indent=2), encoding="utf-8")
        patched = work / "patched"; patched.mkdir()
        apply_plan(plan, patched / "base")
        unsigned = work / "unsigned"; unsigned.mkdir()
        print("Rebuilding verified base and config splits", flush=True)
        run([tools["java"], "-Xmx3g", "-Dfile.encoding=UTF-8", "-jar", tools["apktool"], "b", patched / "base", "-o", unsigned / "base.apk"])
        preserve_resources(base, unsigned / "base.apk", profile["original_package"], profile["clone_package"])
        check_resources(tools, base, unsigned / "base.apk")
        inject_payload(unsigned / "base.apk", dex, asset)
        unsigned_apks = [unsigned / "base.apk"]
        for index, (original, directory, document) in enumerate(split_documents):
            target = patched / ("split-" + str(index)); shutil.copytree(directory, target)
            document.getroot().set("package", profile["clone_package"])
            document.write(target / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)
            yaml = (target / "apktool.yml").read_text(encoding="utf-8")
            if "usesFramework:" not in yaml:
                yaml = yaml.replace("sdkInfo:", "usesFramework:\n  ids:\n  - 1\nsdkInfo:", 1)
                (target / "apktool.yml").write_text(yaml, encoding="utf-8")
            output = unsigned / original.name
            run([tools["java"], "-Dfile.encoding=UTF-8", "-jar", tools["apktool"], "b", target, "-o", output])
            preserve_resources(original, output, profile["original_package"], profile["clone_package"], changed_xml=())
            check_resources(tools, original, output)
            unsigned_apks.append(output)
        for apk in unsigned_apks:
            info = apk_metadata(tools, apk)
            if info.get("name") != profile["clone_package"] or info.get("versionCode") != str(profile["version_code"]):
                raise ValueError("Rebuilt APK package/version validation failed: " + apk.name)
        output_dir = work / "signed"; output_dir.mkdir()
        print("All APK/resource checks passed; signing with the supplied external key", flush=True)
        signed, fingerprints = [], set()
        for apk in unsigned_apks:
            output = output_dir / apk.name
            fingerprints.add(sign(tools, apk, output, args)); signed.append(output)
        helper = None
        if args.helper_apk:
            helper = output_dir / "repo-chinese-helper.apk"
            fingerprints.add(sign(tools, args.helper_apk, helper, args))
        if len(fingerprints) != 1:
            raise ValueError("Clone/helper signing certificate mismatch")
        fingerprint = fingerprints.pop()
        report = {"supported_official_version": profile["version_name"], "clone_version": plan["report"]["clone_version"], "version_code": profile["version_code"], "clone_package": profile["clone_package"], "certificate_sha256": fingerprint, "original_resources_preserved": True, "payload_dex_sha256": hashlib.sha256(dex.read_bytes()).hexdigest(), "immersive_asset_sha256": hashlib.sha256(asset.read_bytes()).hexdigest(), "apks": [{"name": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()} for apk in signed], "installed": False}
        if args.install:
            install(tools, signed, helper, fingerprint, profile, work)
            report["installed"] = True
        (work / "build-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print("Ready local signed APK set: " + str(output_dir))
        return 0
    except Exception as error:
        report = error.report if isinstance(error, ValidationError) else {"valid": False, "errors": [str(error)], "installed": False}
        if work_ready and work and work.is_dir():
            (work / "failure-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
