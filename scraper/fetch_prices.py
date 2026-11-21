#!/usr/bin/env python3
"""Daily grocery price fetcher.

Pulls this week's ads for the configured stores from the Flipp weekly-ad feed,
matches ad items against the staples list, normalises prices to a comparison
unit where the ad states a size, and writes:

  <out>/prices.json   current snapshot the app reads
  <out>/history.json  every advertised price ever seen (append-only)

Standard library only, so the scheduled job needs no install step.
"""
import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://backflipp.wishabi.com/flipp"
HERE = os.path.dirname(os.path.abspath(__file__))
UA = "grocery-price-app/1.0 (personal weekly-ad reader)"
HISTORY_DAYS = 730
# general-merchandise ads share words with groceries ('Sugar' game controllers, 'Apple' phones)
NOT_FOOD = re.compile(r"wireless|controller|xbox|nintendo|playstation|\btoys?\b|costume|lego|playset|"
                      r"ornament|candle|pajama|shirt|headphones|iphone|ipad|\btv\b|vacuum|blender|cookware", re.I)


def get_json(url, tries=4):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.load(r)
        except Exception as e:  # network hiccup: back off and retry
            last = e
            time.sleep(2 ** i)
    raise RuntimeError(f"GET failed after {tries} tries: {url}: {last}")


# ---------- size / unit parsing ----------

NUM = r"(\d+(?:\.\d+)?)"
SIZE_PATTERNS = [
    # (regex, unit family, multiplier to base unit); base units: oz (weight), floz, ct
    (rf"{NUM}\s*-?\s*(?:lbs?|pounds?)\b\.?", "oz", 16.0),
    (rf"{NUM}\s*-?\s*(?:fl\.?\s*-?\s*oz|fluid ounces?)\b\.?", "floz", 1.0),
    (rf"{NUM}\s*-?\s*(?:oz|ounces?)\b\.?", "oz", 1.0),
    (rf"{NUM}\s*-?\s*(?:gal|gallons?)\b\.?", "floz", 128.0),
    (rf"{NUM}\s*-?\s*(?:qts?|quarts?)\b\.?", "floz", 32.0),
    (rf"{NUM}\s*-?\s*(?:pts?|pints?)\b\.?", "floz", 16.0),
    (rf"{NUM}\s*-?\s*(?:liters?|litres?|l)\b\.?", "floz", 33.814),
    (rf"{NUM}\s*-?\s*ml\b\.?", "floz", 0.033814),
    (rf"{NUM}\s*-?\s*(?:ct|count|pk|pack)\b\.?", "ct", 1.0),
    (rf"{NUM}\s*-?\s*(?:doz|dozen)\b\.?", "ct", 12.0),
]
WORD_SIZES = [
    (r"\bhalf[- ]gallon\b", "floz", 64.0),
    (r"\bgallon\b", "floz", 128.0),
    (r"\bdozen\b", "ct", 12.0),
]
# what each comparison unit means in base units
UNIT_BASE = {"lb": ("oz", 16.0), "oz": ("oz", 1.0), "floz": ("floz", 1.0),
             "gal": ("floz", 128.0), "doz": ("ct", 12.0), "ct": ("ct", 1.0)}
UNIT_LABEL = {"lb": "/lb", "oz": "/oz", "floz": "/fl oz", "gal": "/gal",
              "doz": "/doz", "ct": "/ct", "each": " ea"}


def find_sizes(text):
    """All sizes stated in a piece of ad text as (position, family, amount in
    base units, matched text). A range like '10.5 to 12-oz.' yields one entry
    using the smaller size, so a unit price is never understated."""
    if not text:
        return []
    t = text.lower()
    # '12 oz. to 14 oz.' -> '12 to 14 oz.' so it reads as one range, not two sizes
    t = re.sub(rf"{NUM}\s*-?\s*(?:fl\.?\s*oz|oz|lbs?|ct|count|ml|qt|l)\b\.?\s*(to|-|–)\s*(?=\d)", r"\1 \2 ", t)
    spans, out = [], []
    for pat, fam, mult in SIZE_PATTERNS:
        for m in re.finditer(pat, t):
            if any(m.start() < e and m.end() > s for s, e in spans):
                continue  # already claimed by an earlier, more specific pattern
            val = float(m.group(1))
            label = m.group(0).strip()
            rng = re.search(rf"{NUM}\s*-?\s*(?:to|-|–)\s*$", t[:m.start()])
            if rng:
                val = min(val, float(rng.group(1)))
                label = f"{rng.group(1)}-{label}"
            if val > 0:
                spans.append((m.start(), m.end()))
                out.append((m.start(), fam, val * mult, label))
    if not out:
        for pat, fam, amt in WORD_SIZES:
            m = re.search(pat, t)
            if m:
                out.append((m.start(), fam, amt, m.group(0)))
                break
    return sorted(out)


def pick_size(fam, name, desc, match_pos):
    """Choose the size that belongs to the matched product. Ads often bundle
    several products ('Flour 5-lb. or Sugar 4-lb.'); when the text gives more
    than one candidate and none sits next to the matched word, return None
    rather than guess."""
    def same(f):
        # liquids are routinely labelled plain 'oz' in ads
        return f == fam or (fam == "floz" and f == "oz")
    in_name = [z for z in find_sizes(name) if same(z[1])]
    if in_name:
        z = min(in_name, key=lambda z: abs(z[0] - match_pos))
        return fam, z[2], z[3]
    in_desc = [z for z in find_sizes(desc) if same(z[1])]
    if len({round(z[2], 2) for z in in_desc}) == 1:
        return fam, in_desc[0][2], in_desc[0][3]
    return None


def parse_multibuy(pre):
    """'2/' or '3 for' -> 2, 3. Otherwise 1."""
    if not pre:
        return 1
    m = re.search(r"(\d+)\s*(?:/|for)\s*\$?\s*$", pre.strip().lower())
    return int(m.group(1)) if m else 1


def per_unit_text(*texts):
    """Detect an explicit per-unit price ('lb', 'per lb', '/lb', 'ea')."""
    for t in texts:
        if not t:
            continue
        s = t.lower()
        if re.search(r"(^|[\s/])(lb|lbs|pound)\b", s):
            return "lb"
        if re.search(r"(^|[\s/])(ea|each)\b", s):
            return "each"
    return None


def to_float(x):
    try:
        v = float(x)
        return v if v > 0 else None
    except (TypeError, ValueError):
        return None


def normalise(staple_unit, price, qty, sold_by, size):
    """Return (unit_price, unit) in the staple's comparison unit, or (None, None)
    when the ad does not give enough to convert honestly."""
    each = price / qty
    if staple_unit == "each":
        return (round(each, 2), "each") if sold_by != "lb" else (None, None)
    fam, per = UNIT_BASE[staple_unit]
    if sold_by == "lb":
        if fam == "oz":
            return round(each / 16.0 * per, 4), staple_unit
        return None, None
    if size and size[0] == fam and size[1] > 0:
        return round(each / size[1] * per, 4), staple_unit
    return None, None


# ---------- main ----------

def clean(s):
    return re.sub(r"\s+", " ", s or "").strip()


def iso_date(s):
    return (s or "")[:10]


def https(url):
    return re.sub(r"^http://", "https://", url) if url else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(HERE, "..", "data"))
    ap.add_argument("--no-details", action="store_true", help="skip per-item detail lookups (faster, no sizes)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    with open(os.path.join(HERE, "stores.json")) as f:
        store_cfg = json.load(f)
    with open(os.path.join(HERE, "staples.json")) as f:
        staples = json.load(f)["staples"]
    for s in staples:
        s["_m"] = re.compile(s["match"], re.I)
        s["_x"] = re.compile(s["exclude"], re.I) if s.get("exclude") else None

    postal = store_cfg["postal_code"]
    today = dt.datetime.now(dt.timezone.utc).date().isoformat()
    by_merchant = {s["merchant"].lower(): s for s in store_cfg["stores"] if s.get("merchant")}

    flyers = get_json(f"{BASE}/flyers?locale=en-us&postal_code={postal}")["flyers"]
    store_meta = {s["id"]: {"id": s["id"], "name": s["name"], "has_ads": False,
                            "ad_from": None, "ad_to": None, "ad_items": 0}
                  for s in store_cfg["stores"]}

    deals = []
    offers = {}  # staple id -> store id -> list of offers
    errors = []
    for fl in flyers:
        store = by_merchant.get(clean(fl.get("merchant")).lower())
        if not store:
            continue
        if store.get("skip_flyers") and re.search(store["skip_flyers"], fl.get("name") or "", re.I):
            continue
        if iso_date(fl.get("valid_to")) < today or iso_date(fl.get("valid_from")) > today:
            continue  # not running today
        try:
            items = get_json(f"{BASE}/flyers/{fl['id']}?locale=en-us")["items"]
        except Exception as e:
            errors.append(f"{store['name']} flyer {fl['id']}: {e}")
            continue
        meta = store_meta[store["id"]]
        meta["has_ads"] = True
        meta["ad_from"] = min(filter(None, [meta["ad_from"], iso_date(fl.get("valid_from"))]))
        meta["ad_to"] = max(filter(None, [meta["ad_to"], iso_date(fl.get("valid_to"))]))

        for it in items:
            name = clean(it.get("name"))
            price = to_float(it.get("price"))
            if not name or price is None:
                continue
            staple = None
            for s in staples:
                if NOT_FOOD.search(name):
                    break
                if s["_m"].search(name) and not (s["_x"] and s["_x"].search(name)):
                    staple = s
                    break
            if staple is None and not store["grocery_only"]:
                continue  # general-merchandise ads: keep only grocery staples

            pre = post = story = desc = None
            if staple is not None and not args.no_details:
                try:
                    d = get_json(f"{BASE}/items/{it['id']}").get("item", {})
                    pre, post = clean(d.get("pre_price_text")), clean(d.get("price_text"))
                    story, desc = clean(d.get("sale_story")), clean(d.get("description"))
                    time.sleep(0.15)
                except Exception as e:
                    errors.append(f"item {it['id']}: {e}")
            if not story:
                story = clean(it.get("discount")) if isinstance(it.get("discount"), str) else story

            qty = parse_multibuy(pre)
            sold_by = per_unit_text(post, pre)
            size = pack = None
            if staple is not None:
                pos = staple["_m"].search(name).start()
                if staple["unit"] in UNIT_BASE:
                    size = pick_size(UNIT_BASE[staple["unit"]][0], name, desc, pos)
                elif staple.get("per_pack_item"):
                    pack = pick_size("ct", name, desc, pos)
            note = " · ".join(x for x in [
                f"{qty} for ${price:.2f}" if qty > 1 else None,
                desc or None, story or None,
                post if post and sold_by is None else None,
            ] if x)

            deal = {
                "store": store["id"], "name": name, "price": round(price / qty, 2),
                "per": "lb" if sold_by == "lb" else "each",
                "note": note, "from": iso_date(it.get("valid_from") or fl.get("valid_from")),
                "to": iso_date(it.get("valid_to") or fl.get("valid_to")),
                "image": https(it.get("cutout_image_url")),
            }
            meta["ad_items"] += 1
            if staple is not None:
                up, unit = normalise(staple["unit"], price, qty, sold_by, size)
                if pack and up is not None and pack[1] > 1:
                    up, size = round(up / pack[1], 2), pack  # multipack: price per can/piece
                deal["staple"] = staple["id"]
                o = dict(deal)
                o["unit_price"], o["unit"] = up, unit
                o["size"] = size[2] if size else None
                offers.setdefault(staple["id"], {}).setdefault(store["id"], []).append(o)
            deals.append(deal)

    if not any(m["has_ads"] for m in store_meta.values()):
        print("No ads fetched for any store; leaving existing data untouched.", file=sys.stderr)
        for e in errors:
            print("  " + e, file=sys.stderr)
        sys.exit(1)

    # ---------- history ----------
    hist_path = os.path.join(args.out, "history.json")
    history = {}
    if os.path.exists(hist_path):
        with open(hist_path) as f:
            history = json.load(f)
    cutoff = (dt.date.fromisoformat(today) - dt.timedelta(days=HISTORY_DAYS)).isoformat()
    for sid, stores in offers.items():
        for store_id, lst in stores.items():
            h = history.setdefault(sid, {}).setdefault(store_id, [])
            for o in lst:
                key = (o["from"], o["name"], o["price"])
                found = next((e for e in h if (e["from"], e["name"], e["price"]) == key), None)
                if found:
                    found["to"] = max(found["to"], o["to"])
                    found["seen"] = today
                else:
                    h.append({"from": o["from"], "to": o["to"], "seen": today, "name": o["name"],
                              "price": o["price"], "per": o["per"], "unit_price": o["unit_price"],
                              "unit": o["unit"], "size": o["size"], "note": o["note"]})
    for sid in history:
        for store_id in history[sid]:
            history[sid][store_id] = sorted(
                (e for e in history[sid][store_id] if e["to"] >= cutoff), key=lambda e: e["from"])

    # ---------- snapshot ----------
    def rank(o):
        # comparable unit prices first (cheapest), then raw price
        return (0, o["unit_price"]) if o.get("unit_price") is not None else (1, o["price"])

    out_items = []
    for s in staples:
        row = {"id": s["id"], "name": s["name"], "category": s["category"],
               "unit": s["unit"], "unit_label": UNIT_LABEL[s["unit"]], "stores": {}}
        for store in store_cfg["stores"]:
            cur = sorted(offers.get(s["id"], {}).get(store["id"], []), key=rank)
            if cur:
                best = dict(cur[0])
                best["current"] = True
                best["more"] = len(cur) - 1
                best.pop("staple", None)
                best.pop("store", None)
                row["stores"][store["id"]] = best
            else:
                past = history.get(s["id"], {}).get(store["id"], [])
                if past:
                    last = dict(max(past, key=lambda e: e["to"]))
                    last["current"] = False
                    last.pop("seen", None)
                    row["stores"][store["id"]] = last
        out_items.append(row)

    snapshot = {
        "schema": 1,
        "updated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "postal_code": postal, "area": store_cfg.get("area"),
        "source": "Store weekly ads (Flipp). Prices are advertised sale and member prices, not every shelf price.",
        "stores": list(store_meta.values()),
        "items": out_items,
        "deals": sorted(deals, key=lambda d: (d["store"], d["name"].lower())),
        "errors": errors[:20],
    }
    with open(os.path.join(args.out, "prices.json"), "w") as f:
        json.dump(snapshot, f, separators=(",", ":"), ensure_ascii=False)
    with open(hist_path, "w") as f:
        json.dump(history, f, separators=(",", ":"), ensure_ascii=False)

    matched = sum(len(v) for st in offers.values() for v in st.values())
    print(f"{today}: {len(deals)} ad items, {matched} staple matches, "
          f"{sum(1 for r in out_items if any(o['current'] for o in r['stores'].values()))}/{len(out_items)} staples on ad, "
          f"{len(errors)} errors")
    for m in store_meta.values():
        print(f"  {m['name']:<16} {'ads ' + str(m['ad_items']) if m['has_ads'] else 'no ad feed'}")


if __name__ == "__main__":
    main()
