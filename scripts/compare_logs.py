#!/usr/bin/env python3
"""Column-aware comparison of two experiment logs ignoring the host-dependent `overhead.ms` column
(wherever it sits in the header, e.g. second-to-last in EXP-F). Exit 0 = identical simulated metrics.
Usage: python scripts/compare_logs.py A.log B.log
"""
import difflib
import re
import sys

TOK = re.compile(r"[-\d.]+±[-\d.]+")


def normalise(path):
    out, drop = [], None
    for line in open(path, encoding="utf-8"):
        line = re.sub(r"\(wall \d+ ms\)", "(wall ms)", line.rstrip("\n"))   # CfsProbe: host-dependent wall time
        if re.search(r"\bstart \d\d:\d\d:\d\d|\bdone \d\d:\d\d", line):
            continue
        toks = line.split()
        if toks and "±" not in line and any("overhead" in t for t in toks):
            # header: index of the overhead column among the metric columns (name column excluded)
            # headers may have multi-token first column ("mapping/scheduler", "config", "policy", "scheduler")
            cols = toks[1:]
            drop = next((i for i, t in enumerate(cols) if "overhead" in t), None)
            out.append(line); continue
        if line.strip().startswith(("===", "####", "[", "(")) or not line.strip():
            if line.strip().startswith(("===", "####")):
                drop = None
            out.append(line); continue
        vals = TOK.findall(line)
        if drop is not None and vals and len(vals) > drop:
            # rebuild: name + metric tokens without the overhead one
            name = line[: line.find(vals[0])].rstrip()
            vals = [v for i, v in enumerate(vals) if i != drop]
            line = name + "  " + "  ".join(vals)
        out.append(line)
    return out


if __name__ == "__main__":
    a, b = sys.argv[1], sys.argv[2]
    la, lb = normalise(a), normalise(b)
    if la == lb:
        print(f"IDENTICAL (simulated metrics): {a} == {b}"); sys.exit(0)
    sys.stdout.writelines(l + "\n" for l in difflib.unified_diff(la, lb, a, b, lineterm="", n=1))
    sys.exit(1)
