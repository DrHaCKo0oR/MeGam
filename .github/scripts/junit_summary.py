"""Prints a compact summary of JUnit XML results (failures with messages)."""
import glob
import sys
import xml.etree.ElementTree as ET

total = failed = skipped = 0
for path in sorted(glob.glob(sys.argv[1] + "/**/*.xml", recursive=True)):
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        total += 1
        for kind in ("failure", "error"):
            el = case.find(kind)
            if el is not None:
                failed += 1
                msg = (el.get("message") or "").strip().replace("\n", " ")[:400]
                body = (el.text or "").strip().splitlines()
                where = next((l.strip() for l in body if "app.shaqati" in l), "")
                print(f"FAIL {case.get('classname')}.{case.get('name')}: {msg} @ {where}")
        if case.find("skipped") is not None:
            skipped += 1
print(f"TESTS total={total} failed={failed} skipped={skipped}")
