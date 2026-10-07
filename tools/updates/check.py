#!/usr/bin/env python3
"""One stdlib check: a supported synthetic tree patches; mismatches never mutate it."""
import hashlib
import json
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from patch import ANDROID, ValidationError, apply_plan, make_plan


def fingerprint(root):
    return {file.relative_to(root).as_posix(): hashlib.sha256(file.read_bytes()).hexdigest() for file in root.rglob("*") if file.is_file()}


def fixture(root, profile):
    (root / "apktool.yml").write_text("version: 3.0.3\nversionInfo:\n  versionCode: 950\n  versionName: 1.279.0\n", encoding="utf-8")
    (root / "AndroidManifest.xml").write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.github.android">
      <permission android:name="com.github.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
      <application android:name="com.github.android.GitHubApplication" android:label="original">
        <activity android:name="com.github.android.main.MainActivity"><intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter></activity>
        <provider android:authorities="com.github.android.ahp-attachments"/><provider android:authorities="com.github.android.androidx-startup"/><provider android:authorities="com.github.android.firebaseinitprovider"/>
      </application></manifest>''', encoding="utf-8")
    auth = root / "res/xml/authenticator.xml"; auth.parent.mkdir(parents=True)
    auth.write_text('<account-authenticator xmlns:android="http://schemas.android.com/apk/res/android" android:accountType="com.github.android"/>', encoding="utf-8")
    classes = {}
    for hook in profile["hooks"]:
        anchor = hook.get("anchor", "invoke-virtual/range {v0 .. v5}, Landroid/webkit/WebView;->loadDataWithBaseURL(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
        body = "\n".join(hook.get("requires", [])) + "\n" + anchor
        classes[hook["class"]] = ".super " + hook.get("extends", "Ljava/lang/Object;") + "\n.method public final " + hook["method"] + "\n" + body + "\n.end method\n"
    classes.update({
        "Lekk;": ".super Ljava/lang/Object;\n.method public final z()Lh6x;\n.end method\n.method public final j(Lqgv;)Ljava/lang/Object;\n.end method\n",
        "Lg52;": ".super Ljava/lang/Object;\n.field public static final b:Lu060;\n",
        "Lh6x;": ".super Ljava/lang/Object;\n.field public b:I\n.method public final b(Ljava/lang/Object;)Lk6m;\n.end method\n",
        "Lqgv;": ".super Ljava/lang/Object;\n"})
    for item in profile["identity_literals"]:
        classes[item["class"]] = ".super Ljava/lang/Object;\n" + ("const-string v0, " + item["old"] + "\n") * item["count"]
    for index, (descriptor, body) in enumerate(classes.items()):
        directory = "smali" if index % 2 == 0 else "smali_classes3"
        file = root / directory / (descriptor[1:-1] + ".smali")
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(".class public final " + descriptor + "\n" + body, encoding="utf-8")


def main():
    profile = json.loads((Path(__file__).parent / "profiles/github-1.279.0.json").read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="githubcn-preflight-check-") as temporary:
        parent = Path(temporary); root = parent / "official"; root.mkdir()
        fixture(root, profile)
        before = fingerprint(root)
        plan = make_plan(root, profile)
        assert fingerprint(root) == before
        assert len(plan["report"]["hooks"]) == 7
        output = apply_plan(plan, parent / "patched")
        assert fingerprint(root) == before
        manifest = ET.parse(output / "AndroidManifest.xml").getroot()
        assert manifest.get("package") == profile["clone_package"]
        assert manifest.find("./application/activity[@" + ANDROID + "name='cn.local.githubcn.UpdateActivity']") is not None
        assert manifest.find("./application/activity[@" + ANDROID + "name='cn.local.githubcn.SearchActivity']").get(ANDROID + "exported") == "false"
        assert "versionName: 1.279.0-cn2" in (output / "apktool.yml").read_text()
        victim = next(root.glob("smali*/xrz.smali"))
        victim.write_text(victim.read_text().replace("const v2, 0x19c39b2c", "const v2, 0x0"), encoding="utf-8")
        broken = fingerprint(root)
        try:
            make_plan(root, profile)
            raise AssertionError("Missing Compose structural feature was accepted")
        except ValidationError as error:
            assert any("compose_description" in problem for problem in error.report["errors"])
        assert fingerprint(root) == broken
        version = root / "apktool.yml"
        version.write_text(version.read_text().replace("950", "951"), encoding="utf-8")
        unknown = fingerprint(root)
        try:
            make_plan(root, profile)
            raise AssertionError("Unknown version was accepted")
        except ValidationError as error:
            assert any("Unsupported version" in problem for problem in error.report["errors"])
        assert fingerprint(root) == unknown
    print("updates check: PASS (supported plan, independent output, unchanged inputs, missing-hook and unknown-version rejection)")


if __name__ == "__main__":
    main()
