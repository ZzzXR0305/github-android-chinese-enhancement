#!/usr/bin/env python3
"""Validate every known hook before creating a separate patched decode tree.

Only patch metadata and our hook calls are distributed; the user supplies the APK.
Unknown versions are intentionally rejected rather than guessed.
"""
import argparse
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", ANDROID[1:-1])


class ValidationError(RuntimeError):
    def __init__(self, report):
        self.report = report
        super().__init__("Preflight failed: " + "; ".join(report["errors"]))


def version_info(root):
    text = (Path(root) / "apktool.yml").read_text(encoding="utf-8")
    def value(name):
        found = re.search(r"(?m)^\s*" + name + r":\s*([^\r\n]+)", text)
        if not found:
            raise ValueError("apktool.yml is missing " + name)
        return found.group(1).strip().strip("'\"")
    return {"version_code": int(value("versionCode")), "version_name": value("versionName")}


def make_plan(root, profile, patch_version="cn2"):
    root = Path(root).resolve()
    errors, hooks, edits, moves = [], [], {}, {}
    report = {"profile": profile.get("version_name"), "errors": errors, "hooks": hooks}
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,32}", patch_version):
        errors.append("Patch version must use 1-32 letters, digits, dots, underscores, or hyphens")
        raise ValidationError(report)
    try:
        version = version_info(root)
        report.update(version)
        if version != {"version_code": profile["version_code"], "version_name": profile["version_name"]}:
            errors.append("Unsupported version: expected " + str(profile["version_name"]) + "/" + str(profile["version_code"]) + ", got " + str(version))
        manifest = ET.parse(root / "AndroidManifest.xml")
        document = manifest.getroot()
        if document.get("package") != profile["original_package"]:
            errors.append("Expected an untouched official package " + profile["original_package"] + "; already patched trees are not accepted")
        application = document.find("application")
        if application is None or application.get(ANDROID + "name") != profile["application"]:
            errors.append("Manifest Application does not match the supported profile")
        main = document.find("./application/activity[@" + ANDROID + "name='" + profile["main_activity"] + "']")
        if main is None or not any(action.get(ANDROID + "name") == "android.intent.action.MAIN" for action in main.findall("./intent-filter/action")):
            errors.append("Profile MainActivity is not a declared launcher entry")
    except (OSError, ValueError, ET.ParseError) as error:
        errors.append("Version/manifest inspection failed: " + str(error))
        raise ValidationError(report)
    if errors:
        raise ValidationError(report)

    directories = [item for item in root.iterdir() if item.is_dir() and re.fullmatch(r"smali(?:_classes\d+)?", item.name)]
    by_class = {}
    for directory in directories:
        for file in directory.rglob("*.smali"):
            descriptor = "L" + file.relative_to(directory).as_posix()[:-6] + ";"
            by_class.setdefault(descriptor, []).append(file)
    cache = {}

    def locate(descriptor):
        files = by_class.get(descriptor, [])
        if len(files) != 1:
            raise ValueError(descriptor + ": expected one class across all DEX files, found " + str(len(files)))
        path = files[0]
        text = cache.setdefault(path, path.read_text(encoding="utf-8"))
        if not re.search(r"(?m)^\.class [^\n]* " + re.escape(descriptor) + r"$", text):
            raise ValueError(str(path.relative_to(root)) + ": descriptor/path mismatch")
        return path, edits.get(path.relative_to(root), text)

    def extends(descriptor, expected):
        seen = set()
        while descriptor != expected and descriptor not in seen:
            seen.add(descriptor)
            _, text = locate(descriptor)
            found = re.search(r"(?m)^\.super (L[^\n]+;)$", text)
            if not found:
                return False
            descriptor = found.group(1)
        return descriptor == expected

    moved_classes = set()
    for hook in profile["hooks"]:
        try:
            path, text = locate(hook["class"])
            if "Lcn/local/githubcn/" in text:
                raise ValueError("existing payload calls detected; use a fresh official decode")
            if hook.get("extends") and not extends(hook["class"], hook["extends"]):
                raise ValueError("class inheritance does not reach " + hook["extends"])
            methods = list(re.finditer(r"(?m)^\.method ([^\n]+)\n[\s\S]*?^\.end method", text))
            matches = [method for method in methods if method.group(1).split()[-1] == hook["method"]]
            if len(matches) != 1:
                raise ValueError("expected one method " + hook["method"] + ", found " + str(len(matches)))
            method = matches[0]
            body = method.group(0)
            for feature in hook.get("requires", []):
                if body.count(feature) != 1:
                    raise ValueError("structural feature missing or ambiguous: " + feature)
            if "anchor_regex" in hook:
                anchors = list(re.finditer(hook["anchor_regex"], body))
            else:
                anchors = list(re.finditer(re.escape(hook["anchor"]), body))
            if len(anchors) != 1:
                raise ValueError("expected one hook anchor, found " + str(len(anchors)))
            point = method.start() + anchors[0].end()
            edits[path.relative_to(root)] = text[:point] + "\n\n    " + hook["insert"] + text[point:]
            moved_classes.add(path)
            hooks.append({"id": hook["id"], "class": hook["class"], "file": path.relative_to(root).as_posix(), "matched": True})
        except (OSError, ValueError) as error:
            errors.append(hook["id"] + ": " + str(error))
            hooks.append({"id": hook["id"], "matched": False})
    for descriptor, patterns in profile["runtime_members"].items():
        try:
            _, text = locate(descriptor)
            for pattern in patterns:
                if len(re.findall(pattern, text)) != 1:
                    raise ValueError("reflective member missing or ambiguous: " + pattern)
        except (OSError, ValueError) as error:
            errors.append("Compose runtime " + descriptor + ": " + str(error))
    for item in profile["identity_literals"]:
        try:
            path, text = locate(item["class"])
            count = text.count(item["old"])
            if count != item["count"]:
                raise ValueError("identity literal count: expected " + str(item["count"]) + ", found " + str(count))
            edits[path.relative_to(root)] = text.replace(item["old"], item["new"])
        except (OSError, ValueError) as error:
            errors.append("Runtime identity " + item["class"] + ": " + str(error))

    authenticator = Path("res/xml/authenticator.xml")
    try:
        auth = ET.parse(root / authenticator)
        if auth.getroot().get(ANDROID + "accountType") != profile["original_package"]:
            errors.append("Authenticator accountType does not match the untouched original")
        auth.getroot().set(ANDROID + "accountType", profile["clone_package"])
        edits[authenticator] = ET.tostring(auth.getroot(), encoding="unicode", xml_declaration=True)
    except (OSError, ET.ParseError) as error:
        errors.append("Authenticator inspection failed: " + str(error))
    original, clone = profile["original_package"], profile["clone_package"]
    for suffix in ("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "ahp-attachments", "androidx-startup", "firebaseinitprovider"):
        old_value = original + "." + suffix
        count = 0
        for element in document.iter():
            for key, value in list(element.attrib.items()):
                if value == old_value:
                    element.set(key, clone + "." + suffix)
                    count += 1
        if not count:
            errors.append("Required manifest identity missing: " + old_value)
    document.set("package", clone)
    application.set(ANDROID + "label", "GitHub 中文增强")
    permission = ET.SubElement(document, "uses-permission")
    permission.set(ANDROID + "name", "cn.local.repochinese.TRANSLATE")
    queries = document.find("queries")
    if queries is None:
        queries = ET.SubElement(document, "queries")
    ET.SubElement(queries, "package").set(ANDROID + "name", "cn.local.repochinese")
    ET.SubElement(queries, "package").set(ANDROID + "name", original)
    activity = ET.SubElement(application, "activity")
    activity.set(ANDROID + "name", "cn.local.githubcn.SearchActivity")
    activity.set(ANDROID + "exported", "false")
    update = ET.SubElement(application, "activity")
    update.set(ANDROID + "name", "cn.local.githubcn.UpdateActivity")
    update.set(ANDROID + "exported", "false")
    edits[Path("AndroidManifest.xml")] = ET.tostring(document, encoding="unicode", xml_declaration=True)
    clone_version = profile["version_name"] + "-" + patch_version
    yaml = (root / "apktool.yml").read_text(encoding="utf-8")
    edits[Path("apktool.yml")] = re.sub(r"(?m)^(\s*versionName:\s*).*$", lambda match: match.group(1) + clone_version, yaml)
    index = max([1 if item.name == "smali" else int(item.name.split("classes")[1]) for item in directories] or [1]) + 1
    destination = "smali_classes" + str(index)
    for source in moved_classes:
        relative = source.relative_to(root)
        moves[relative] = Path(destination, *relative.parts[1:])
    report.update({"valid": not errors, "clone_version": clone_version, "modified_files": len(edits), "relocated_classes": len(moves), "relocation_dex": index})
    if errors:
        raise ValidationError(report)
    return {"source": root, "edits": edits, "moves": moves, "report": report}


def apply_plan(plan, output):
    output, source = Path(output).resolve(), plan["source"]
    if output == source or source in output.parents:
        raise ValueError("Patched output must be a separate tree, outside the official decode")
    repository = Path(__file__).resolve().parents[2]
    if output == repository or repository in output.parents:
        raise ValueError("Keep decompiled APK trees outside the public source repository")
    if output.exists():
        raise ValueError("Patched output already exists; choose a new directory")
    shutil.copytree(source, output)
    for relative, text in plan["edits"].items():
        (output / relative).write_text(text, encoding="utf-8")
    for old, new in plan["moves"].items():
        target = output / new
        target.parent.mkdir(parents=True, exist_ok=True)
        (output / old).rename(target)
    return output


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--decoded", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--output", type=Path, help="Create a new patched tree; omitted means read-only validation")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--patch-version", default="cn2")
    args = parser.parse_args()
    try:
        if args.report and (args.report.resolve() == args.decoded.resolve() or args.decoded.resolve() in args.report.resolve().parents):
            raise ValueError("Diagnostic report must stay outside the untouched official decode")
        plan = make_plan(args.decoded, json.loads(args.profile.read_text(encoding="utf-8")), args.patch_version)
        if args.output:
            apply_plan(plan, args.output)
        report, code = plan["report"], 0
    except ValidationError as error:
        report, code = error.report, 2
    except Exception as error:
        report, code = {"valid": False, "errors": [str(error)]}, 2
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        args.report.write_text(text + "\n", encoding="utf-8")
    print(text)
    return code


if __name__ == "__main__":
    sys.exit(main())
