"""Converts Gradle's module cache into a Maven-layout repository.

Usage: export_m2.py <gradle files-2.1 dir> <output dir>

Used to publish a dependency bundle for development sandboxes that cannot
reach Maven Central reliably.
"""

import pathlib
import shutil
import sys


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
    print(f"exported {count} files to {dst_p}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
