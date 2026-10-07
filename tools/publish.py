#!/usr/bin/env python3
"""Publish the reviewed source using an existing GitHub CLI login."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "ZzzXR0305/github-android-chinese-enhancement"
TAG = "v1.279.0-cn2"
FORBIDDEN = {".apk", ".apks", ".aab", ".dex", ".class", ".jar", ".zip", ".keystore", ".jks", ".p12", ".pem", ".key", ".smali", ".idsig"}
NOTES = """正文原位翻译、仓库名称增强搜索，以及可拖动的“译 / 搜”按钮。
长按隐藏后，在同一页面继续停留 5 秒会自动恢复。
59 种语言选项和多语言互译搜索；原版搜索默认保留原入口，菜单可选择接入增强搜索。

当前适配 GitHub Android 1.279.0 / versionCode 950，增强补丁 cn2。
官方原版继续从商店更新；未知新版必须先增加并验证适配 profile。
README 和 tools/updates/README.md 包含本地构建及稳定签名覆盖更新流程。

发行内容是自有开源代码。官方 GitHub APK、反编译源码、翻译模型、账号数据及签名私钥未发布。
"""


def command(args, check=True):
    environment = dict(os.environ, GIT_TERMINAL_PROMPT="0", GH_PROMPT_DISABLED="1")
    result = subprocess.run([str(x) for x in args], cwd=ROOT, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=180, env=environment)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "Command failed")
    return result


def audit():
    dirty = command(["git", "status", "--porcelain"]).stdout
    if dirty.strip():
        raise RuntimeError("Source must be committed and clean before publishing.")
    files = command(["git", "ls-files", "-z"]).stdout.split("\0")
    for name in filter(None, files):
        path = Path(name)
        if path.suffix.lower() in FORBIDDEN or path.name in {"local.properties", ".env", "hosts.yml"}:
            raise RuntimeError("Excluded file is tracked: " + name)
        text = (ROOT / path).read_text(encoding="utf-8")
        if re.search(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:ghp_|github_pat_)[A-Za-z0-9_]{20,}", text):
            raise RuntimeError("Credential-like content in tracked file: " + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Only verify source and authentication")
    args = parser.parse_args()
    gh = shutil.which("gh")
    if not gh:
        print("GitHub CLI is missing. Install it, then run: gh auth login --hostname github.com --git-protocol https --web", file=sys.stderr)
        return 2
    if not shutil.which("git"):
        raise RuntimeError("Git is required")
    audit()
    if command([gh, "auth", "status", "--hostname", "github.com"], check=False).returncode:
        print("No usable GitHub login. Run: gh auth login --hostname github.com --git-protocol https --web", file=sys.stderr)
        return 2
    login = command([gh, "api", "user", "--jq", ".login"]).stdout.strip()
    if login.lower() != REPOSITORY.split("/")[0].lower():
        raise RuntimeError("Logged-in account does not match the reviewed repository owner: " + login)
    remote = command(["git", "remote", "get-url", "origin"], check=False)
    url = "https://github.com/" + REPOSITORY + ".git"
    if remote.returncode == 0 and remote.stdout.strip() != url:
        raise RuntimeError("Existing origin is different; refusing to overwrite it")
    if args.check:
        print("Source and authentication ready: " + REPOSITORY)
        return 0
    existing = command([gh, "repo", "view", REPOSITORY, "--json", "nameWithOwner,isPrivate,viewerPermission"], check=False)
    if existing.returncode == 0:
        info = json.loads(existing.stdout)
        if info.get("isPrivate") or info.get("viewerPermission") not in {"ADMIN", "MAINTAIN", "WRITE"}:
            raise RuntimeError("Existing repository must already be public and writable")
    else:
        # Confirm a genuine 404 before creating; network and permission failures do not authorize a retry.
        probe = command([gh, "api", "repos/" + REPOSITORY], check=False)
        if "HTTP 404" not in probe.stderr and "HTTP 404" not in probe.stdout:
            raise RuntimeError("Cannot confirm repository absence: " + (probe.stderr or probe.stdout).strip())
        command([gh, "repo", "create", REPOSITORY, "--public", "--description",
                 "GitHub Android 正文原位翻译、增强搜索与可移动工具按钮；自有开源补丁及版本适配工具"])
    if remote.returncode:
        command(["git", "remote", "add", "origin", url])
    # Configure credentials for this push only; never write a token or change global Git settings.
    helper = "!" + ('"' + str(gh).replace("\\", "/") + '"') + " auth git-credential"
    command(["git", "-c", "credential.https://github.com.helper=", "-c",
             "credential.https://github.com.helper=" + helper, "push", "-u", "origin", "main"])
    release = command([gh, "release", "view", TAG, "--repo", REPOSITORY], check=False)
    if release.returncode:
        with tempfile.TemporaryDirectory(prefix="github-cn-release-") as temporary:
            notes = Path(temporary) / "notes.md"
            notes.write_text(NOTES, encoding="utf-8")
            command([gh, "release", "create", TAG, "--repo", REPOSITORY, "--target", "main",
                     "--title", "GitHub 中文增强 1.279.0-cn2", "--notes-file", notes])
    remote_head = command([gh, "api", "repos/" + REPOSITORY + "/git/ref/heads/main", "--jq", ".object.sha"]).stdout.strip()
    local_head = command(["git", "rev-parse", "HEAD"]).stdout.strip()
    if remote_head != local_head:
        raise RuntimeError("Remote commit does not match reviewed source")
    print("Published and verified: https://github.com/" + REPOSITORY)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
