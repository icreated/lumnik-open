# loadtest/search/gen_corpus.py
"""Deterministic corpus and query generator for the Search sizing run (#750).

Vocabulary is the #704 corpus' (see vocabulary.json); rows are never copies of it. Documented distribution of a
row's searched value: 70% canonical, 15% single-edit typo, 10% abbreviation, 5% phonetic variant. Every name
carries a number word unique to the row, so near-duplicate names stay distinguishable.
"""
import argparse, csv, json, pathlib, random, sys

SEED = 750
HERE = pathlib.Path(__file__).parent
VOCAB = json.loads((HERE / "vocabulary.json").read_text(encoding="utf-8"))
FORMS = [("canonical", 0.70), ("typo", 0.15), ("abbrev", 0.10), ("phonetic", 0.05)]
RANGE_NAMES = {"base": 0, "1": 1, "2": 2, "seed": 9}   # seed: the one row that makes the tables exist (an empty CSV creates none)
QUERY_SOURCE_ROWS = 10000  # queries are drawn from the smallest tier's base rows, so they answer at every tier


def _typo(rng, word):
    if len(word) < 4:
        return word
    i = rng.randrange(1, len(word) - 1)
    return word[:i] + word[i + 1:] if rng.random() < 0.5 else word[:i] + word[i + 1] + word[i] + word[i + 2:]


def _abbrev(word):
    return (word[:3] + ".") if len(word) > 4 else word


def _phonetic(word):
    for a, b in (("ph", "f"), ("ck", "k"), ("ou", "u"), ("ai", "e"), ("ss", "s")):
        if a in word:
            return word.replace(a, b, 1)
    return word[::-1][:1] + word[1:] if len(word) > 1 else word


def _form(rng):
    x, acc = rng.random(), 0.0
    for name, p in FORMS:
        acc += p
        if x < acc:
            return name
    return FORMS[-1][0]


def _row(seed, range_id, i):
    rng = random.Random(f"{seed}/{range_id}/{i}")
    words = VOCAB["words"]
    w1, w2 = rng.choice(words), rng.choice(words)
    form = _form(rng)
    if form == "typo":
        w1 = _typo(rng, w1)
    elif form == "abbrev":
        w1 = _abbrev(w1)
    elif form == "phonetic":
        w1 = _phonetic(w1)
    number = f"n{rng.randrange(10**5):05d}{i % 10}"   # unique enough; the code below is the real key
    return {"code": f"SZ{range_id}-{i:07d}", "name": f"{w1} {w2} {number}",
            "city": rng.choice(VOCAB["cities"]), "form": form}


def generate(rows, range_id, seed=SEED):
    return [_row(seed, range_id, i) for i in range(rows)]


def queries(seed=SEED, per_mode=100):
    rng = random.Random(f"{seed}/queries")
    base = generate(QUERY_SOURCE_ROWS, 0, seed)
    canon = [r for r in base if r["form"] == "canonical"]
    out = {"exact": [], "text": [], "fuzzy": [], "prefix": []}
    for _ in range(per_mode):
        r = rng.choice(canon)
        w1, w2, num = r["name"].split(" ")
        out["exact"].append({"q": r["name"], "expect_code": r["code"]})
        out["text"].append({"q": w1, "expect_code": r["code"]})
        out["fuzzy"].append({"q": _typo(rng, w1) + " " + w2, "expect_code": r["code"]})
        out["prefix"].append({"q": w1[: rng.randrange(3, 6)], "expect_code": r["code"]})
    return out


def main(argv):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("csv")
    c.add_argument("--rows", type=int, required=True)
    c.add_argument("--range", required=True, choices=sorted(RANGE_NAMES))
    c.add_argument("--out", required=True)
    q = sub.add_parser("queries")
    q.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    if a.cmd == "csv":
        with open(a.out, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["code", "name", "city"])
            for r in generate(a.rows, RANGE_NAMES[a.range]):
                w.writerow([r["code"], r["name"], r["city"]])
    else:
        json.dump(queries(), open(a.out, "w", encoding="utf-8"), ensure_ascii=False)


if __name__ == "__main__":
    main(sys.argv[1:])
