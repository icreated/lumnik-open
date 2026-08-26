# Contributing

Read this first — it will save you an afternoon.

## This repository is a build artifact

lumnik is developed in a private monorepo. **This repository is a projection of it**,
extracted whole and published at each release: an allowlist copy, a scrub, a secret scan,
then five proofs — the full test suite, the `lm` suite, the boundary guards, a strict docs
build, and the adversarial acceptance battery — all of them green before anything is
pushed here.

One consequence, and it is the important one:

> **Pull requests opened here cannot be merged.** The next publication refuses to run over
> a commit it did not author, so a merged PR would break the release that follows it.

This is not a judgement on your patch. It is how the projection works.

## So how do I help?

**Open an issue.** It is read, and it is the channel that works. A bug report, a connector
that misbehaves against a real ERP, a page of documentation that lied to you — those are
worth more to us than a diff, because they carry the thing we cannot invent: contact with
a system we have never seen.

If you have a patch, put it in the issue — a diff, a branch on your fork, a description of
the fix. It lands upstream with attribution, and arrives in the next release.

**Security flaws go elsewhere**: see [SECURITY.md](SECURITY.md). Not a public issue.

## What makes a report easy to act on

- which build (`GET /api/platform/health` → the `modules` map, see
  [docs/editions.md](/docs/editions.md)) and which version or tag
- what you ran, what happened, what you expected
- the manifest, if a connector is involved — with the secrets taken out
- logs around the failure rather than the whole file

## Licence

The open edition is Apache-2.0. Anything you send us for inclusion is taken under the same
licence.
