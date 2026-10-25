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
    classes, methods = {}, {}
    for hook in profile["hooks"]:
        anchor = hook.get("anchor", "invoke-virtual/range {v0 .. v5}, Landroid/webkit/WebView;->loadDataWithBaseURL(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
        if hook["id"] in ("web_before_load", "legacy_before_load"):
            anchor = "move-result-object v0\n\n    invoke-virtual {p0, v0}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V"
        if hook["id"] in ("web_after_load", "legacy_after_load"):
            anchor += "\n\n    invoke-virtual {p0, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V"
        body = "\n".join(feature for feature in hook.get("requires", []) if feature not in anchor) + "\n" + anchor
        classes.setdefault(hook["class"], ".super " + hook.get("extends", "Ljava/lang/Object;") + "\n")
        key = (hook["class"], hook["method"])
        methods[key] = methods.get(key, "") + body + "\n"
    for (descriptor, method), body in methods.items():
        classes[descriptor] += ".method public final " + method + "\n" + body + "\n.end method\n"
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
        assert len(plan["report"]["hooks"]) == len(profile["hooks"]) == 11
        assert {"web_before_load", "legacy_before_load", "web_after_load", "legacy_after_load"}.issubset({hook["id"] for hook in plan["report"]["hooks"]})
        assert plan["report"]["relocated_classes"] == 7
        output = apply_plan(plan, parent / "patched")
        assert fingerprint(root) == before
        manifest = ET.parse(output / "AndroidManifest.xml").getroot()
        assert manifest.get("package") == profile["clone_package"]
        assert manifest.find("./application/activity[@" + ANDROID + "name='cn.local.githubcn.UpdateActivity']") is not None
        assert manifest.find("./application/activity[@" + ANDROID + "name='cn.local.githubcn.SearchActivity']").get(ANDROID + "exported") == "false"
        assert "versionName: 1.279.0-cn2" in (output / "apktool.yml").read_text()
        for name in ("f", "LegacyGitHubWebView"):
            hooked = next(output.glob("smali*/com/github/android/webview/viewholders/" + name + ".smali")).read_text()
            assert "->attach(Landroid/webkit/WebView;)V" in hooked and "->beforeLoad(" in hooked
            before = hooked.index("->beforeLoad(")
            assert before < hooked.index("Landroid/webkit/WebView;->loadUrl", before)
            after_call = "invoke-static {p0, v0}, Lcn/local/githubcn/Immersive;->afterLoad(Landroid/webkit/WebView;Ljava/lang/String;)Ljava/lang/String;"
            assert hooked.count(after_call) == 1
            after = hooked.index(after_call)
            initialize = "invoke-direct {v1}, Ljava/lang/Object;-><init>()V"
            start = hooked.rfind(initialize, 0, after)
            evaluate = hooked.index("invoke-virtual {p0, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript", after)
            assert hooked.index("Landroid/webkit/WebView;->loadUrl", before) < start < after < evaluate
            assert [line.strip() for line in hooked[start:evaluate].splitlines() if line.strip()] == [initialize, after_call, "move-result-object v0"]
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
