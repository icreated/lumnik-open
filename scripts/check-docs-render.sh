#!/usr/bin/env bash
# Catches the rendering failures `mkdocs build --strict` stays green through.
#
# Strict mode validates links and missing files. It does NOT validate that a markdown
# construct became the HTML you meant. Two real bugs shipped past it in one afternoon:
# a table that fell into a <pre> block, and a list item that swallowed every sibling
# after it as literal text. Both were found by reading the built HTML by hand.
#
# A third mode joined them later: `--strict` validates that a linked FILE exists but
# never that the `#fragment` after it does, so a link can point at an id that is absent
# — or, worse, at one that exists on the wrong element. Two headings with the same text
# make mkdocs suffix the second `_1`; a link written against the first silently lands on
# a card instead of the section it names. Both shipped on this site.
#
# This is that hand-check, automated — deliberately narrow, one detector per failure
# mode actually observed. Run after `mkdocs build`.
#
# Every page is scanned. An earlier version used `grep -rl` to pre-filter which files
# the detectors even looked at, which meant a page without the pre-filter's pattern was
# never examined at all — a detector that cannot see the thing it detects. One python
# pass over every built page, no shell exit codes to get wrong.
set -uo pipefail

SITE="${1:-site}"

if [ ! -d "$SITE" ]; then
  echo "✗ no built site at '$SITE' — run 'make docs-build' first" >&2
  exit 2
fi

# This script grades an ARTIFACT, not the sources. Run alone against a site built before the
# edit under test, it reports the OLD pages green — which is exactly how a renamed heading
# shipped with a dead link into it: three local runs passed, the publish workflow failed.
# Refuse a site older than any doc, rather than grading the wrong thing quietly.
if [ -n "$(find docs mkdocs.yml -newer "$SITE" -print -quit 2>/dev/null)" ]; then
  echo "✗ '$SITE' is older than docs/ — it would grade the previous build." >&2
  echo "  Run 'make docs-build' (it builds, then calls this)." >&2
  exit 2
fi

python3 - "$SITE" <<'PY'
import re, sys, pathlib, posixpath

site = pathlib.Path(sys.argv[1])
pages = sorted(site.rglob('*.html'))
if not pages:
    print(f"✗ no HTML under '{site}' — did the build run?", file=sys.stderr)
    sys.exit(2)

table_in_pre, literal_markup, lost_emphasis = [], [], []
dangling, ambiguous = set(), set()
ids, authored = {}, {}

HEADING_ID = re.compile(r'\sid="([^"]+)"')
# Every <a> WITH its attributes, so the tag's own class can disqualify it. An earlier
# version delimited the article container instead — but this theme's cards are
# `<article class="lm-card">`, so a non-greedy match stopped at the first card and the
# detector scanned a fraction of each page while reporting green. Caught by repointing a
# real card link at a colliding id and watching it stay silent.
ANCHOR = re.compile(r'<a\s[^>]*>', re.I)
FRAG_HREF = re.compile(r'href="([^"]*#[^"]*)"')
# Emphasis that lost its nesting. `**bold**` inside an `*italic*` run shares the `*` marker, so
# CommonMark reads the inner `**` as close-then-open italic: the bold silently becomes three
# adjacent italic spans and the word carrying the emphasis renders flat. Observed 2026-08-22 on
# the one word carrying an open/closed distinction, and `--strict` was green through it.
# Immediate adjacency only — `*a* *b*` legitimately renders `<em>a</em> <em>b</em>`, with the
# space, and must not trip this.
#
# `<em>` only, deliberately. A symmetric `</strong><strong>` branch was written and then removed:
# `**outer **inner** tail**` renders `<strong>outer </strong>inner<strong> tail</strong>` — text
# between the tags, never adjacency — so that branch could not have fired. Its neighbour
# `_outer __inner__ tail_` is a DIFFERENT failure (the underscores render literally inside the
# italics) and is not covered here; it has not been observed on the site.
LOST_EMPHASIS = re.compile(r'</em><em>')

# The theme writes its own links. Its sidebar and table of contents legitimately point at
# EVERY heading id on a page, cards included, so they can never be an author's defect.
THEME_LINK = re.compile(r'class="[^"]*(?:md-nav__link|md-tabs__link|md-footer__link|md-skip|headerlink)')
# Palette toggles, tab inputs and code-copy targets are the theme's ids, not the author's.
THEME_FRAG = re.compile(r'^__')

# A markdown table delimiter row: |---|---|
DELIM = re.compile(r'\|\s*:?-{3,}:?\s*\|')
# A list marker that survived into rendered content. It shows up in two places, and
# the second is the one that actually happens: a bullet with no blank line after a
# nested table is absorbed as a ROW of that table, landing inside a <td>, not a <p>.
# The first version of this detector only looked at <p> — it could not have caught the
# very bug that motivated it. Verified by breaking a page on purpose and reading the HTML.
SWALLOWED = re.compile(r'<(?:p|td)>\s*(?:[-*+]\s+\S|\|)')
# The pipe branch used to require a SECOND pipe with no tag between them — so a leaked row
# whose first cell is inline code (`| <code>lm conflicts list</code> | …`) slipped past. A
# rendered paragraph or cell that STARTS with a pipe is a table row that lost its table,
# whatever follows. Observed 2026-08-22: an admonition inserted mid-table split it in two and
# this check stayed green.

for page in pages:
    html = page.read_text(encoding='utf-8', errors='replace')
    ids[page] = set(HEADING_ID.findall(html))
    authored[page] = [t for t in ANCHOR.findall(html) if not THEME_LINK.search(t)]

    # 1. A pipe-table that fell into a code block — the classic
    #    4-spaces-inside-a-list-item trap.
    for block in re.findall(r'<pre.*?</pre>', html, re.S):
        if not DELIM.search(block):
            continue
        # A DELIBERATE code display: some pages show a file verbatim inside a fence
        # (the four-tilde transclusion — `deploy-helm.md` renders the Helm README as
        # source on purpose). Those are syntax-highlighted, so they carry spans WITH a
        # class. mkdocs-material wraps EVERY code block in a bare `<span></span>`, so
        # testing for "<span" alone matches everything and catches nothing — that
        # mistake made the first version of this check pass on a table it had broken.
        if re.search(r'<span class=', block):
            continue
        table_in_pre.append(str(page))
        break

    # 2. A list marker or table row that survived into rendered prose — a sibling
    #    swallowed by a preceding nested block with no blank line before it.
    if SWALLOWED.search(html):
        literal_markup.append(str(page))

    # 3. Emphasis whose nesting collapsed — the bold is gone and nothing else says so.
    if LOST_EMPHASIS.search(html):
        lost_emphasis.append(str(page))

# 4. A link whose `#fragment` does not exist on the page it names — or exists on the
#    wrong element. `--strict` checks the file and stops there.
def resolve(page, path):
    """The built page an href's path part names, or None if it leaves the site."""
    if not path:
        return page
    if path.startswith('/'):
        # A ROOT-relative href (`/deploy/#…`) is relative to the SITE root, not the
        # filesystem root — and both joins silently disagreed: posixpath.join drops the
        # base on an absolute path, and `site / "/deploy/index.html"` drops `site` too.
        # The candidate landed outside the build, `cand in ids` was False, resolve()
        # returned None and the loop `continue`d: the link was checked by NEITHER
        # detector while the script printed green. A silent bucket in a guard is the
        # failure this file's header already records once. Verified by pointing a page at
        # a root-relative href with a bad fragment: green before this branch, red after.
        cand = site / posixpath.normpath(path).lstrip('/')
    else:
        base = posixpath.dirname(str(page.relative_to(site)))
        cand = site / posixpath.normpath(posixpath.join(base, path))
    if cand.is_dir() or not str(cand).endswith('.html'):
        cand = pathlib.Path(str(cand).rstrip('/')) / 'index.html'
    return cand if cand in ids else None

for page in pages:
    for tag in authored[page]:
        m = FRAG_HREF.search(tag)
        if not m:
            continue
        href = m.group(1)
        if href.startswith(('http://', 'https://', 'mailto:', 'tel:')):
            continue
        path, _, frag = href.partition('#')
        if not frag or THEME_FRAG.match(frag):
            continue
        target = resolve(page, path)
        if target is None:
            continue                       # a missing FILE is --strict's job, not ours
        if frag not in ids[target]:
            dangling.add((str(page), href))
        elif frag + '_1' in ids[target]:
            # The id resolves, but a same-text heading was deduped: this link lands on
            # the FIRST one, which on this site is a nav card, not the section it names.
            ambiguous.add((str(page), href, frag + '_1'))

fail = False

if table_in_pre:
    print("✗ a markdown table rendered as a code block in:", file=sys.stderr)
    for p in table_in_pre:
        print(f"    {p}", file=sys.stderr)
    print("  (a table indented inside a list item or admonition — fix the indent)", file=sys.stderr)
    fail = True
else:
    print("✓ no markdown table fell into a code block")

if literal_markup:
    print("✗ a list marker or table row rendered as literal text in:", file=sys.stderr)
    for p in literal_markup:
        print(f"    {p}", file=sys.stderr)
    print("  (a sibling swallowed by a preceding nested block — add a blank line)", file=sys.stderr)
    fail = True
else:
    print("✓ no list marker or table row survived as literal text")

if lost_emphasis:
    print("\u2717 emphasis lost its nesting (bold inside italics renders flat) in:", file=sys.stderr)
    for p_ in lost_emphasis:
        print(f"    {p_}", file=sys.stderr)
    print("  (`**bold**` inside an `*italic*` run \u2014 drop the outer italics, or use `_\u2026_` for the span)", file=sys.stderr)
    fail = True
else:
    print("\u2713 no emphasis lost its nesting")

if dangling:
    print("\u2717 a link points at a #fragment that does not exist:", file=sys.stderr)
    for p_, h in sorted(dangling):
        print(f"    {p_}  ->  {h}", file=sys.stderr)
    print("  (the heading was renamed or the anchor mistyped \u2014 read the id out of the built HTML)", file=sys.stderr)
    fail = True
else:
    print("\u2713 every #fragment link resolves to an id that exists")

if ambiguous:
    print("\u2717 a link lands on a duplicate heading \u2014 probably the wrong one:", file=sys.stderr)
    for p_, h, sfx in sorted(ambiguous):
        print(f"    {p_}  ->  {h}   (a same-text heading exists as #{sfx})", file=sys.stderr)
    print("  (two headings share this text; the link takes the FIRST \u2014 rename one, or target the suffixed id)", file=sys.stderr)
    fail = True
else:
    print("\u2713 no #fragment link lands on a deduped heading")

if not fail:
    print(f"Docs render check PASSED ({len(pages)} pages)")
sys.exit(1 if fail else 0)
PY
