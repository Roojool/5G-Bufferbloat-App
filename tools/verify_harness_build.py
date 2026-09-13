"""Check built artifacts and actual CMake NDK selection; no device/network access."""
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
NDK = "28.2.13676358"


def main():
    for variant, native_variant, enabled in (("debug", "Debug", "ON"), ("release", "RelWithDebInfo", "OFF")):
        for abi in ABIS:
            candidates = list((ROOT / "app/.cxx" / native_variant).glob(f"*/{abi}/CMakeCache.txt"))
            assert candidates, (variant, abi, "missing CMake cache")
            cache = max(candidates, key=lambda p: p.stat().st_mtime_ns)
            text = cache.read_text()
            match = re.search(r"^CMAKE_ANDROID_NDK:[^=]+=(.+)$", text, re.MULTILINE)
            assert match, "missing selected NDK"
            ndk_path = Path(match.group(1).strip())
            props = (ndk_path / "source.properties").read_text()
            assert re.search(r"Pkg.Revision\s*=\s*" + re.escape(NDK) + r"\s*$", props, re.MULTILINE)
            assert f"BB_BUILD_SOCKET_HARNESS:BOOL={enabled}" in text
            print(f"{variant}/{abi}: selected NDK={NDK}, harness={enabled}, cache={cache.relative_to(ROOT)}")
        apk = ROOT / f"app/build/outputs/apk/{variant}/app-{variant}{'-unsigned' if variant == 'release' else ''}.apk"
        with zipfile.ZipFile(apk) as archive:
            entries = set(archive.namelist())
            for abi in ABIS:
                assert f"lib/{abi}/libbufferbloat_native_engine.so" in entries
                present = f"lib/{abi}/libbufferbloat_socket_harness.so" in entries
                assert present == (variant == "debug"), (variant, abi, "incorrect harness packaging")
            manifest = archive.read("AndroidManifest.xml")
            components = ("HarnessActivity", "HarnessService", "BatchHarnessActivity")
            if variant == "release":
                for encoding in ("utf-8", "utf-16-le"):
                    for component in components:
                        assert component.encode(encoding) not in manifest
            else:
                for component in components:
                    assert any(component.encode(encoding) in manifest for encoding in ("utf-8", "utf-16-le")), component
        print(f"{variant}: three production stub libraries; debug harness packaging/manifest gate PASS")


if __name__ == "__main__":
    main()
