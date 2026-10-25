"""Read-only structural check of the embedded engine container; no device access."""
import hashlib
import struct
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET


def dex_classes(data):
    string_count, strings_offset = struct.unpack_from('<II', data, 56)
    type_count, types_offset = struct.unpack_from('<II', data, 64)
    class_count, classes_offset = struct.unpack_from('<II', data, 96)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from('<I', data, strings_offset + index * 4)[0]
        while data[offset] & 128:
            offset += 1
        offset += 1
        strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', 'replace'))
    types = [strings[struct.unpack_from('<I', data, types_offset + index * 4)[0]]
             for index in range(type_count)]
    return [types[struct.unpack_from('<I', data, classes_offset + index * 32)[0]]
            for index in range(class_count)]


if __name__ == '__main__':
    root = Path(__file__).resolve().parent
    apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
    manifest = root / 'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml'
    application = ET.parse(manifest).getroot().find('application')
    assert not any(application.findall(tag) for tag in ('activity', 'service', 'provider', 'receiver'))
    with zipfile.ZipFile(apk) as archive:
        classes = []
        for name in archive.namelist():
            if name.startswith('classes') and name.endswith('.dex'):
                classes.extend(dex_classes(archive.read(name)))
        assert len(classes) == len(set(classes)), 'Duplicate class definitions'
        assert 'Lcn/local/githubcn/engine/Translation;' in classes
        assert not any('cn/local/repochinese' in name for name in classes)
        assert archive.getinfo('assets/tflite_langid.tflite.jpg').file_size > 300000
        libraries = [name for name in archive.namelist() if name.startswith('lib/')]
        assert set(libraries) == {
            'lib/arm64-v8a/liblanguage_id_l2c_jni.so',
            'lib/arm64-v8a/libtranslate_jni.so',
        }, libraries
        for name in libraries:
            library = archive.read(name)
            assert library[:4] == b'\x7fELF' and struct.unpack_from('<H', library, 18)[0] == 183
    print('Embedded engine container PASS:', len(classes), 'classes, no Android components, arm64 JNI and language asset')
    print('Bytes:', apk.stat().st_size)
    print('SHA256:', hashlib.sha256(apk.read_bytes()).hexdigest())
