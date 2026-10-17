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
from urllib.parse import unquote, urljoin, urlparse

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


HARAJ_QUERY = (ROOT / "haraj_posts.graphql").read_text(encoding="utf-8") if (ROOT / "haraj_posts.graphql").exists() else ""


def fetch_step():
    """Plain HTTP requests whose full responses are saved (masked) for offline parsing."""
    spec = json.loads((ROOT / "fetch.json").read_text(encoding="utf-8"))
    sess = requests.Session()
    sess.headers.update({"User-Agent": MOBILE_UA, "Accept-Language": "ar-SA,ar;q=0.9,en;q=0.8"})
    summary = []
    for item in spec.get("requests", []):
        name = item["name"]
        try:
            if item.get("graphql") == "haraj":
                body = {"query": HARAJ_QUERY, "variables": item.get("variables", {})}
                r = sess.post(item["url"], json=body, timeout=40,
                              headers={"Origin": "https://haraj.com.sa", "Referer": "https://haraj.com.sa/"})
            elif item.get("method") == "POST":
                r = sess.post(item["url"], json=item.get("json"), timeout=40, headers=item.get("headers", {}))
            else:
                r = sess.get(item["url"], timeout=40, headers=item.get("headers", {}))
            text = r.text
            ext = "json" if "json" in r.headers.get("content-type", "") else "html"
            write(OUT / "fetch" / f"{name}.{ext}", text)
            entry = {"name": name, "status": r.status_code, "len": len(text), "final_url": r.url,
                     "content_type": r.headers.get("content-type"), "server": r.headers.get("server")}
            # Follow the first detail link found in the response.
            if item.get("detail_re") and r.ok:
                from urllib.parse import unquote
                m = re.search(item["detail_re"], unquote(text))
                if m:
                    durl = urljoin(r.url, m.group(0))
                    d = sess.get(durl, timeout=40)
                    write(OUT / "fetch" / f"{name}-detail.html", d.text)
                    entry["detail"] = {"url": durl, "status": d.status_code, "len": len(d.text)}
            summary.append(entry)
        except Exception as e:  # noqa: BLE001
            summary.append({"name": name, "error": repr(e)})
        print(json.dumps(summary[-1], ensure_ascii=False), flush=True)
        time.sleep(1.5)
    write(OUT / "fetch" / "_summary.json", summary)


async def browse_step():
    """Browser captures of specific pages (for sites whose API is only visible from the app UI)."""
    from playwright.async_api import async_playwright

    spec = json.loads((ROOT / "fetch.json").read_text(encoding="utf-8"))
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        context = await browser.new_context(user_agent=MOBILE_UA, locale="ar-SA", timezone_id="Asia/Riyadh",
                                            viewport={"width": 412, "height": 915}, is_mobile=True, has_touch=True)
        for item in spec.get("browse", []):
            r = await capture(context, item["url"], scroll=True, wait_ms=item.get("wait_ms", 8000))
            write(OUT / "browse" / f"{item['name']}.json", r)
            print(item["name"], r.get("status"), r.get("title"), len(r.get("network", [])), flush=True)
        await browser.close()


def jsgrep_step():
    """Downloads a site's JavaScript bundles and saves snippets around API patterns."""
    spec = json.loads((ROOT / "fetch.json").read_text(encoding="utf-8"))
    sess = requests.Session()
    sess.headers.update({"User-Agent": MOBILE_UA, "Accept-Language": "ar-SA,ar;q=0.9"})
    for item in spec.get("jsgrep", []):
        out_lines = []
        try:
            html = sess.get(item["page"], timeout=40).text
            srcs = re.findall(r'<script[^>]+src="([^"]+\.js[^"]*)"', html)
            srcs += re.findall(r'"(/_next/static/chunks/[^"]+\.js)"', html)
            srcs += [s for s in re.findall(r'(https://[^"\'\s]+/_next/static/chunks/[^"\'\s\\]+\.js)', html)]
            srcs += item.get("extra_js", [])
            seen = set()
            for s in srcs:
                u = urljoin(item["page"], s.replace("\\u0026", "&"))
                if u in seen or len(seen) >= item.get("max_js", 120):
                    continue
                seen.add(u)
                try:
                    js = sess.get(u, timeout=40).text
                except Exception as e:  # noqa: BLE001
                    out_lines.append(f"!! {u} {e!r}")
                    continue
                for pat in item["patterns"]:
                    for m in list(re.finditer(pat, js))[: item.get("max_hits", 6)]:
                        a, b = max(0, m.start() - item.get("before", 400)), min(len(js), m.end() + item.get("after", 1200))
                        out_lines.append(f"=== {u} :: {pat} @ {m.start()}\n{js[a:b]}\n")
            out_lines.insert(0, f"scripts scanned: {len(seen)}")
        except Exception as e:  # noqa: BLE001
            out_lines.append(f"ERROR {e!r}")
        write(OUT / "jsgrep" / f"{item['name']}.txt", "\n".join(out_lines))
        print(item["name"], len(out_lines), flush=True)


def experiments_step():
    """Runs API experiments (GraphQL variants, query-parameter matrices) and records compact results."""
    spec = json.loads((ROOT / "experiments.json").read_text(encoding="utf-8"))
    sess = requests.Session()
    sess.headers.update({"User-Agent": MOBILE_UA, "Accept-Language": "ar-SA,ar;q=0.9"})
    results = []
    deal_token = None
    for ex in spec:
        name = ex["name"]
        try:
            headers = dict(ex.get("headers", {}))
            if ex.get("deal_auth"):
                if deal_token is None:
                    r0 = sess.post("https://api.dealapp.sa/production/user/skip",
                                   json={"os": MOBILE_UA, "name": "Linux x86_64", "devicePlatform": "web"},
                                   headers={"Origin": "https://dealapp.sa"}, timeout=30)
                    deal_token = r0.json().get("token") or r0.json().get("data", {}).get("token")
                    if not deal_token:
                        for hk, hv in r0.headers.items():
                            if hk.lower() in ("authorization", "token", "x-auth-token", "access-token", "x-access-token") or hv.startswith("eyJ") or "Bearer" in hv:
                                deal_token = hv
                    if not deal_token:
                        deal_token = r0.cookies.get("token") or ""
                    results.append({"name": "deal-token", "status": r0.status_code,
                                    "headers": {k: (v[:12] + "…") for k, v in r0.headers.items()},
                                    "cookies": list(r0.cookies.keys()), "has_token": bool(deal_token)})
                if deal_token:
                    headers["Authorization"] = deal_token if deal_token.startswith("Bearer") else "Bearer " + deal_token
                headers.setdefault("Origin", "https://dealapp.sa")
            if "json" in ex:
                r = sess.post(ex["url"], json=ex["json"], headers=headers, timeout=40)
            else:
                r = sess.get(ex["url"], headers=headers, timeout=40)
            body = r.text
            entry = {"name": name, "status": r.status_code, "len": len(body)}
            try:
                j = r.json()
                if ex.get("summarize") == "deal":
                    entry["total"] = j.get("total")
                    entry["sample"] = [
                        {k: (d.get(k) if not isinstance(d.get(k), dict) else {kk: d[k].get(kk) for kk in ("name_ar", "_id", "propertyType")})
                         for k in ("price", "purpose", "rentType", "city", "district", "propertyType")}
                        for d in (j.get("data") or [])[:3]
                    ]
                else:
                    entry["body"] = body[: ex.get("keep", 4000)]
            except Exception:  # noqa: BLE001
                entry["body"] = body[: ex.get("keep", 2000)]
            if ex.get("save"):
                write(OUT / "exp" / f"{name}.json", body)
            results.append(entry)
        except Exception as e:  # noqa: BLE001
            results.append({"name": name, "error": repr(e)})
        print(json.dumps(results[-1], ensure_ascii=False)[:600], flush=True)
        time.sleep(ex.get("sleep", 1.0))
    write(OUT / "exp" / "_results.json", results)


def deal_districts_step():
    """Pages through Deal's Riyadh rentals and records every district id with its Arabic name."""
    sess = requests.Session()
    sess.headers.update({"User-Agent": MOBILE_UA, "Accept-Language": "ar"})
    r0 = sess.post("https://api.dealapp.sa/production/user/skip",
                   json={"os": "Android", "name": "Android", "devicePlatform": "web"},
                   headers={"Origin": "https://dealapp.sa"}, timeout=30)
    token = next((v for k, v in r0.headers.items() if k.lower() == "authorization"), "")
    headers = {"Authorization": token if token.startswith("Bearer") else "Bearer " + token, "Origin": "https://dealapp.sa"}
    districts, log = {}, []
    # Guessed list endpoints first; then crawl ads.
    for u in ["https://api.dealapp.sa/production/district/city/6009d941950ada00061eeeab",
              "https://api.dealapp.sa/production/districts?city=6009d941950ada00061eeeab",
              "https://api.dealapp.sa/production/city/6009d941950ada00061eeeab/districts",
              "https://api.dealapp.sa/production/location/districts?city=6009d941950ada00061eeeab"]:
        try:
            r = sess.get(u, headers=headers, timeout=30)
            log.append({"url": u, "status": r.status_code, "body": r.text[:600]})
        except Exception as e:  # noqa: BLE001
            log.append({"url": u, "error": repr(e)})
    for ptype in ["63091bcd45e4ba93412d5d25", "63091cd645e4ba2cf22d5d64"]:
        for page in range(1, 260):
            u = ("https://api.dealapp.sa/production/ad?page=%d&limit=10&city=6009d941950ada00061eeeab"
                 "&purpose=RENT&propertyType=%s" % (page, ptype))
            try:
                r = sess.get(u, headers=headers, timeout=30)
            except Exception as e:  # noqa: BLE001
                log.append({"page": page, "error": repr(e)})
                break
            if r.status_code != 200:
                log.append({"page": page, "status": r.status_code, "body": r.text[:300]})
                if r.status_code == 429:
                    time.sleep(20)
                    continue
                break
            j = r.json()
            for d in j.get("data") or []:
                dd = d.get("district") or {}
                if dd.get("_id"):
                    e = districts.setdefault(dd["_id"], {"ar": dd.get("name_ar"), "en": dd.get("name_en"), "n": 0, "pts": []})
                    e["n"] += 1
                    c = ((d.get("location") or {}).get("value") or {}).get("coordinates")
                    if c and len(e["pts"]) < 5:
                        e["pts"].append([round(c[1], 4), round(c[0], 4)])
            if not j.get("hasNextPage"):
                log.append({"type": ptype, "last_page": page, "total": j.get("total")})
                break
            time.sleep(0.35)
    write(OUT / "exp" / "deal-district-map.json", {"districts": districts, "log": log})
    print("deal districts", len(districts), flush=True)


def deal_ids_step():
    """Finds Deal's Riyadh district ids by trying the ObjectId counter range around known ones.

    Deal district ids were created in one batch (…42e490 is Okaz, …42e505 Al Bayan), so each id in the
    range is asked for one ad; districts that have ads report their name. Also checks how deep a guest
    can page within one district.
    """
    sess = requests.Session()
    sess.headers.update({"User-Agent": MOBILE_UA, "Accept-Language": "ar"})
    r0 = sess.post("https://api.dealapp.sa/production/user/skip",
                   json={"os": "Android", "name": "Android", "devicePlatform": "web"},
                   headers={"Origin": "https://dealapp.sa"}, timeout=30)
    token = next((v for k, v in r0.headers.items() if k.lower() == "authorization"), "")
    headers = {"Authorization": token if token.startswith("Bearer") else "Bearer " + token, "Origin": "https://dealapp.sa"}
    found, log = {}, []
    base = "https://api.dealapp.sa/production/ad?page=1&limit=1&district=%s"
    for ts in ["5dd490ae"]:
        for counter in range(0x42E400, 0x42E600):
            did = "%sbc2e910042%06x" % (ts, counter)
            try:
                r = sess.get(base % did, headers=headers, timeout=30)
            except Exception as e:  # noqa: BLE001
                log.append({"id": did, "error": repr(e)})
                continue
            if r.status_code != 200:
                log.append({"id": did, "status": r.status_code, "body": r.text[:200]})
                if r.status_code == 429:
                    time.sleep(15)
                continue
            j = r.json()
            data = j.get("data") or []
            if data:
                d = data[0].get("district") or {}
                c = data[0].get("city") or {}
                loc = ((data[0].get("location") or {}).get("value") or {}).get("coordinates")
                found[did] = {"ar": d.get("name_ar"), "en": d.get("name_en"), "city": c.get("name_ar"),
                              "total": j.get("total"), "pt": [round(loc[1], 4), round(loc[0], 4)] if loc else None}
            time.sleep(0.2)
    # Guest paging depth inside one district (Hittin).
    depth = []
    for page in range(1, 8):
        r = sess.get("https://api.dealapp.sa/production/ad?page=%d&limit=10&district=5dd490aebc2e91004242e4de" % page,
                     headers=headers, timeout=30)
        depth.append({"page": page, "status": r.status_code, "n": len((r.json() or {}).get("data") or []) if r.ok else 0})
        time.sleep(0.5)
    write(OUT / "exp" / "deal-district-ids.json", {"found": found, "depth": depth, "log": log[:50]})
    print("deal ids", len(found), depth, flush=True)


def pf_locations_step():
    """Finds Property Finder location ids for districts near work (from their SEO pages) and
    checks whether the search accepts several locations at once."""
    import math
    from urllib.parse import quote
    districts = json.loads((ROOT.parent / "core/src/main/resources/shaqati/riyadh_districts.json").read_text(encoding="utf-8"))
    work = (24.7655, 46.6802)

    def km(a, b):
        dlat = math.radians(b[0] - a[0]); dlng = math.radians(b[1] - a[1])
        h = math.sin(dlat / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlng / 2) ** 2
        return 6371 * 2 * math.asin(math.sqrt(h))

    near = sorted([d for d in districts if km(work, (d["lat"], d["lng"])) <= 11], key=lambda d: km(work, (d["lat"], d["lng"])))
    sess = requests.Session()
    sess.headers.update({"User-Agent": MOBILE_UA, "Accept-Language": "ar-SA,ar;q=0.9"})
    found, log = {}, []

    def next_data(html):
        m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
        return json.loads(m.group(1)) if m else None

    for d in near[:40]:
        name = d["ar"].replace(" ", "-")
        url = "https://www.propertyfinder.sa/ar/" + quote("للايجار/الرياض/شقق-للايجار-الرياض-" + name + ".html")
        try:
            r = sess.get(url, timeout=40)
            nd = next_data(r.text) if r.status_code == 200 else None
            pp = (nd or {}).get("props", {}).get("pageProps", {})
            seo = pp.get("seoData") or {}
            locs = (pp.get("filtersValueFromQuery") or {}).get("filter[locations_ids]") or []
            meta = ((pp.get("searchResult") or {}).get("meta") or {})
            entry = {"status": r.status_code, "id": seo.get("locationId"), "name": seo.get("locationName"),
                     "types": [x.get("l_t") for x in locs], "total": meta.get("total_count")}
            if entry["id"] and entry["id"] != "8216":
                found[d["ar"]] = entry
            log.append({"district": d["ar"], **entry})
        except Exception as e:  # noqa: BLE001
            log.append({"district": d["ar"], "error": repr(e)})
        time.sleep(1.2)

    tests = []
    ids = [v["id"] for v in found.values()][:3]
    if len(ids) >= 2:
        for sep in ["-", ",", "%2C"]:
            url = ("https://www.propertyfinder.sa/ar/search?c=2&t=1&rp=y&pt=50000&ob=nd&l=" + sep.join(ids))
            try:
                r = sess.get(url, timeout=40)
                nd = next_data(r.text) if r.status_code == 200 else None
                pp = (nd or {}).get("props", {}).get("pageProps", {})
                locs = (pp.get("filtersValueFromQuery") or {}).get("filter[locations_ids]") or []
                meta = ((pp.get("searchResult") or {}).get("meta") or {})
                listing_locs = sorted({(l.get("property") or {}).get("location", {}).get("id") for l in (pp.get("searchResult") or {}).get("listings", [])} - {None})
                tests.append({"sep": sep, "status": r.status_code, "locations": [x.get("id") for x in locs],
                              "total": meta.get("total_count"), "listing_location_ids": listing_locs[:12]})
            except Exception as e:  # noqa: BLE001
                tests.append({"sep": sep, "error": repr(e)})
            time.sleep(1.5)
    write(OUT / "exp" / "pf-locations.json", {"found": found, "multi": tests, "log": log})
    print("pf locations", len(found), tests, flush=True)


ARCHIVE_TARGETS = {
    # name: (Wayback URL pattern, match type)
    "bayut-search-en": ("bayut.sa/en/to-rent/apartments/riyadh/", "prefix"),
    "bayut-search-ar": ("bayut.sa/للايجار/شقق/الرياض/", "prefix"),
    "bayut-detail-en": ("bayut.sa/en/property/details-", "prefix"),
    "bayut-detail-ar": ("bayut.sa/property/details-", "prefix"),
    "wasalt-search-ar": ("wasalt.sa/ar/search/rent/riyadh", "prefix"),
    "wasalt-search-en": ("wasalt.sa/en/search/rent/riyadh", "prefix"),
    "wasalt-property": ("wasalt.sa/ar/property/", "prefix"),
    "wasalt-property-en": ("wasalt.sa/en/property/", "prefix"),
}


SECRET_PATTERNS = [
    (re.compile(r"\b[ps]k\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+"), "MAPBOX_TOKEN_REDACTED"),
    (re.compile(r"eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"), "JWT_REDACTED"),
    (re.compile(r"AIza[0-9A-Za-z_\-]{35}"), "GOOGLE_KEY_REDACTED"),
    (re.compile(r'(?i)("(?:api_?key|search_?key|secret|token|access_?token|algolia_?key)"\s*:\s*")[^"]{12,}(")'), r"\1REDACTED\2"),
]


def redact_secrets(text: str) -> str:
    """Keys and tokens that sites embed in their pages are not ours to publish."""
    for rx, repl in SECRET_PATTERNS:
        text = rx.sub(repl, text)
    return text


def archive_step():
    """Saves archived copies (Internet Archive) of Bayut and Wasalt pages.

    Both sites answer cloud servers with a bot check, so their page structure (how listings are
    embedded) is learned from archived copies; the app itself reads them in a browser on the phone.
    """
    sess = requests.Session()
    sess.headers.update({"User-Agent": "Shaqati-probe/1.0 (personal apartment search research)"})
    log = {}
    for name, (pattern, match) in ARCHIVE_TARGETS.items():
        entry = {"pattern": pattern, "tried": []}
        log[name] = entry
        rows = []
        for host_prefix in ("www.", ""):
            try:
                r = sess.get("https://web.archive.org/cdx/search/cdx", params={
                    "url": host_prefix + pattern, "matchType": match, "output": "json",
                    "filter": ["statuscode:200", "mimetype:text/html"], "from": "2024",
                    "limit": "-40", "fl": "timestamp,original,length",
                }, timeout=90)
                data = r.json() if r.ok and r.text.strip() else []
                rows += data[1:] if data else []
                entry.setdefault("cdx", []).append({"host": host_prefix, "status": r.status_code, "rows": max(len(data) - 1, 0)})
            except Exception as e:  # noqa: BLE001
                entry.setdefault("cdx", []).append({"host": host_prefix, "error": repr(e)})
            time.sleep(2)
        rows.sort(key=lambda x: x[0], reverse=True)
        saved = 0
        for ts, original, length in rows[:8]:
            if saved >= 2:
                break
            url = f"https://web.archive.org/web/{ts}id_/{original}"
            try:
                r = sess.get(url, timeout=90)
                html = r.text
                info = {"ts": ts, "url": original, "status": r.status_code, "len": len(html),
                        "window_state": "window.state" in html, "next_data": "__NEXT_DATA__" in html,
                        "next_f": "self.__next_f" in html, "title": (re.search(r"<title[^>]*>([^<]*)", html) or [None, None])[1]}
                entry["tried"].append(info)
                if r.ok and len(html) > 20000 and "Security check" not in html and "challenge-platform" not in html:
                    saved += 1
                    write(OUT / "archive" / f"{name}-{saved}.html", redact_secrets(html))
            except Exception as e:  # noqa: BLE001
                entry["tried"].append({"ts": ts, "url": original, "error": repr(e)})
            time.sleep(3)
        print(name, json.dumps(entry, ensure_ascii=False)[:800], flush=True)
    write(OUT / "archive" / "_log.json", log)


def cdx_rows(sess, url, match, limit, fl="timestamp,original", collapse=False):
    params = {"url": url, "matchType": match, "output": "json", "from": "2025", "limit": limit, "fl": fl,
              "filter": ["statuscode:200", "mimetype:text/html"]}
    if collapse:
        params["collapse"] = "urlkey"
    r = requests.get("https://web.archive.org/cdx/search/cdx", params=params, timeout=180,
                     headers={"User-Agent": "Shaqati-probe/1.0 (personal apartment search research)"})
    data = r.json() if r.ok and r.text.strip() else []
    return r.status_code, (data[1:] if data else [])


def archived(sess, ts, original):
    r = sess.get(f"https://web.archive.org/web/{ts}id_/{original}", timeout=90)
    html = r.text
    title = unquote((re.search(r"<title[^>]*>([^<]*)", html) or [None, ""])[1])
    blocked = any(x in title for x in ("Just a moment", "لحظة", "Attention Required", "Security check"))
    return r, html, title, blocked


def bayut_state(html):
    i = html.find("window.state = ")
    if i < 0:
        return None
    try:
        return json.JSONDecoder().raw_decode(html, i + len("window.state = "))[0]
    except ValueError:
        return None


def archive2_step():
    """Second pass: Wasalt's URL layout and pages, Bayut's filter URLs and a rent detail page."""
    sess = requests.Session()
    sess.headers.update({"User-Agent": "Shaqati-probe/1.0 (personal apartment search research)"})
    log = {}

    # Wasalt: every archived URL, grouped into path templates.
    st, rows = cdx_rows(sess, "wasalt.sa", "domain", "30000", collapse=True)
    templates = {}
    for ts, original in rows:
        path = unquote(urlparse(original).path)
        key = re.sub(r"\d+", "N", "/".join(path.split("/")[:4]))
        t = templates.setdefault(key, {"n": 0, "examples": []})
        t["n"] += 1
        if len(t["examples"]) < 4:
            t["examples"].append(unquote(original))
    log["wasalt_cdx"] = {"status": st, "rows": len(rows)}
    write(OUT / "archive" / "_wasalt_paths.json",
          dict(sorted(templates.items(), key=lambda kv: -kv[1]["n"])[:150]))
    rentish = [(ts, o) for ts, o in rows if re.search(r"rent|إيجار|ايجار", unquote(o), re.I)]
    write(OUT / "archive" / "_wasalt_rent_urls.json", [unquote(o) for _, o in rentish[:400]])
    props = [(ts, o) for ts, o in rentish if "/property/" in o]
    lists = [(ts, o) for ts, o in rentish if "/property/" not in o]
    for name, cands in (("wasalt-rent-property", props), ("wasalt-rent-list", lists)):
        entry = log.setdefault(name, [])
        saved = 0
        for ts, original in sorted(cands, key=lambda x: x[0], reverse=True)[:8]:
            if saved >= 2:
                break
            try:
                r, html, title, blocked = archived(sess, ts, original)
                entry.append({"ts": ts, "url": unquote(original), "status": r.status_code, "len": len(html),
                              "next_data": "__NEXT_DATA__" in html, "title": title, "blocked": blocked})
                if r.ok and len(html) > 20000 and not blocked:
                    saved += 1
                    write(OUT / "archive" / f"{name}-{saved}.html", redact_secrets(html))
            except Exception as e:  # noqa: BLE001
                entry.append({"url": original, "error": repr(e)})
            time.sleep(3)

    # Bayut: which query parameters its rent search URLs use.
    st, rows = cdx_rows(sess, "bayut.sa/en/to-rent/", "prefix", "30000", collapse=True)
    params, pages = {}, []
    for ts, original in rows:
        q = urlparse(original).query
        for kv in filter(None, q.split("&")):
            k = kv.split("=")[0]
            p = params.setdefault(k, {"n": 0, "examples": []})
            p["n"] += 1
            if len(p["examples"]) < 3:
                p["examples"].append(unquote(original))
        if "/page-" in original and len(pages) < 10:
            pages.append(unquote(original))
    log["bayut_cdx"] = {"status": st, "rows": len(rows)}
    write(OUT / "archive" / "_bayut_params.json", {"params": params, "pages": pages})
    filtered = [(ts, o) for ts, o in rows if "riyadh" in o and re.search(r"price_max|beds|baths|furnish", o)]
    entry = log.setdefault("bayut-filtered", [])
    for ts, original in sorted(filtered, key=lambda x: x[0], reverse=True)[:3]:
        try:
            r, html, title, blocked = archived(sess, ts, original)
            state = bayut_state(html)
            params_seen = state["search"]["url"]["params"] if state else None
            entry.append({"ts": ts, "url": unquote(original), "status": r.status_code, "title": title,
                          "blocked": blocked, "params": params_seen})
            if state and not blocked:
                write(OUT / "archive" / "bayut-filtered-1.html", redact_secrets(html))
                break
        except Exception as e:  # noqa: BLE001
            entry.append({"url": original, "error": repr(e)})
        time.sleep(3)

    # Bayut: a Riyadh rent detail page, English and Arabic.
    for name, pattern in (("bayut-rent-detail-en", "bayut.sa/en/property/details-"),
                          ("bayut-rent-detail-ar", "bayut.sa/property/تفاصيل-")):
        entry = log.setdefault(name, [])
        st, rows = cdx_rows(sess, pattern, "prefix", "-300")
        entry.append({"cdx": st, "rows": len(rows)})
        for ts, original in sorted(rows, key=lambda x: x[0], reverse=True)[:12]:
            try:
                r, html, title, blocked = archived(sess, ts, original)
                data = ((bayut_state(html) or {}).get("property") or {}).get("data") or {}
                where = [x.get("name") for x in data.get("location") or []]
                entry.append({"ts": ts, "title": title, "purpose": data.get("purpose"), "where": where})
                if data.get("purpose") == "for-rent" and "Riyadh" in where:
                    write(OUT / "archive" / f"{name}-1.html", redact_secrets(html))
                    break
            except Exception as e:  # noqa: BLE001
                entry.append({"url": original, "error": repr(e)})
            time.sleep(3)

    # Photo hosts: are listing photos served to plain clients?
    shots = ["https://images.bayut.sa/thumbnails/9587649-800x600.jpeg",
             "https://images.bayut.sa/thumbnails/9587649-800x600.webp"]
    for f in sorted((OUT / "archive").glob("wasalt-*.html")):
        shots += re.findall(r"https://[^\"'\\\s]+\.(?:jpe?g|webp|png)", f.read_text(encoding="utf-8"))[:2]
    entry = log.setdefault("photos", [])
    for url in shots[:6]:
        try:
            r = requests.get(url, timeout=30, headers={"User-Agent": MOBILE_UA})
            entry.append({"url": url, "status": r.status_code, "type": r.headers.get("content-type"),
                          "len": len(r.content)})
        except Exception as e:  # noqa: BLE001
            entry.append({"url": url, "error": repr(e)})
    write(OUT / "archive" / "_log2.json", log)
    print(json.dumps(log, ensure_ascii=False)[:6000], flush=True)


WASALT_LISTS = [
    "wasalt.sa/شقق-للايجار-في-الرياض",
    "wasalt.sa/شقق-للايجار-في-شمال-الرياض",
    "wasalt.sa/en/apartments-for-rent-in-riyadh",
    "wasalt.sa/en/apartments-for-rent-in-north-riyadh",
    "wasalt.sa/3-غرفة-نوم-شقق-للايجار-في-شمال-الرياض",
    "wasalt.sa/عقارات-للايجار-في-الرياض",
]


def archive3_step():
    """Wasalt apartment-rental list pages that carry results (the earlier ones were empty)."""
    sess = requests.Session()
    sess.headers.update({"User-Agent": "Shaqati-probe/1.0 (personal apartment search research)"})
    log = []
    saved = 0
    for pattern in WASALT_LISTS:
        st, rows = cdx_rows(sess, pattern, "prefix", "-30")
        entry = {"pattern": pattern, "cdx": st, "rows": len(rows), "tried": []}
        log.append(entry)
        for ts, original in sorted(rows, key=lambda x: x[0], reverse=True)[:4]:
            try:
                r, html, title, blocked = archived(sess, ts, original)
                nd = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
                count = props = None
                if nd:
                    pp = json.loads(nd.group(1)).get("props", {}).get("pageProps", {})
                    sr = pp.get("searchResult") or {}
                    count, props = sr.get("count"), len(sr.get("properties") or [])
                entry["tried"].append({"ts": ts, "url": unquote(original), "status": r.status_code, "title": title,
                                       "blocked": blocked, "count": count, "properties": props})
                if props:
                    saved += 1
                    write(OUT / "archive" / f"wasalt-apartments-{saved}.html", redact_secrets(html))
                    break
            except Exception as e:  # noqa: BLE001
                entry["tried"].append({"url": original, "error": repr(e)})
            time.sleep(3)
        if saved >= 3:
            break
    write(OUT / "archive" / "_log3.json", log)
    print(json.dumps(log, ensure_ascii=False)[:6000], flush=True)


WASALT_KEYS = ["NEWEST_FIRST", "relevance", "sortBy", "sort_by", "orderBy", "max_price", "pageSize",
               "rent/search", "includeProperties", "zoneId"]


def archive4_step():
    """How Wasalt's search page builds its URLs (sort, price, paging), from its archived scripts."""
    sess = requests.Session()
    sess.headers.update({"User-Agent": "Shaqati-probe/1.0 (personal apartment search research)"})
    log = {"scripts": [], "search_pages": []}
    page = OUT / "archive" / "wasalt-apartments-2.html"
    html = page.read_text(encoding="utf-8")
    srcs = sorted(set(re.findall(r'<script[^>]+src="(/_next/static/[^"]+\.js)"', html)))
    snippets = {}
    for src in srcs[:40]:
        try:
            r = sess.get(f"https://web.archive.org/web/2026id_/https://wasalt.sa{src}", timeout=60)
            js = r.text if r.ok else ""
            hits = {}
            for key in WASALT_KEYS:
                for m in list(re.finditer(re.escape(key), js))[:4]:
                    hits.setdefault(key, []).append(js[max(0, m.start() - 220): m.end() + 220])
            log["scripts"].append({"src": src, "status": r.status_code, "len": len(js), "keys": sorted(hits)})
            if hits:
                snippets[src] = hits
        except Exception as e:  # noqa: BLE001
            log["scripts"].append({"src": src, "error": repr(e)})
        time.sleep(1)
    write(OUT / "archive" / "_wasalt_js.json", snippets)
    for pattern in ("wasalt.sa/rent/search", "wasalt.sa/en/rent/search", "wasalt.sa/ar/rent/search"):
        st, rows = cdx_rows(sess, pattern, "prefix", "-20")
        entry = {"pattern": pattern, "cdx": st, "rows": len(rows), "urls": [unquote(o) for _, o in rows[:10]]}
        log["search_pages"].append(entry)
        for ts, original in sorted(rows, key=lambda x: x[0], reverse=True)[:2]:
            try:
                r, body, title, blocked = archived(sess, ts, original)
                nd = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', body, re.S)
                pp = json.loads(nd.group(1)).get("props", {}).get("pageProps", {}) if nd else {}
                sr = pp.get("searchResult") or {}
                entry.setdefault("tried", []).append({"url": unquote(original), "status": r.status_code, "title": title,
                                                      "count": sr.get("count"), "properties": len(sr.get("properties") or []),
                                                      "filterQueries": pp.get("filterQueries")})
                if sr.get("properties"):
                    write(OUT / "archive" / "wasalt-search-route.html", redact_secrets(body))
                    break
            except Exception as e:  # noqa: BLE001
                entry.setdefault("tried", []).append({"url": original, "error": repr(e)})
            time.sleep(3)
    write(OUT / "archive" / "_log4.json", log)
    print(json.dumps(log, ensure_ascii=False)[:6000], flush=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    only = [x for x in os.environ.get("PROBE_ONLY", "").split(",") if x]
    steps = os.environ.get("PROBE_STEPS", "versions,geo,sites").split(",")
    if "experiments" in steps:
        experiments_step()
    if "deal_districts" in steps:
        deal_districts_step()
    if "deal_ids" in steps:
        deal_ids_step()
    if "pf_locations" in steps:
        pf_locations_step()
    if "archive" in steps:
        archive_step()
    if "archive2" in steps:
        archive2_step()
    if "archive3" in steps:
        archive3_step()
    if "archive4" in steps:
        archive4_step()
    if "versions" in steps:
        maven_versions()
    if "geo" in steps:
        geocode()
    if "jsgrep" in steps:
        jsgrep_step()
    if "fetch" in steps:
        fetch_step()
    if "browse" in steps:
        asyncio.run(browse_step())
    if "sites" in steps:
        asyncio.run(probe_sites(only or None))
    print("probe done", flush=True)


if __name__ == "__main__":
    sys.exit(main())
