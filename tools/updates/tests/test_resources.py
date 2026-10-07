"""Resource preservation checks using synthetic tables, never official APK data."""
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from resources import _rename_package, preserve_resources


def table(package="com.github.android"):
    name = package.encode("utf-16le").ljust(256, b"\0")
    chunk = struct.pack("<HHII", 0x0200, 288, 296, 0x7f) + name + b"\0" * 20 + b"sentinel"
    return struct.pack("<HHII", 0x0002, 12, len(chunk) + 12, 1) + chunk


def archive(path, entries):
    with zipfile.ZipFile(path, "w") as output:
        for name, value in entries.items():
            output.writestr(name, value)


class ResourcesCheck(unittest.TestCase):
    def test_only_package_field_changes(self):
        before = table()
        after = _rename_package(before, "com.github.android", "cn.local.githubcn")
        self.assertEqual(before[:24], after[:24])
        self.assertEqual(before[280:], after[280:])
        self.assertEqual(before, _rename_package(after, "cn.local.githubcn", "com.github.android"))

    def test_unknown_and_truncated_tables_rejected(self):
        for value in (table("unknown.package"), table()[:-1], b"bad"):
            with self.assertRaises(ValueError):
                _rename_package(value, "com.github.android", "cn.local.githubcn")

    def test_archive_preserves_resources_and_changed_authenticator(self):
        with tempfile.TemporaryDirectory() as folder:
            original, rebuilt = Path(folder) / "original.apk", Path(folder) / "rebuilt.apk"
            old = {"resources.arsc": table(), "res/drawable-v24/widget.xml": b"compiled-old-ref", "res/xml/authenticator.xml": b"old-auth", "AndroidManifest.xml": b"old-manifest"}
            new = {"resources.arsc": b"bad-table", "res/drawable/widget.xml": b"null-ref", "res/xml/authenticator.xml": b"clone-auth", "AndroidManifest.xml": b"clone-manifest", "classes.dex": b"new-dex"}
            archive(original, old); archive(rebuilt, new)
            preserve_resources(original, rebuilt, "com.github.android", "cn.local.githubcn")
            with zipfile.ZipFile(rebuilt) as result:
                self.assertEqual(result.read("res/drawable-v24/widget.xml"), old["res/drawable-v24/widget.xml"])
                self.assertNotIn("res/drawable/widget.xml", result.namelist())
                self.assertEqual(result.read("res/xml/authenticator.xml"), b"clone-auth")
                self.assertEqual(result.read("AndroidManifest.xml"), b"clone-manifest")
                self.assertEqual(result.read("classes.dex"), b"new-dex")
                self.assertEqual(result.read("resources.arsc"), _rename_package(old["resources.arsc"], "com.github.android", "cn.local.githubcn"))

    def test_bad_input_leaves_rebuilt_untouched(self):
        with tempfile.TemporaryDirectory() as folder:
            original, rebuilt = Path(folder) / "original.apk", Path(folder) / "rebuilt.apk"
            archive(original, {"resources.arsc": table(), "res/xml/authenticator.xml": b"old"})
            archive(rebuilt, {"AndroidManifest.xml": b"new"})
            before = rebuilt.read_bytes()
            with self.assertRaises(ValueError):
                preserve_resources(original, rebuilt, "com.github.android", "cn.local.githubcn")
            self.assertEqual(rebuilt.read_bytes(), before)

    def test_code_free_split_without_resource_table(self):
        with tempfile.TemporaryDirectory() as folder:
            original, rebuilt = Path(folder) / "original.apk", Path(folder) / "rebuilt.apk"
            archive(original, {"lib/arm64-v8a/lib.so": b"lib"})
            archive(rebuilt, {"AndroidManifest.xml": b"clone-manifest", "lib/arm64-v8a/lib.so": b"lib"})
            preserve_resources(original, rebuilt, "com.github.android", "cn.local.githubcn")
            with zipfile.ZipFile(rebuilt) as result:
                self.assertEqual(result.read("AndroidManifest.xml"), b"clone-manifest")
                self.assertEqual(result.read("lib/arm64-v8a/lib.so"), b"lib")


if __name__ == "__main__":
    unittest.main()
