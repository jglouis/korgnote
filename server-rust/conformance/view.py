#!/usr/bin/env python3
"""Prints steps of a transcript the harness recorded, one line each.

    conformance/view.py kotlin.json 0 90 [WIDTH]         steps 0 to 90
    conformance/view.py kotlin.json 'watcher|events' [WIDTH]   steps whose label or request matches
"""
import json
import re
import sys

entries = json.load(open(sys.argv[1]))
if sys.argv[2].isdigit():
    lo, hi = int(sys.argv[2]), int(sys.argv[3])
    chosen = list(enumerate(entries))[lo:hi]
    rest = sys.argv[4:]
else:
    pattern = re.compile(sys.argv[2])
    chosen = [(i, e) for i, e in enumerate(entries) if pattern.search(f"{e.get('step')} | {e.get('request', '')}")]
    rest = sys.argv[3:]
width = int(rest[0]) if rest else 110

SHORT = [("application/json", "JSON"), ("text/plain; charset=UTF-8", "TEXT"), ("text/html; charset=UTF-8", "HTML"),
         ("'content-type': ", ""), ("'content-encoding': ", "enc "), ("'vary': 'Accept-Encoding'", "vary"), ("transfer-encoding", "chunked"),
         ("content-length", "len")]

for i, e in chosen:
    if "value" in e:
        print(f"{i} NOTE {e['step']}: {json.dumps(e['value'], ensure_ascii=False)[:width * 3]}")
        continue
    if "error" in e:
        print(f"{i} {e['step']} | {e['request']} -> ERROR {e['error']}")
        continue
    body = e["body"]
    b = json.dumps(body.get("text", body.get("json", body)), ensure_ascii=False)[:width]
    sent = " ".join(f"{k}={v[:40]}" for k, v in e.get("sent", {}).items())
    headers = str(e["headers"]) + " +" + ",".join(e["other_headers"])
    headers = re.sub(r"'etag': '([0-9a-f]{6})[0-9a-f]*'", r"etag \1", headers)
    for long, short in SHORT:
        headers = headers.replace(long, short)
    print(f"{i} {e['step'][:44]} | {e['request'][:74]} {sent} -> {e['status']} {headers} | {b}")
