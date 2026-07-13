# Auto-generated portfolio cards — design

**Date:** 2026-07-13
**Status:** Approved

## Problem

Every new project card in `frontend/data/projects.json` is a manual PR: write an
English description, a Danish description, pick the tech list, add the GitHub
link. This makes the portfolio lag behind the actual project activity on
GitHub. The goal is that a new repo appears on the portfolio automatically,
with generated texts, gated only by a quick PR review.

## Solution overview (GitOps pull-sync)

A scheduled GitHub Actions workflow in this repo discovers repos on the
`Gabel1998` account that opt in via a GitHub topic, generates card data for new
ones using the Claude API, and opens a PR updating `projects.json`. The
existing CI + deploy pipeline handles everything after merge. No frontend
changes — the frontend already renders whatever is in `projects.json`.

## Opt-in convention (GitHub topics)

| Topic on repo    | Meaning                                   |
| ---------------- | ----------------------------------------- |
| `portfolio`      | Show on the portfolio                     |
| `no-portfolio`   | Deliberately excluded                     |
| (neither)        | Undecided — Claude Code prompts the owner |

A repo may additionally contain an optional `.portfolio.yml` with manual
overrides (`title`, `description`, `descriptionDa`, `tech`, `image`). Fields
present there always win over generated values.

## Components

### 1. Sync workflow — `.github/workflows/sync-projects.yml`

- Triggers: daily cron + `workflow_dispatch` (manual button / `gh workflow run`).
- Runs `scripts/sync_projects.py` (Python, `anthropic` + `requests`/`gh` API).
- Secrets: `ANTHROPIC_API_KEY` (repo secret). GitHub reads use the built-in
  `GITHUB_TOKEN` (repos are public); opening the PR uses `GITHUB_TOKEN` with
  `permissions: contents: write, pull-requests: write`.

### 2. Sync script — `scripts/sync_projects.py`

1. List all repos for `Gabel1998`; filter to topic `portfolio`.
2. Diff against `frontend/data/projects.json` (match on `github` URL).
3. For each **new** repo:
   - Fetch README, repo description, topics, language stats.
   - Call Claude API — model `claude-opus-4-8`, structured output
     (`output_config.format` with a JSON schema for
     `{title, description, descriptionDa, tech[]}`). The 8 existing cards are
     included in the prompt as style examples.
   - Build the full card mechanically: `slug` from repo name, `github` URL
     from the API, `image` null (or from `.portfolio.yml`), plus
     `"generated": true` so later runs know this card is machine-managed.
     (The frontend ignores unknown fields.)
   - Missing/near-empty README → fall back to repo description + language
     stats and flag the card as "thin content" in the PR body.
4. **Existing cards are never rewritten.** Cards without `"generated": true`
   (the current 8 hand-written ones) are read-only to the sync. Cards marked
   `generated` may get mechanical updates (`github` URL, `tech` from repo
   topics) — generated text is still not re-generated once merged.
5. Removal: only cards marked `"generated": true` whose repo dropped the
   `portfolio` topic are removed in the PR. Hand-written cards are never
   auto-removed (covers retired projects like the decommissioned MonkKnows).
6. If nothing changed: exit cleanly, no PR. If anything changed: one combined
   PR with the `projects.json` diff and a summary body.

### 3. Review gate

Generated cards go live only after a human merges the PR. The existing deploy
workflow takes over from there.

### 4. Claude Code global instruction (outside this repo)

`~/.claude/CLAUDE.md` gets a section instructing Claude Code to, when creating
a new GitHub repo or working in a repo under `~/Development` that has neither
`portfolio` nor `no-portfolio` topic, ask the owner which it should be, set the
topic via `gh`, and on "yes" trigger the sync immediately:
`gh workflow run sync-projects.yml -R Gabel1998/portfolio`.

## Error handling

- Claude API failure or schema-validation failure → workflow fails visibly;
  no partial writes to `projects.json`.
- Structured output guarantees schema-valid JSON from the model; the script
  additionally validates the merged file parses before committing.
- Idempotent: re-running with no upstream changes produces no PR.

## Cost

Only new repos trigger an API call. Measured README sizes: 1–14 KB
(~600–4,000 tokens); plus style examples (~2,000) and instructions (~1,000).
≈ 5–8K input + ~400 output tokens per new card ≈ $0.04–0.05. Cron runs with no
new repos cost nothing.

## Migration of the 8 existing cards

Add the `portfolio` topic to each existing project's repo. The sync recognizes
them by `github` URL and leaves their texts untouched. Cards without a repo
(e.g. retired projects) simply keep living in `projects.json` — the sync only
removes a card when its repo exists and explicitly dropped the topic.

## Testing

- Script unit tests: diffing logic (new / existing / removed), override
  merging from `.portfolio.yml`, thin-README fallback — with the API call
  mocked.
- Dry-run mode (`--dry-run`) printing the would-be diff without writing.
- First live validation: run `workflow_dispatch` against a scratch repo with
  the `portfolio` topic; inspect the PR; delete the topic and confirm the
  removal PR.
