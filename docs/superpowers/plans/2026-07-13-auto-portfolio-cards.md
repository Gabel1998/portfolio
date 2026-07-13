# Auto-Generated Portfolio Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** New GitHub repos with the `portfolio` topic automatically appear as cards in `frontend/data/projects.json` via a scheduled workflow that generates bilingual card text with the Claude API and opens a PR for review.

**Architecture:** A Python script (`scripts/sync_projects.py`) diffs topic-tagged GitHub repos against `projects.json`, generates text for new repos with the Claude API (structured JSON output), and writes the updated file plus a PR body. A GitHub Actions workflow runs it daily/on-demand and opens a PR with `peter-evans/create-pull-request`. Hand-written cards (no `"generated": true`) are read-only to the sync.

**Tech Stack:** Python 3.12, `anthropic` SDK (model `claude-opus-4-8`, structured outputs), `requests`, `PyYAML`, `pytest`, GitHub Actions.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-13-auto-portfolio-cards-design.md` — follow it exactly.
- Cards without `"generated": true` are **never modified or removed** by the sync.
- Model is exactly `claude-opus-4-8`; structured output via `output_config={"format": {"type": "json_schema", "schema": ...}}`.
- Card JSON shape (existing file): `slug`, `title`, `description`, `descriptionDa`, `tech` (list), `github`, `image`. Generated cards add `"generated": true`. The frontend ignores unknown fields.
- Opt-in topic: `portfolio`. Deliberate opt-out topic: `no-portfolio` (only used by the CLAUDE.md convention, not by the script).
- GitHub account: `Gabel1998`. Projects file: `frontend/data/projects.json`.
- Commit messages: plain, no `Co-Authored-By` trailers (user's global rule).
- All Python lives in `scripts/`; tests run with `python3 -m pytest scripts/ -v`.

---

### Task 1: Scaffolding + diff logic

**Files:**
- Create: `scripts/requirements.txt`
- Create: `scripts/sync_projects.py`
- Test: `scripts/test_sync_projects.py`

**Interfaces:**
- Produces: `diff_cards(repos: list[dict], cards: list[dict]) -> tuple[list[dict], list[dict]]` — returns `(new_repos, removed_cards)`. `repos` are GitHub API repo objects (needs `html_url`); `cards` are `projects.json` entries. A repo is *new* when no card has `github == repo["html_url"]`. A card is *removed* only when it has `"generated": true`, has a `github` URL, and that URL is not among `repos`.

- [ ] **Step 1: Create requirements file**

`scripts/requirements.txt`:

```
anthropic
requests
PyYAML
pytest
```

- [ ] **Step 2: Write the failing tests**

`scripts/test_sync_projects.py`:

```python
import sync_projects as sp


def repo(name, url=None):
    return {"name": name, "html_url": url or f"https://github.com/Gabel1998/{name}",
            "full_name": f"Gabel1998/{name}", "description": f"{name} desc", "topics": ["portfolio"]}


def card(slug, url=None, generated=False):
    c = {"slug": slug, "title": slug, "description": "d", "descriptionDa": "d",
         "tech": [], "github": url or f"https://github.com/Gabel1998/{slug}", "image": None}
    if generated:
        c["generated"] = True
    return c


def test_new_repo_is_detected():
    new, removed = sp.diff_cards([repo("shiny")], [card("old")])
    assert [r["name"] for r in new] == ["shiny"]
    assert removed == []


def test_existing_repo_is_not_new():
    new, removed = sp.diff_cards([repo("old")], [card("old")])
    assert new == [] and removed == []


def test_generated_card_without_topic_is_removed():
    c = card("gone", generated=True)
    new, removed = sp.diff_cards([], [c])
    assert removed == [c]


def test_handwritten_card_is_never_removed():
    new, removed = sp.diff_cards([], [card("monkknows")])
    assert removed == []


def test_card_without_github_url_is_ignored():
    c = card("nourl", generated=True)
    c["github"] = None
    new, removed = sp.diff_cards([], [c])
    assert removed == []
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd ~/Development/personal/Portfolio-project && python3 -m pytest scripts/ -v`
Expected: FAIL / collection error — `sync_projects` has no attribute `diff_cards` (or module not found).

- [ ] **Step 4: Write minimal implementation**

`scripts/sync_projects.py`:

```python
#!/usr/bin/env python3
"""Sync portfolio cards in frontend/data/projects.json from GitHub repos.

Repos on the account opt in with the `portfolio` topic. New repos get a card
generated via the Claude API; hand-written cards (no `"generated": true`) are
read-only. Spec: docs/superpowers/specs/2026-07-13-auto-portfolio-cards-design.md
"""

from pathlib import Path

GITHUB_USER = "Gabel1998"
API = "https://api.github.com"
PROJECTS_JSON = Path(__file__).resolve().parent.parent / "frontend" / "data" / "projects.json"
MODEL = "claude-opus-4-8"


def diff_cards(repos, cards):
    """Return (new_repos, removed_cards).

    A repo is new when no card carries its html_url. A card is removed only
    when it is machine-managed (generated) and its repo no longer carries the
    portfolio topic (i.e. is absent from `repos`).
    """
    card_urls = {c.get("github") for c in cards}
    repo_urls = {r["html_url"] for r in repos}
    new_repos = [r for r in repos if r["html_url"] not in card_urls]
    removed = [c for c in cards
               if c.get("generated") and c.get("github") and c["github"] not in repo_urls]
    return new_repos, removed
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 5 passed. (If `pytest`/`anthropic` are missing locally: `pip3 install -r scripts/requirements.txt` first.)

- [ ] **Step 6: Commit**

```bash
git add scripts/requirements.txt scripts/sync_projects.py scripts/test_sync_projects.py
git commit -m "Add sync script scaffolding with card diff logic"
```

---

### Task 2: Card building + content gathering

**Files:**
- Modify: `scripts/sync_projects.py` (append functions)
- Test: `scripts/test_sync_projects.py` (append tests)

**Interfaces:**
- Consumes: constants from Task 1.
- Produces:
  - `build_card(repo: dict, text: dict, overrides: dict | None) -> dict` — full card; `text` is `{title, description, descriptionDa, tech}` from the generator; `overrides` from `.portfolio.yml` win for `title`, `description`, `descriptionDa`, `tech`, `image`.
  - `gather_content(repo: dict, readme: str | None, languages: list[str]) -> tuple[str, bool]` — prompt content and a `thin` flag (True when README missing or < 300 chars stripped).

- [ ] **Step 1: Write the failing tests**

Append to `scripts/test_sync_projects.py`:

```python
TEXT = {"title": "Shiny", "description": "En desc", "descriptionDa": "Da desc", "tech": ["Python"]}


def test_build_card_mechanical_fields():
    c = sp.build_card(repo("Shiny-Repo"), TEXT, None)
    assert c["slug"] == "shiny-repo"
    assert c["github"] == "https://github.com/Gabel1998/Shiny-Repo"
    assert c["image"] is None
    assert c["generated"] is True
    assert c["title"] == "Shiny" and c["tech"] == ["Python"]


def test_build_card_overrides_win():
    c = sp.build_card(repo("x"), TEXT, {"title": "Manual", "image": "/images/x.png", "slug": "hacked"})
    assert c["title"] == "Manual"
    assert c["image"] == "/images/x.png"
    assert c["slug"] == "x"  # slug is mechanical, not overridable


def test_gather_content_includes_metadata_and_readme():
    content, thin = sp.gather_content(repo("x"), "A" * 400, ["Java", "CSS"])
    assert "x desc" in content and "Java" in content and "A" * 400 in content
    assert thin is False


def test_gather_content_thin_when_readme_missing_or_short():
    _, thin_none = sp.gather_content(repo("x"), None, [])
    _, thin_short = sp.gather_content(repo("x"), "hi", [])
    assert thin_none is True and thin_short is True
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest scripts/ -v`
Expected: 4 new FAILs — `no attribute 'build_card'`.

- [ ] **Step 3: Write minimal implementation**

Append to `scripts/sync_projects.py`:

```python
THIN_THRESHOLD = 300  # chars of stripped README below which content is "thin"
OVERRIDABLE = {"title", "description", "descriptionDa", "tech", "image"}


def build_card(repo, text, overrides=None):
    card = {
        "slug": repo["name"].lower(),
        "title": text["title"],
        "description": text["description"],
        "descriptionDa": text["descriptionDa"],
        "tech": text["tech"],
        "github": repo["html_url"],
        "image": None,
        "generated": True,
    }
    for key, value in (overrides or {}).items():
        if key in OVERRIDABLE:
            card[key] = value
    return card


def gather_content(repo, readme, languages):
    parts = [
        f"Repo name: {repo['name']}",
        f"Repo description: {repo.get('description') or '(none)'}",
        f"Topics: {', '.join(repo.get('topics', []))}",
        f"Languages: {', '.join(languages) or '(unknown)'}",
    ]
    thin = not readme or len(readme.strip()) < THIN_THRESHOLD
    if readme:
        parts.append("README:\n" + readme)
    return "\n".join(parts), thin
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 9 passed.

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_projects.py scripts/test_sync_projects.py
git commit -m "Add card building with .portfolio.yml overrides and thin-README detection"
```

---

### Task 3: GitHub API functions

**Files:**
- Modify: `scripts/sync_projects.py` (append functions + imports)
- Test: `scripts/test_sync_projects.py` (append tests)

**Interfaces:**
- Consumes: `GITHUB_USER`, `API` from Task 1.
- Produces:
  - `gh_get(path: str, token: str, **params) -> dict | list` — GET `{API}{path}`, raises on HTTP errors.
  - `fetch_portfolio_repos(token) -> list[dict]` — all repos of `GITHUB_USER` with topic `portfolio` (paginates).
  - `fetch_readme(token, full_name) -> str | None` — decoded README or None on 404.
  - `fetch_portfolio_yml(token, full_name) -> dict` — parsed `.portfolio.yml` or `{}` on 404.
  - `fetch_languages(token, full_name) -> list[str]` — language names sorted by byte count desc.

- [ ] **Step 1: Write the failing tests**

Append to `scripts/test_sync_projects.py`:

```python
import base64
import requests


class FakeResponse:
    def __init__(self, payload, status=200):
        self.payload, self.status_code = payload, status

    def json(self):
        return self.payload

    def raise_for_status(self):
        if self.status_code >= 400:
            err = requests.HTTPError(f"{self.status_code}")
            err.response = self
            raise err


def fake_get(responses):
    """responses: dict of url-substring -> FakeResponse (or list to pop per call)."""
    def _get(url, **kwargs):
        for key, resp in responses.items():
            if key in url:
                if isinstance(resp, list):
                    return resp.pop(0)
                return resp
        raise AssertionError(f"unexpected url {url}")
    return _get


def test_fetch_portfolio_repos_filters_topic_and_paginates(monkeypatch):
    page1 = [repo("a"), {**repo("b"), "topics": []}]
    monkeypatch.setattr(sp.requests, "get",
                        fake_get({"/users/Gabel1998/repos": [FakeResponse(page1), FakeResponse([])]}))
    result = sp.fetch_portfolio_repos("tok")
    assert [r["name"] for r in result] == ["a"]


def test_fetch_readme_decodes_and_handles_404(monkeypatch):
    encoded = base64.b64encode("# Hej".encode()).decode()
    monkeypatch.setattr(sp.requests, "get",
                        fake_get({"/repos/Gabel1998/a/readme": FakeResponse({"content": encoded})}))
    assert sp.fetch_readme("tok", "Gabel1998/a") == "# Hej"

    monkeypatch.setattr(sp.requests, "get",
                        fake_get({"/repos/Gabel1998/a/readme": FakeResponse({}, status=404)}))
    assert sp.fetch_readme("tok", "Gabel1998/a") is None


def test_fetch_portfolio_yml_parses_and_defaults(monkeypatch):
    encoded = base64.b64encode(b"title: Manual\n").decode()
    monkeypatch.setattr(sp.requests, "get",
                        fake_get({"/contents/.portfolio.yml": FakeResponse({"content": encoded})}))
    assert sp.fetch_portfolio_yml("tok", "Gabel1998/a") == {"title": "Manual"}

    monkeypatch.setattr(sp.requests, "get",
                        fake_get({"/contents/.portfolio.yml": FakeResponse({}, status=404)}))
    assert sp.fetch_portfolio_yml("tok", "Gabel1998/a") == {}


def test_fetch_languages_sorted_by_bytes(monkeypatch):
    monkeypatch.setattr(sp.requests, "get",
                        fake_get({"/languages": FakeResponse({"CSS": 10, "Java": 900})}))
    assert sp.fetch_languages("tok", "Gabel1998/a") == ["Java", "CSS"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest scripts/ -v`
Expected: new FAILs — `no attribute 'fetch_portfolio_repos'` (and module may lack `requests` import).

- [ ] **Step 3: Write minimal implementation**

Add to the imports at the top of `scripts/sync_projects.py`:

```python
import base64

import requests
import yaml
```

Append the functions:

```python
def gh_get(path, token, **params):
    resp = requests.get(
        f"{API}{path}",
        params=params,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def _gh_get_or_none(path, token):
    try:
        return gh_get(path, token)
    except requests.HTTPError as err:
        if err.response is not None and err.response.status_code == 404:
            return None
        raise


def fetch_portfolio_repos(token):
    repos, page = [], 1
    while True:
        batch = gh_get(f"/users/{GITHUB_USER}/repos", token, per_page=100, page=page)
        if not batch:
            break
        repos.extend(batch)
        page += 1
    return [r for r in repos if "portfolio" in r.get("topics", [])]


def fetch_readme(token, full_name):
    data = _gh_get_or_none(f"/repos/{full_name}/readme", token)
    if data is None:
        return None
    return base64.b64decode(data["content"]).decode("utf-8", errors="replace")


def fetch_portfolio_yml(token, full_name):
    data = _gh_get_or_none(f"/repos/{full_name}/contents/.portfolio.yml", token)
    if data is None:
        return {}
    return yaml.safe_load(base64.b64decode(data["content"])) or {}


def fetch_languages(token, full_name):
    langs = gh_get(f"/repos/{full_name}/languages", token)
    return sorted(langs, key=langs.get, reverse=True)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 13 passed.

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_projects.py scripts/test_sync_projects.py
git commit -m "Add GitHub API fetchers for repos, README, overrides, and languages"
```

---

### Task 4: Claude API card-text generation

**Files:**
- Modify: `scripts/sync_projects.py` (append)
- Test: `scripts/test_sync_projects.py` (append)

**Interfaces:**
- Consumes: `MODEL` (Task 1), content string from `gather_content` (Task 2).
- Produces: `generate_card_text(client, content: str, examples: str, thin: bool) -> dict` — returns `{title, description, descriptionDa, tech}` parsed from a structured-output response. `client` is an `anthropic.Anthropic` instance (injected so tests mock it).

- [ ] **Step 1: Write the failing test**

Append to `scripts/test_sync_projects.py`:

```python
import json
from types import SimpleNamespace


class FakeAnthropicClient:
    def __init__(self, payload):
        self.payload, self.last_kwargs = payload, None
        self.messages = SimpleNamespace(create=self._create)

    def _create(self, **kwargs):
        self.last_kwargs = kwargs
        block = SimpleNamespace(type="text", text=json.dumps(self.payload))
        return SimpleNamespace(content=[block])


def test_generate_card_text_uses_structured_output():
    client = FakeAnthropicClient(TEXT)
    result = sp.generate_card_text(client, "content here", "[]", thin=False)
    assert result == TEXT
    kwargs = client.last_kwargs
    assert kwargs["model"] == "claude-opus-4-8"
    assert kwargs["output_config"]["format"]["type"] == "json_schema"
    assert "content here" in kwargs["messages"][0]["content"]


def test_generate_card_text_thin_note_in_prompt():
    client = FakeAnthropicClient(TEXT)
    sp.generate_card_text(client, "c", "[]", thin=True)
    assert "sparse" in client.last_kwargs["messages"][0]["content"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest scripts/ -v`
Expected: 2 new FAILs — `no attribute 'generate_card_text'`.

- [ ] **Step 3: Write minimal implementation**

Append to `scripts/sync_projects.py`:

```python
CARD_TEXT_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "description": {"type": "string"},
        "descriptionDa": {"type": "string"},
        "tech": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["title", "description", "descriptionDa", "tech"],
    "additionalProperties": False,
}

PROMPT = """You write project cards for Andreas Søgård Gabel's developer portfolio \
(https://github.com/{user}). Match the tone and length of these existing cards exactly \
— concrete, technical, no marketing fluff:

{examples}

Write the card for this project:

{content}
{thin_note}
Return: title (short, human-readable — not the repo slug), description (English, 1-3 \
sentences), descriptionDa (natural Danish translation, same content), tech (the main \
technologies as short names, e.g. "Spring Boot", "Docker" — max 6)."""

THIN_NOTE = ("\nNote: source material is sparse. Keep the description short and factual; "
             "do not invent features.\n")


def generate_card_text(client, content, examples, thin):
    import json as _json

    prompt = PROMPT.format(user=GITHUB_USER, examples=examples, content=content,
                           thin_note=THIN_NOTE if thin else "\n")
    msg = client.messages.create(
        model=MODEL,
        max_tokens=4096,
        output_config={"format": {"type": "json_schema", "schema": CARD_TEXT_SCHEMA}},
        messages=[{"role": "user", "content": prompt}],
    )
    text = next(b.text for b in msg.content if b.type == "text")
    return _json.loads(text)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 15 passed.

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_projects.py scripts/test_sync_projects.py
git commit -m "Add Claude API card-text generation with structured output"
```

---

### Task 5: main() orchestration, dry-run, and workflow output

**Files:**
- Modify: `scripts/sync_projects.py` (append `main`, `write_output`; add imports)
- Test: `scripts/test_sync_projects.py` (append integration-style test)

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces:
  - `main(argv: list[str] | None = None) -> int` — orchestrates; `--dry-run` prints instead of writing. Reads env `GITHUB_TOKEN` (required) and lets the `anthropic` SDK read `ANTHROPIC_API_KEY` itself.
  - Side effects on change: rewrites `PROJECTS_JSON`, writes `pr-body.md` in CWD, appends `changes=true|false` to `$GITHUB_OUTPUT` if set.

- [ ] **Step 1: Write the failing test**

Append to `scripts/test_sync_projects.py`:

```python
def test_main_end_to_end_with_new_and_removed(monkeypatch, tmp_path):
    projects = tmp_path / "projects.json"
    existing = [card("handwritten"), card("dropped", generated=True)]
    projects.write_text(json.dumps(existing))
    monkeypatch.setattr(sp, "PROJECTS_JSON", projects)
    monkeypatch.setenv("GITHUB_TOKEN", "tok")
    monkeypatch.setenv("GITHUB_OUTPUT", str(tmp_path / "gh_output"))
    monkeypatch.chdir(tmp_path)

    monkeypatch.setattr(sp, "fetch_portfolio_repos",
                        lambda tok: [repo("handwritten"), repo("newone")])
    monkeypatch.setattr(sp, "fetch_readme", lambda tok, fn: "R" * 400)
    monkeypatch.setattr(sp, "fetch_portfolio_yml", lambda tok, fn: {})
    monkeypatch.setattr(sp, "fetch_languages", lambda tok, fn: ["Python"])
    monkeypatch.setattr(sp, "Anthropic", lambda: FakeAnthropicClient(TEXT))

    assert sp.main([]) == 0

    result = json.loads(projects.read_text())
    slugs = [c["slug"] for c in result]
    assert "newone" in slugs                      # new card added
    assert "handwritten" in slugs                 # hand-written untouched
    assert "dropped" not in slugs                 # generated card removed
    assert (tmp_path / "pr-body.md").exists()
    assert "changes=true" in (tmp_path / "gh_output").read_text()


def test_main_no_changes_writes_false(monkeypatch, tmp_path):
    projects = tmp_path / "projects.json"
    projects.write_text(json.dumps([card("only")]))
    monkeypatch.setattr(sp, "PROJECTS_JSON", projects)
    monkeypatch.setenv("GITHUB_TOKEN", "tok")
    monkeypatch.setenv("GITHUB_OUTPUT", str(tmp_path / "gh_output"))
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(sp, "fetch_portfolio_repos", lambda tok: [repo("only")])

    assert sp.main([]) == 0
    assert "changes=false" in (tmp_path / "gh_output").read_text()
    assert not (tmp_path / "pr-body.md").exists()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest scripts/ -v`
Expected: 2 new FAILs — `no attribute 'main'` (and `sp.Anthropic` missing).

- [ ] **Step 3: Write minimal implementation**

Add to the imports in `scripts/sync_projects.py`:

```python
import argparse
import json
import os

from anthropic import Anthropic
```

Append:

```python
def write_output(changes):
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as fh:
            fh.write(f"changes={'true' if changes else 'false'}\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true",
                        help="print the resulting JSON and summary without writing")
    args = parser.parse_args(argv)

    token = os.environ["GITHUB_TOKEN"]
    cards = json.loads(PROJECTS_JSON.read_text())
    repos = fetch_portfolio_repos(token)
    new_repos, removed = diff_cards(repos, cards)

    summary = []
    if new_repos:
        client = Anthropic()
        handwritten = [c for c in cards if not c.get("generated")]
        examples = json.dumps(
            [{k: c[k] for k in ("title", "description", "descriptionDa", "tech")}
             for c in handwritten],
            ensure_ascii=False, indent=2,
        )
        for repo in new_repos:
            readme = fetch_readme(token, repo["full_name"])
            languages = fetch_languages(token, repo["full_name"])
            overrides = fetch_portfolio_yml(token, repo["full_name"])
            content, thin = gather_content(repo, readme, languages)
            text = generate_card_text(client, content, examples, thin)
            cards.append(build_card(repo, text, overrides))
            note = " — ⚠️ thin README, review the text extra carefully" if thin else ""
            summary.append(f"- ➕ New card: `{repo['name']}`{note}")

    removed_urls = {c["github"] for c in removed}
    cards = [c for c in cards if not (c.get("generated") and c.get("github") in removed_urls)]
    summary.extend(f"- ➖ Removed: `{c['slug']}` (portfolio topic dropped)" for c in removed)

    if not summary:
        print("No changes.")
        write_output(False)
        return 0

    new_json = json.dumps(cards, ensure_ascii=False, indent=2) + "\n"
    json.loads(new_json)  # final sanity check: the file we ship must parse

    if args.dry_run:
        print(new_json)
        print("\n".join(summary))
        return 0

    PROJECTS_JSON.write_text(new_json)
    Path("pr-body.md").write_text(
        "Automated portfolio card sync.\n\n" + "\n".join(summary) + "\n"
    )
    print("\n".join(summary))
    write_output(True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 17 passed.

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_projects.py scripts/test_sync_projects.py
git commit -m "Add sync orchestration with dry-run and workflow output"
```

---

### Task 6: GitHub Actions workflow + secret

**Files:**
- Create: `.github/workflows/sync-projects.yml`

**Interfaces:**
- Consumes: `scripts/sync_projects.py` CLI + its `changes` output (Task 5).
- Produces: scheduled/manual workflow that opens/updates a PR on branch `auto/sync-projects`.

- [ ] **Step 1: Write the workflow**

`.github/workflows/sync-projects.yml`:

```yaml
name: Sync Projects

on:
  schedule:
    - cron: "30 5 * * *"   # daily 05:30 UTC
  workflow_dispatch:

permissions:
  contents: write
  pull-requests: write

jobs:
  sync:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
          cache: pip
          cache-dependency-path: scripts/requirements.txt

      - name: Install dependencies
        run: pip install -r scripts/requirements.txt

      - name: Sync project cards
        id: sync
        run: python scripts/sync_projects.py
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}

      - name: Open pull request
        if: steps.sync.outputs.changes == 'true'
        uses: peter-evans/create-pull-request@v6
        with:
          branch: auto/sync-projects
          title: "Sync portfolio cards from GitHub"
          commit-message: "Sync portfolio cards from GitHub"
          body-path: pr-body.md
          add-paths: frontend/data/projects.json
```

> Known limitation: PRs created with the default `GITHUB_TOKEN` do not trigger the `pull_request` CI workflow. Acceptable here — the change is a data-file diff reviewed by a human, and deploy runs on merge to main. If CI-on-PR is wanted later, add a PAT secret and pass it as `token:` to `create-pull-request`.

- [ ] **Step 2: Validate the YAML locally**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/sync-projects.yml')); print('OK')"`
Expected: `OK`

- [ ] **Step 3: Set the API-key secret** (needs the user's Anthropic key at hand — ask if not available)

```bash
gh secret set ANTHROPIC_API_KEY -R Gabel1998/portfolio
```

Expected: `✓ Set Actions secret ANTHROPIC_API_KEY for Gabel1998/portfolio`

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/sync-projects.yml
git commit -m "Add scheduled sync-projects workflow with auto-PR"
```

---

### Task 7: Migration, global CLAUDE.md convention, and live validation

**Files:**
- Modify: `~/.claude/CLAUDE.md` (outside this repo)
- No repo files.

**Interfaces:**
- Consumes: the deployed workflow from Task 6 (must be merged/pushed to main first — `workflow_dispatch` only sees workflows on the default branch).

- [ ] **Step 1: Push main so the workflow exists on GitHub**

```bash
git push origin main
```

- [ ] **Step 2: Add `portfolio` topic to existing project repos**

List the card URLs to find the repos:

```bash
python3 -c "import json; [print(c.get('github')) for c in json.load(open('frontend/data/projects.json'))]"
```

For each URL that points to a real, still-active repo (skip decommissioned ones like MonkKnows if archived/deleted):

```bash
gh repo edit Gabel1998/<repo-name> --add-topic portfolio
```

Expected per repo: silent success (verify with `gh repo view Gabel1998/<repo-name> --json repositoryTopics`).

- [ ] **Step 3: Add the convention to global CLAUDE.md**

Append to `~/.claude/CLAUDE.md`:

```markdown
## Portfolio-kort (GitHub-topics)

- Konvention: topic `portfolio` = vises på portfoliet (Gabel1998/portfolio); topic `no-portfolio` = bevidst fravalgt.
- Når du opretter et nyt GitHub-repo til mig, eller arbejder i et repo under `~/Development`, der hverken har topic `portfolio` eller `no-portfolio`: spørg om projektet skal på portfoliet, og sæt det valgte topic med `gh repo edit --add-topic <topic>`.
- Ved ja: trig sync'en med `gh workflow run sync-projects.yml -R Gabel1998/portfolio` og nævn, at der lander en PR til godkendelse.
```

- [ ] **Step 4: Live validation — scratch repo round-trip**

```bash
# 1. Create a scratch repo with a README and the topic
gh repo create Gabel1998/sync-test-scratch --public --add-readme
gh repo edit Gabel1998/sync-test-scratch --add-topic portfolio

# 2. Trigger the sync and watch it
gh workflow run sync-projects.yml -R Gabel1998/portfolio
gh run watch -R Gabel1998/portfolio

# 3. Inspect the PR — expect one new card for sync-test-scratch (likely with the
#    thin-README warning). Do NOT merge.
gh pr view auto/sync-projects -R Gabel1998/portfolio

# 4. Clean up: close the PR, drop the scratch repo
gh pr close auto/sync-projects -R Gabel1998/portfolio --delete-branch
gh repo delete Gabel1998/sync-test-scratch --yes
```

Expected: workflow succeeds; PR contains exactly one added card in `frontend/data/projects.json` with `"generated": true`; after cleanup no PR remains.

- [ ] **Step 5: Confirm no-change run is silent**

```bash
gh workflow run sync-projects.yml -R Gabel1998/portfolio
gh run watch -R Gabel1998/portfolio
```

Expected: run succeeds, logs show `No changes.`, no PR is opened.
