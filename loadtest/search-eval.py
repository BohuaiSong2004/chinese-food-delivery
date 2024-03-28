#!/usr/bin/env python3
"""Relevance check for /api/search against a labeled query set.

Reports top-1 accuracy, MRR@10 and zero-result queries, overall and per category.
Run with Elasticsearch up, then with it stopped to measure the Postgres fallback:

    python3 loadtest/search-eval.py                  # tuning set, http://localhost:8000
    python3 loadtest/search-eval.py loadtest/search-queries-holdout.tsv
    BASE_URL=http://localhost:8000 python3 loadtest/search-eval.py --misses
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from collections import defaultdict

BASE = os.environ.get("BASE_URL", "http://localhost:8000")
HERE = os.path.dirname(os.path.abspath(__file__))


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            query, category, answers = line.split("\t")
            rows.append((query, category, set(answers.split(","))))
    return rows


def search(query):
    url = f"{BASE}/api/search?" + urllib.parse.urlencode({"q": query, "size": 10})
    with urllib.request.urlopen(url, timeout=10) as r:
        body = json.load(r)
    return [h["nameZh"] for h in body["hits"]], body["degraded"]


def main():
    files = [a for a in sys.argv[1:] if not a.startswith("--")] or [os.path.join(HERE, "search-queries.tsv")]
    rows = [row for f in files for row in load(f)]
    show_misses = "--misses" in sys.argv
    per = defaultdict(lambda: [0, 0, 0.0, 0])  # n, top1, rr sum, zero
    degraded_any = False
    misses = []
    for query, category, answers in rows:
        names, degraded = search(query)
        degraded_any |= degraded
        rank = next((i + 1 for i, n in enumerate(names) if n in answers), None)
        for key in (category, "all"):
            s = per[key]
            s[0] += 1
            s[1] += rank == 1
            s[2] += 1 / rank if rank else 0
            s[3] += not names
        if rank != 1:
            misses.append((query, category, rank, names[:3]))
    print(f"backend: {'Postgres fallback' if degraded_any else 'Elasticsearch'}  ({BASE})")
    print(f"{'category':<8} {'n':>3} {'top-1':>7} {'MRR@10':>7} {'zero':>5}")
    for key in sorted(per, key=lambda k: (k == "all", k)):
        n, top1, rr, zero = per[key]
        print(f"{key:<8} {n:>3} {top1:>3}/{n:<3} {rr / n:>7.2f} {zero:>5}")
    if show_misses:
        print("\nnot first:")
        for query, category, rank, top in misses:
            print(f"  {query:<22} [{category}] rank={rank or '-'}  top3={top}")


if __name__ == "__main__":
    main()
