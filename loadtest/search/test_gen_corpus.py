# loadtest/search/test_gen_corpus.py
import json, pathlib, unittest
import gen_corpus

HERE = pathlib.Path(__file__).parent
CORPUS = pathlib.Path(__file__).resolve().parents[2] / "lumnik-testkit/src/main/resources/search-corpus/v1/direct-search.yaml"

class GenCorpus(unittest.TestCase):
    def test_same_seed_same_bytes(self):
        a = gen_corpus.generate(500, 1)
        b = gen_corpus.generate(500, 1)
        self.assertEqual(json.dumps(a, sort_keys=True), json.dumps(b, sort_keys=True))

    def test_ranges_are_key_disjoint_but_equivalent_in_distribution(self):
        r1 = gen_corpus.generate(4000, 1)
        r2 = gen_corpus.generate(4000, 2)
        self.assertFalse({r["code"] for r in r1} & {r["code"] for r in r2})
        share = lambda rows, f: sum(1 for r in rows if r["form"] == f) / len(rows)
        for f in ("canonical", "typo", "abbrev", "phonetic"):
            self.assertAlmostEqual(share(r1, f), share(r2, f), delta=0.03)

    def test_form_distribution_matches_the_documented_one(self):
        rows = gen_corpus.generate(20000, 0)
        share = lambda f: sum(1 for r in rows if r["form"] == f) / len(rows)
        self.assertAlmostEqual(share("canonical"), 0.70, delta=0.02)
        self.assertAlmostEqual(share("typo"), 0.15, delta=0.02)
        self.assertAlmostEqual(share("abbrev"), 0.10, delta=0.02)
        self.assertAlmostEqual(share("phonetic"), 0.05, delta=0.02)

    def test_vocabulary_comes_from_the_704_corpus(self):
        text = CORPUS.read_text(encoding="utf-8")
        vocab = json.loads((HERE / "vocabulary.json").read_text(encoding="utf-8"))
        self.assertGreater(len(vocab["words"]), 10)
        for w in vocab["words"] + vocab["cities"]:
            self.assertIn(w, text)

    def test_rows_are_not_replicas_of_each_other(self):
        rows = gen_corpus.generate(5000, 0)
        self.assertEqual(len({r["code"] for r in rows}), 5000)
        self.assertGreater(len({r["name"] for r in rows}), 4500)  # a unique number word keeps names near-distinct

    def test_zero_rows_csv_is_header_only(self):  # the orchestrator applies the fixture over an empty file
        import subprocess, sys, tempfile
        self.assertEqual(gen_corpus.generate(0, 0), [])
        with tempfile.TemporaryDirectory() as d:
            out = pathlib.Path(d) / "empty.csv"
            subprocess.run([sys.executable, str(HERE / "gen_corpus.py"), "csv", "--rows", "0", "--range", "base", "--out", str(out)], check=True)
            lines = out.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 1)
            self.assertIn("code", lines[0])

    def test_queries_are_identical_across_calls_and_exact_ones_expect_their_row(self):
        q1, q2 = gen_corpus.queries(), gen_corpus.queries()
        self.assertEqual(q1, q2)
        self.assertEqual(set(q1), {"exact", "text", "fuzzy", "prefix"})
        for mode, qs in q1.items():
            self.assertGreaterEqual(len(qs), 50, mode)
        for q in q1["exact"]:
            self.assertTrue(q["expect_code"].startswith("SZ0-"))   # drawn from the base range
        for q in q1["prefix"]:
            self.assertTrue(3 <= len(q["q"]) <= 5)

    def test_every_query_has_a_known_source_row_in_the_base_range_at_every_tier(self):
        # Queries must be answerable at the smallest tier, so they are drawn from its first 10 000 rows.
        base = {r["code"]: r for r in gen_corpus.generate(10000, 0)}
        for mode, qs in gen_corpus.queries().items():
            for q in qs:
                self.assertIn(q["expect_code"], base, mode)

if __name__ == "__main__":
    unittest.main()
