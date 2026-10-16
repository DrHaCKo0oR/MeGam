"""Prints Android lint errors and warnings from lint-results XML (compact, for CI logs)."""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except Exception as e:  # noqa: BLE001
    print(f"no lint report: {e}")
    sys.exit(0)
counts = {}
for issue in root.iter("issue"):
    sev = issue.get("severity")
    counts[sev] = counts.get(sev, 0) + 1
    if sev in ("Error", "Fatal") or (sev == "Warning" and len(sys.argv) > 2):
        loc = issue.find("location")
        where = f"{loc.get('file', '')}:{loc.get('line', '')}" if loc is not None else ""
        print(f"LINT {sev} {issue.get('id')}: {issue.get('message')} @ {where}")
print("LINT " + " ".join(f"{k}={v}" for k, v in sorted(counts.items())))
