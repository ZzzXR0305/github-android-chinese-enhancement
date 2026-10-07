"""Preserve official split resources while retaining reviewed identity XML edits."""
import os
import re
import struct
import tempfile
import zipfile
from pathlib import Path


def _rename_package(table, original_package, clone_package):
    """Change only the fixed-width ResTable_package name (not its contents)."""
    replacement = clone_package.encode("utf-16le")
    if "\0" in clone_package or len(replacement) >= 256:
        raise ValueError("Clone resource package name exceeds its UTF-16 field")
    result = bytearray(table)

    def header(offset, end):
        if offset + 8 > end:
            raise ValueError("Truncated resource chunk header")
        kind, size, length = struct.unpack_from("<HHI", table, offset)
        if size < 8 or length < size or offset + length > end:
            raise ValueError("Invalid resource chunk bounds")
        return kind, size, length

    kind, root_header, total = header(0, len(table))
    if kind != 0x0002 or total != len(table):
        raise ValueError("Expected one complete Android resource table")
    if root_header < 12:
        raise ValueError("Truncated resource table header")
    count, offset, changed_at = 0, root_header, None
    while offset < total:
        kind, size, length = header(offset, total)
        if kind == 0x0200:
            if size < 268:
                raise ValueError("Truncated resource package header")
            package_id = struct.unpack_from("<I", table, offset + 8)[0]
            name_bytes = table[offset + 12:offset + 268]
            name = name_bytes.decode("utf-16le").split("\0", 1)[0]
            if package_id == 0x7f and name == original_package:
                result[offset + 12:offset + 268] = replacement.ljust(256, b"\0")
                count += 1
                changed_at = offset + 12
        offset += length
    if count != 1:
        raise ValueError("Expected one exact original resource package; found " + str(count))
    if result[:changed_at] != table[:changed_at] or result[changed_at + 256:] != table[changed_at + 256:]:
        raise ValueError("Resource data changed outside the package-name field")
    return bytes(result)


def preserve_resources(original, rebuilt, original_package, clone_package,
                       changed_xml=("res/xml/authenticator.xml",)):
    """Restore the official table/files, except explicitly changed compiled XML."""
    original, rebuilt = Path(original), Path(rebuilt)
    if original.resolve() == rebuilt.resolve():
        raise ValueError("Original and rebuilt APK must be separate files")
    changed_xml = set(changed_xml)
    if any(not name.startswith("res/") or not name.endswith(".xml") for name in changed_xml):
        raise ValueError("Only reviewed compiled resource XML can be changed")
    temporary = None
    try:
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(rebuilt) as compiled:
            source_names, target_names = set(source.namelist()), set(compiled.namelist())
            if len(source_names) != len(source.infolist()) or len(target_names) != len(compiled.infolist()):
                raise ValueError("Duplicate ZIP entries are unsupported")
            table = (_rename_package(source.read("resources.arsc"), original_package, clone_package)
                     if "resources.arsc" in source_names else None)
            replacements = {}
            for name in changed_xml & source_names:
                target = name if name in target_names else re.sub(r"-v\d+(?=/)", "", name)
                if target not in target_names:
                    raise ValueError("Missing rebuilt identity XML: " + name)
                replacements[name] = compiled.read(target)
            fd, temporary = tempfile.mkstemp(prefix="resource-preserve-", suffix=".apk", dir=rebuilt.parent)
            os.close(fd)
            with zipfile.ZipFile(temporary, "w") as output:
                for entry in compiled.infolist():
                    if not entry.filename.startswith("res/") and entry.filename != "resources.arsc":
                        output.writestr(entry, compiled.read(entry))
                for entry in source.infolist():
                    if entry.filename.startswith("res/"):
                        output.writestr(entry, replacements.get(entry.filename, source.read(entry)))
                    elif entry.filename == "resources.arsc":
                        output.writestr(entry, table)
        os.replace(temporary, rebuilt)
        temporary = None
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)
