#!/usr/bin/env python3
"""Prints where two transcripts differ, each difference cut down to what is around its first character.

    conformance/diff.py kotlin.json rust.json [PATTERN] [--all]

PATTERN keeps the steps whose label or request matches. Without --all, differences the harness
lists as meant are left out.
"""
import json
import re
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from harness import allowed  # noqa: E402

arguments = [a for a in sys.argv[1:] if a != "--all"]
everything = "--all" in sys.argv
a, b = (json.load(open(path)) for path in arguments[:2])
pattern = re.compile(arguments[2]) if len(arguments) > 2 else None
count = 0
for index in range(max(len(a), len(b))):
    x = a[index] if index < len(a) else {}
    y = b[index] if index < len(b) else {}
    step = (x or y).get("step", "")
    if x == y or step.startswith("info:"):
        continue
    keys = [k for k in sorted(set(x) | set(y)) if k != "other_headers" and x.get(k) != y.get(k)]
    if not keys or (not everything and x and y and all(allowed(x, y, k) for k in keys)):
        continue
    label = f"{step} | {(x or y).get('request', '')}"
    if pattern and not pattern.search(label):
        continue
    count += 1
    print(f"#{index} {label}")
    for key in keys:
        xv, yv = (json.dumps(v.get(key), ensure_ascii=False) for v in (x, y))
        first = next((i for i in range(min(len(xv), len(yv))) if xv[i] != yv[i]), min(len(xv), len(yv)))
        start = max(0, first - 70)
        print(f"   {key}: K: {xv[start:first + 190]}\n   {' ' * len(key)}  R: {yv[start:first + 190]}")
print(f"{count} shown")
