"""Converts Gradle's module cache into a Maven-layout repository.

Usage: export_m2.py <gradle files-2.1 dir> <output dir>

Used to publish a dependency bundle for development sandboxes that cannot
reach Maven Central reliably. Gradle caches some files (for example Kotlin
Multiplatform Android variants) under the file *name* from the module
metadata, while repositories serve them under its *url*; both are written.
"""

import json
import os
import pathlib
import shutil
import sys


def fix_module_urls(root: pathlib.Path) -> int:
    """Copies files cached under their metadata name to the url Gradle requests."""
    added = 0
    for module_file in root.rglob("*.module"):
        try:
            meta = json.loads(module_file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        folder = module_file.parent
        for variant in meta.get("variants", []):
            for f in variant.get("files", []):
                name, url = f.get("name"), f.get("url")
                if not name or not url:
                    continue
                # Urls may point into a sibling version folder ("../../33.4.8-android/x.jar").
                target = pathlib.Path(os.path.normpath(folder / url))
                source = folder / name
                if not target.exists() and source.exists():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
                    added += 1
    return added


def main(src: str, dst: str) -> int:
    src_p = pathlib.Path(src).expanduser()
    dst_p = pathlib.Path(dst)
    count = 0
    for f in src_p.glob("*/*/*/*/*"):
        if not f.is_file():
            continue
        group, module, version = f.parts[-5], f.parts[-4], f.parts[-3]
        out = dst_p / group.replace(".", "/") / module / version / f.name
        out.parent.mkdir(parents=True, exist_ok=True)
        if not out.exists():
            shutil.copy2(f, out)
            count += 1
    added = fix_module_urls(dst_p)
    print(f"exported {count} files to {dst_p}, {added} renamed copies")
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 2:
        print("renamed copies:", fix_module_urls(pathlib.Path(sys.argv[1])))
        sys.exit(0)
    sys.exit(main(sys.argv[1], sys.argv[2]))
