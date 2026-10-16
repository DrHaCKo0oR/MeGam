"""Live probe of Saudi listing sites, run from GitHub Actions.

The development sandbox cannot reach the listing sites, so this script records
what each site serves (network calls, embedded JSON, page structure) and writes
masked samples to probe/out/ for offline analysis and test fixtures.

Phone numbers are masked before anything is written.
"""

import asyncio
import json
import os
import pathlib
import re
import sys
import time
import traceback
from urllib.parse import urljoin, urlparse

import requests

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / "out"
TARGETS = json.loads((ROOT / "targets.json").read_text(encoding="utf-8"))

MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
)
PHONE_RE = re.compile(r"(?<![\d])(\+?966|00966|0)?(5\d)(\d{7})(?![\d])")
PHONE_KEY_RE = re.compile(r"(?i)(phone|mobile|whats|tel$|telephone|contact_number|msisdn)")
MAX_BODY = int(os.environ.get("PROBE_MAX_BODY", "60000"))


def mask_text(s: str) -> str:
    return PHONE_RE.sub(lambda m: (m.group(1) or "") + m.group(2) + "XXXXXXX", s)


def mask_json(v, key=""):
    if isinstance(v, dict):
        return {k: mask_json(x, k) for k, x in v.items()}
    if isinstance(v, list):
        return [mask_json(x, key) for x in v]
    if isinstance(v, str):
        if PHONE_KEY_RE.search(key or "") and re.search(r"\d{6,}", v):
            return re.sub(r"\d", "X", v)
        return mask_text(v)
    if isinstance(v, int) and PHONE_KEY_RE.search(key or "") and len(str(v)) >= 8:
        return 0
    return v


def write(path: pathlib.Path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(mask_json(content), ensure_ascii=False, indent=1)
    else:
        content = mask_text(content)
    path.write_text(content, encoding="utf-8")


def slug(s: str, n=60) -> str:
    s = re.sub(r"[^A-Za-z0-9؀-ۿ]+", "-", s).strip("-")
    return s[:n] or "x"


def plain_get(url: str) -> dict:
    out = {"url": url}
    try:
        r = requests.get(
            url,
            headers={"User-Agent": MOBILE_UA, "Accept-Language": "ar-SA,ar;q=0.9,en;q=0.8"},
            timeout=30,
            allow_redirects=True,
        )
        out.update(
            status=r.status_code,
            final_url=r.url,
            length=len(r.text),
            server=r.headers.get("server"),
            cf=bool(r.headers.get("cf-ray")),
            title=(re.search(r"<title[^>]*>(.*?)</title>", r.text, re.S | re.I) or [None, None])[1],
            has_next_data="__NEXT_DATA__" in r.text,
            has_next_f="self.__next_f" in r.text,
        )
    except Exception as e:  # noqa: BLE001
        out["error"] = repr(e)
    return out


def impersonated_get(url: str) -> dict:
    out = {"url": url}
    try:
        from curl_cffi import requests as creq

        r = creq.get(url, impersonate="chrome", timeout=30, headers={"Accept-Language": "ar-SA,ar;q=0.9"})
        out.update(status=r.status_code, final_url=r.url, length=len(r.text),
                   has_next_data="__NEXT_DATA__" in r.text, has_next_f="self.__next_f" in r.text)
    except Exception as e:  # noqa: BLE001
        out["error"] = repr(e)
    return out


async def capture(context, url: str, scroll: bool = True, wait_ms: int = 6000) -> dict:
    page = await context.new_page()
    net = []

    async def on_response(resp):
        try:
            req = resp.request
            if req.resource_type not in ("xhr", "fetch", "document", "other"):
                return
            ct = resp.headers.get("content-type", "")
            entry = {
                "url": req.url,
                "method": req.method,
                "type": req.resource_type,
                "status": resp.status,
                "content_type": ct,
            }
            if req.method == "POST":
                entry["post_data"] = (req.post_data or "")[:20000]
            hdrs = await req.all_headers()
            entry["req_headers"] = {k: v for k, v in hdrs.items() if k.lower() not in ("cookie",)}
            if ("json" in ct or "graphql" in req.url) and req.resource_type != "document":
                try:
                    body = await resp.text()
                    entry["body"] = body[:MAX_BODY]
                    entry["body_len"] = len(body)
                except Exception as e:  # noqa: BLE001
                    entry["body_error"] = repr(e)
            net.append(entry)
        except Exception:  # noqa: BLE001
            pass

    page.on("response", on_response)
    result = {"requested_url": url}
    try:
        resp = await page.goto(url, wait_until="domcontentloaded", timeout=60000)
        result["status"] = resp.status if resp else None
        try:
            await page.wait_for_load_state("networkidle", timeout=20000)
        except Exception:  # noqa: BLE001
            pass
        await page.wait_for_timeout(wait_ms)
        if scroll:
            for _ in range(6):
                await page.evaluate("window.scrollBy(0, document.body.scrollHeight)")
                await page.wait_for_timeout(1500)
        result["final_url"] = page.url
        result["title"] = await page.title()
        html = await page.content()
        result["html_len"] = len(html)
        result["html_head"] = html[:6000]
        nd = await page.evaluate(
            "() => { const e = document.getElementById('__NEXT_DATA__'); return e ? e.textContent : null; }"
        )
        if nd:
            try:
                result["next_data"] = json.loads(nd)
            except Exception:  # noqa: BLE001
                result["next_data_raw"] = nd[:MAX_BODY]
        nf = await page.evaluate(
            "() => (self.__next_f || []).map(x => (x && x[1]) || '').join('')"
        )
        if nf:
            result["next_f_len"] = len(nf)
            result["next_f"] = nf[: MAX_BODY * 4]
        state = await page.evaluate(
            "() => { try { return window.state ? JSON.stringify(window.state).slice(0, 400000) : null } catch(e) { return 'ERR ' + e } }"
        )
        if state:
            result["window_state"] = state
        anchors = await page.evaluate(
            "() => Array.from(document.querySelectorAll('a[href]')).map(a => [a.href, (a.innerText||'').trim().slice(0,80)])"
        )
        seen = set()
        uniq = []
        for href, text in anchors:
            if href in seen:
                continue
            seen.add(href)
            uniq.append([href, text])
        result["anchors"] = uniq[:600]
        scripts = await page.evaluate(
            "() => Array.from(document.scripts).map(s => s.src).filter(Boolean)"
        )
        result["script_srcs"] = scripts[:80]
        cookies = await context.cookies()
        result["cookie_names"] = sorted({c["name"] for c in cookies})
        result["body_text"] = (await page.evaluate("() => document.body ? document.body.innerText : ''"))[:20000]
    except Exception as e:  # noqa: BLE001
        result["error"] = repr(e)
        result["trace"] = traceback.format_exc()[-3000:]
    result["network"] = net[:150]
    await page.close()
    return result


def pick_detail_links(anchors, pattern: str, limit=2):
    rx = re.compile(pattern)
    out = []
    for href, _ in anchors:
        if rx.search(href) and href not in out:
            out.append(href)
        if len(out) >= limit:
            break
    return out


async def probe_sites(only=None):
    from playwright.async_api import async_playwright

    summary = {}
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--disable-blink-features=AutomationControlled"])
        for site in TARGETS["sites"]:
            name = site["name"]
            if only and name not in only:
                continue
            sdir = OUT / name
            s = {"plain": [], "impersonated": [], "pages": []}
            for u in site.get("plain", site.get("urls", []))[:3]:
                s["plain"].append(plain_get(u))
                s["impersonated"].append(impersonated_get(u))
            context = await browser.new_context(
                user_agent=MOBILE_UA,
                locale="ar-SA",
                timezone_id="Asia/Riyadh",
                viewport={"width": 412, "height": 915},
                is_mobile=True,
                has_touch=True,
                extra_http_headers={"Accept-Language": "ar-SA,ar;q=0.9,en;q=0.8"},
            )
            await context.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})")
            idx = 0
            detail_candidates = []
            for u in site.get("urls", []):
                idx += 1
                t0 = time.time()
                r = await capture(context, u)
                r["elapsed_s"] = round(time.time() - t0, 1)
                write(sdir / f"{idx:02d}-{slug(urlparse(u).path or 'home')}.json", r)
                s["pages"].append({k: r.get(k) for k in ("requested_url", "final_url", "status", "title", "html_len", "error", "next_f_len", "elapsed_s")})
                s["pages"][-1]["network_count"] = len(r.get("network", []))
                s["pages"][-1]["has_next_data"] = "next_data" in r
                if site.get("detail_re"):
                    detail_candidates += pick_detail_links(r.get("anchors", []), site["detail_re"], 3)
            for u in site.get("details", []) + detail_candidates[: site.get("max_details", 2)]:
                idx += 1
                r = await capture(context, u, scroll=False, wait_ms=4000)
                write(sdir / f"{idx:02d}-detail-{slug(urlparse(u).path)}.json", r)
                s["pages"].append({k: r.get(k) for k in ("requested_url", "final_url", "status", "title", "html_len", "error")})
            await context.close()
            summary[name] = s
            print(json.dumps({name: s}, ensure_ascii=False)[:4000], flush=True)
        await browser.close()
    write(OUT / "summary.json", summary)


def geocode():
    res = {}
    ua = {"User-Agent": "MeGam-apartment-finder-probe/1.0 (personal research)"}
    for q in TARGETS.get("geocode", []):
        try:
            r = requests.get(
                "https://nominatim.openstreetmap.org/search",
                params={"q": q, "format": "jsonv2", "limit": 5, "addressdetails": 1, "polygon_geojson": 0},
                headers=ua,
                timeout=30,
            )
            res[q] = r.json()
        except Exception as e:  # noqa: BLE001
            res[q] = {"error": repr(e)}
        time.sleep(1.2)
    for q in TARGETS.get("photon", []):
        try:
            r = requests.get("https://photon.komoot.io/api/", params={"q": q, "limit": 8, "lat": 24.77, "lon": 46.67}, headers=ua, timeout=30)
            res["photon:" + q] = r.json()
        except Exception as e:  # noqa: BLE001
            res["photon:" + q] = {"error": repr(e)}
    for name, query in TARGETS.get("overpass", {}).items():
        try:
            r = requests.post("https://overpass-api.de/api/interpreter", data={"data": query}, headers=ua, timeout=90)
            res["overpass:" + name] = r.json()
        except Exception as e:  # noqa: BLE001
            res["overpass:" + name] = {"error": repr(e)}
    for name, url in TARGETS.get("osrm", {}).items():
        try:
            res["osrm:" + name] = requests.get(url, headers=ua, timeout=30).json()
        except Exception as e:  # noqa: BLE001
            res["osrm:" + name] = {"error": repr(e)}
    write(OUT / "geo.json", res)


def maven_versions():
    res = {}
    for coord in TARGETS.get("google_maven", []):
        g, a = coord.split(":")
        url = f"https://dl.google.com/dl/android/maven2/{g.replace('.', '/')}/{a}/maven-metadata.xml"
        try:
            txt = requests.get(url, timeout=30).text
            vs = re.findall(r"<version>([^<]+)</version>", txt)
            res[coord] = vs[-25:]
        except Exception as e:  # noqa: BLE001
            res[coord] = repr(e)
    for coord in TARGETS.get("central", []):
        g, a = coord.split(":")
        url = f"https://repo1.maven.org/maven2/{g.replace('.', '/')}/{a}/maven-metadata.xml"
        try:
            txt = requests.get(url, timeout=30).text
            vs = re.findall(r"<version>([^<]+)</version>", txt)
            res[coord] = vs[-25:]
        except Exception as e:  # noqa: BLE001
            res[coord] = repr(e)
    for coord in TARGETS.get("plugins", []):
        g, a = coord.split(":")
        url = f"https://plugins.gradle.org/m2/{g.replace('.', '/')}/{a}/maven-metadata.xml"
        try:
            txt = requests.get(url, timeout=30).text
            vs = re.findall(r"<version>([^<]+)</version>", txt)
            res[coord] = vs[-25:]
        except Exception as e:  # noqa: BLE001
            res[coord] = repr(e)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        for sub in ("platforms", "build-tools", "system-images", "ndk"):
            p = pathlib.Path(sdk) / sub
            res["sdk:" + sub] = sorted(os.listdir(p)) if p.exists() else []
    write(OUT / "versions.json", res)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    only = [x for x in os.environ.get("PROBE_ONLY", "").split(",") if x]
    steps = os.environ.get("PROBE_STEPS", "versions,geo,sites").split(",")
    if "versions" in steps:
        maven_versions()
    if "geo" in steps:
        geocode()
    if "sites" in steps:
        asyncio.run(probe_sites(only or None))
    print("probe done", flush=True)


if __name__ == "__main__":
    sys.exit(main())
