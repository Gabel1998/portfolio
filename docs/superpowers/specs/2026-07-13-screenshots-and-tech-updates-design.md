# Auto-screenshots + mechanical tech updates — design

**Date:** 2026-07-13
**Status:** Approved
**Extends:** `2026-07-13-auto-portfolio-cards-design.md` (the card-sync system, shipped in PR #10)

## Problem

1. All cards have `image: null` — the portfolio has no visuals, although the
   frontend already renders `image` as a 220px cover and most projects run
   live behind nginx on andreasgabel.dk.
2. A generated card's `tech` list is frozen at generation time; when a
   project's stack changes on GitHub, the card silently goes stale.

## Feature 1: Live-URL discovery (mechanical)

A card's live URL is resolved in priority order:

1. `liveUrl` in the repo's `.portfolio.yml` (added to the `OVERRIDABLE` set)
2. The GitHub repo's **homepage** field

Generated cards get `liveUrl` set from this resolution (the frontend already
renders a "Visit site" link when present). Resolution runs for new cards and
whenever a screenshot is needed.

**Migration:** set homepage on the live app repos
(`raid-fines` → `https://andreasgabel.dk/raidfines/`,
`webCrawler` → `https://andreasgabel.dk/webcrawler/`,
`talent-api` → `https://andreasgabel.dk/talent-api`,
`beskyttelsesrum` → `https://andreasgabel.dk/beskyttelsesrum/`,
`portfolio` → `https://andreasgabel.dk`) and add matching `liveUrl` to the
corresponding hand-written cards (one-time manual edit — hand-written cards
are user-owned; this migration is user-approved).

## Feature 2: Screenshots (fill-if-null)

For **every** card whose `image` is `null` — hand-written and generated alike:

- Card has a live URL → Playwright (headless chromium) screenshots it at
  1280×720 (`wait_until="networkidle"` + 2s settle for SPAs).
- No live URL → download the repo's GitHub OpenGraph image
  (`https://opengraph.githubassets.com/1/<owner>/<repo>`, owner/repo parsed
  from the card's own `github` URL — works for any owner, e.g.
  nasOps/MonkKnows) — self-hosted copy, no hotlinking. Cards without a
  `github` URL and without a live URL are skipped.
- File saved to `frontend/images/projects/<slug>.png`; card's `image` set to
  `/images/projects/<slug>.png`; file committed in the sync PR (workflow
  `add-paths` extended with `frontend/images/projects/`).

**Invariant amendment (narrow, deliberate):** the sync may **fill** a null
`image` (and only `image`) on a hand-written card. It never overwrites a
non-null `image` on any card. All other fields on hand-written cards remain
read-only.

**Refresh policy:** none automatic. To refresh a screenshot: set `image` back
to `null` (and optionally delete the PNG); the next run regenerates. This
keeps daily runs churn-free.

**Error handling:** a failed screenshot (timeout, DNS, non-2xx OG download)
skips that card's image with a ⚠️ line in the PR body — it never fails the
run and never blocks card-data changes. The card stays `image: null` so the
next run retries.

## Feature 3: Tech re-curation on change (generated cards only)

- Generated cards gain `techBasis`: a sorted list snapshot of
  `topics + languages` captured when `tech` was last (re)generated. The
  frontend ignores unknown fields.
- Each run recomputes the basis for every generated card's repo. If it
  differs from the stored `techBasis`, a small Claude call regenerates
  **only** the tech list (structured output: `{tech: [...]}`, same curated
  naming style, prompt includes the current tech list and the new basis),
  and both `tech` and `techBasis` are updated in the PR.
- Descriptions/titles are never re-generated. Hand-written cards (no
  `generated: true`) are never touched by this feature.
- A `tech` override in `.portfolio.yml` wins: if present, mechanical
  re-curation is skipped for that card.
- New cards store their initial `techBasis` at creation time.

## Workflow changes

- Install Playwright + chromium in the sync workflow
  (`pip install playwright && playwright install --with-deps chromium`) —
  adds ~1–2 min per run; acceptable at daily cadence.
- `add-paths` gains `frontend/images/projects/`.
- Pending-PR guard, cron, permissions: unchanged.

## Cost

- Screenshots: free (compute only).
- Tech re-curation: one small Claude call per changed repo (~500 input +
  ~50 output tokens ≈ well under 1 cent). Unchanged repos cost nothing.

## Testing

- Unit tests (mocked, as in the existing suite): URL resolution priority,
  fill-if-null vs never-overwrite, hand-written cards untouched except null
  `image`, techBasis diffing (changed / unchanged / override-wins), OG
  fallback selection, screenshot-failure ⚠️ path.
- Playwright capture isolated in one function with an injected screenshot
  callable so tests never launch a browser.
- Live validation: run `workflow_dispatch` after merge; expect one PR that
  fills images for existing cards (the migration run); inspect, merge,
  verify site renders images; then a no-change run stays silent.
