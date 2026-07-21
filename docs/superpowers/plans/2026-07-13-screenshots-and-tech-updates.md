# Auto-Screenshots + Tech Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every card with `image: null` gets a screenshot (Playwright for live sites, GitHub OG image otherwise), and generated cards' tech lists re-curate automatically when the repo's stack changes.

**Architecture:** A new focused module `scripts/screenshots.py` owns image filling (URL resolution is data already on the card; capture/download are injectable for tests). `scripts/sync_projects.py` gains `liveUrl`/`techBasis` on generated cards and a `refresh_tech_lists` pass using a small Claude call. `main()` wires both in before the no-changes check. The workflow installs chromium and commits the images directory.

**Tech Stack:** Python 3.12, `playwright` (sync API, chromium), existing `anthropic`/`requests`/`PyYAML`/`pytest` stack.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-13-screenshots-and-tech-updates-design.md` — follow exactly.
- Hand-written cards (no `"generated": true`): the sync may **fill** a null `image` — nothing else. It never overwrites a non-null `image` on any card.
- Live-URL priority: `.portfolio.yml` `liveUrl` > repo `homepage` field. `liveUrl` joins `OVERRIDABLE`.
- Screenshot: 1280×720, `wait_until="networkidle"` + 2000 ms settle. Saved to `frontend/images/projects/<slug>.png`; card `image` = `/images/projects/<slug>.png`.
- OG fallback: `https://opengraph.githubassets.com/1/<owner>/<repo>`, owner/repo parsed from the card's own `github` URL. No liveUrl AND no github URL → skip silently.
- Screenshot/OG failures: ⚠️ line in summary, card stays `image: null`, run continues (never fails the run).
- Tech re-curation: generated cards only; `techBasis` = sorted set-union of topics + languages; `.portfolio.yml` `tech` override → skip entirely; only `tech` + `techBasis` change, never texts. Model exactly `claude-opus-4-8`, structured output via `output_config={"format": {"type": "json_schema", ...}}`.
- `--dry-run`: skips image filling (file side effects) with a printed note; tech refresh still computed.
- Tests: `python3 -m pytest scripts/ -v` (19 pass today). No network/browser in tests. Plain commit messages, no Co-Authored-By trailers.
- Current `sync_projects.py` state is on main (post-PR #10/#12): `OVERRIDABLE` at line 41, `build_card` at 44, `main()` at 177.

---

### Task 1: screenshots.py module

**Files:**
- Create: `scripts/screenshots.py`
- Modify: `scripts/requirements.txt` (add `playwright`)
- Test: `scripts/test_screenshots.py` (new)

**Interfaces:**
- Produces:
  - `og_image_url(github_url: str | None) -> str | None`
  - `capture_screenshot(url: str, dest: Path) -> None` (raises on failure; imports playwright lazily)
  - `download_og_image(github_url: str, dest: Path) -> None` (raises on failure)
  - `fill_images(cards: list[dict], capture=capture_screenshot, download=download_og_image, images_dir=IMAGES_DIR) -> list[str]` — mutates cards (sets `image`), writes PNGs, returns summary lines.
  - `IMAGES_DIR = <repo>/frontend/images/projects` (Path)

- [ ] **Step 1: Add playwright to requirements**

Append `playwright` on its own line to `scripts/requirements.txt`, then `pip3 install playwright` locally (no `playwright install` needed — tests never launch a browser).

- [ ] **Step 2: Write the failing tests**

`scripts/test_screenshots.py`:

```python
from pathlib import Path

import screenshots as sc


def card(slug, image=None, live_url=None, github="x"):
    c = {"slug": slug, "image": image}
    if live_url:
        c["liveUrl"] = live_url
    if github:
        c["github"] = f"https://github.com/Gabel1998/{slug}" if github == "x" else github
    return c


def test_og_image_url_parses_owner_repo():
    assert sc.og_image_url("https://github.com/nasOps/MonkKnows") == \
        "https://opengraph.githubassets.com/1/nasOps/MonkKnows"


def test_og_image_url_handles_bad_input():
    assert sc.og_image_url(None) is None
    assert sc.og_image_url("https://github.com/justowner") is None


def fakes(tmp_path, fail=False):
    calls = {"capture": [], "download": []}

    def capture(url, dest):
        if fail:
            raise TimeoutError("site down")
        calls["capture"].append(url)
        Path(dest).write_bytes(b"png")

    def download(github_url, dest):
        if fail:
            raise ValueError("bad status")
        calls["download"].append(github_url)
        Path(dest).write_bytes(b"png")

    return calls, capture, download


def test_fill_images_uses_capture_for_live_url(tmp_path):
    calls, capture, download = fakes(tmp_path)
    c = card("app", live_url="https://example.dk/app/")
    summary = sc.fill_images([c], capture, download, tmp_path)
    assert calls["capture"] == ["https://example.dk/app/"]
    assert calls["download"] == []
    assert c["image"] == "/images/projects/app.png"
    assert (tmp_path / "app.png").exists()
    assert any("app" in line for line in summary)


def test_fill_images_falls_back_to_og(tmp_path):
    calls, capture, download = fakes(tmp_path)
    c = card("lib")  # github URL, no liveUrl
    sc.fill_images([c], capture, download, tmp_path)
    assert calls["capture"] == []
    assert calls["download"] == ["https://github.com/Gabel1998/lib"]
    assert c["image"] == "/images/projects/lib.png"


def test_fill_images_never_overwrites_existing_image(tmp_path):
    calls, capture, download = fakes(tmp_path)
    c = card("done", image="/images/projects/done.png", live_url="https://x.dk")
    summary = sc.fill_images([c], capture, download, tmp_path)
    assert calls["capture"] == [] and calls["download"] == []
    assert summary == []


def test_fill_images_skips_card_without_any_source(tmp_path):
    calls, capture, download = fakes(tmp_path)
    c = card("naked", github=None)
    summary = sc.fill_images([c], capture, download, tmp_path)
    assert c["image"] is None and summary == []


def test_fill_images_failure_warns_and_leaves_null(tmp_path):
    _, capture, download = fakes(tmp_path, fail=True)
    c = card("flaky", live_url="https://down.dk")
    summary = sc.fill_images([c], capture, download, tmp_path)
    assert c["image"] is None
    assert summary and summary[0].startswith("- ⚠️")


def test_download_og_image_writes_bytes(tmp_path, monkeypatch):
    class Resp:
        content = b"imagebytes"
        def raise_for_status(self):
            pass
    monkeypatch.setattr(sc.requests, "get", lambda url, timeout: Resp())
    dest = tmp_path / "x.png"
    sc.download_og_image("https://github.com/Gabel1998/x", dest)
    assert dest.read_bytes() == b"imagebytes"
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `python3 -m pytest scripts/test_screenshots.py -v`
Expected: collection error — `No module named 'screenshots'`.

- [ ] **Step 4: Write the implementation**

`scripts/screenshots.py`:

```python
"""Fill portfolio card images: Playwright screenshots for live sites, GitHub
OpenGraph images as fallback. Fill-if-null only — a non-null `image` is never
overwritten. Spec: docs/superpowers/specs/2026-07-13-screenshots-and-tech-updates-design.md
"""

from pathlib import Path
from urllib.parse import urlparse

import requests

IMAGES_DIR = Path(__file__).resolve().parent.parent / "frontend" / "images" / "projects"
VIEWPORT = {"width": 1280, "height": 720}


def og_image_url(github_url):
    """GitHub repo URL -> OpenGraph image URL, or None if unparseable."""
    if not github_url:
        return None
    parts = urlparse(github_url).path.strip("/").split("/")
    if len(parts) < 2 or not all(parts[:2]):
        return None
    return f"https://opengraph.githubassets.com/1/{parts[0]}/{parts[1]}"


def capture_screenshot(url, dest):
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport=VIEWPORT)
        page.goto(url, wait_until="networkidle", timeout=30_000)
        page.wait_for_timeout(2_000)  # settle SPAs after networkidle
        page.screenshot(path=str(dest))
        browser.close()


def download_og_image(github_url, dest):
    url = og_image_url(github_url)
    if not url:
        raise ValueError(f"cannot derive OG image URL from {github_url!r}")
    resp = requests.get(url, timeout=30)
    resp.raise_for_status()
    Path(dest).write_bytes(resp.content)


def fill_images(cards, capture=capture_screenshot, download=download_og_image,
                images_dir=IMAGES_DIR):
    """Fill `image` on every card where it is null. Returns summary lines.

    Images are nice-to-have: any failure warns and moves on so card data is
    never blocked by a flaky site (hence the broad except).
    """
    summary = []
    images_dir = Path(images_dir)
    images_dir.mkdir(parents=True, exist_ok=True)
    for card in cards:
        if card.get("image") is not None:
            continue
        dest = images_dir / f"{card['slug']}.png"
        try:
            if card.get("liveUrl"):
                capture(card["liveUrl"], dest)
                kind = "screenshot"
            elif card.get("github"):
                download(card["github"], dest)
                kind = "OG image"
            else:
                continue
        except Exception as err:  # noqa: BLE001 — deliberate, see docstring
            summary.append(f"- ⚠️ Image failed for `{card['slug']}`: {err}")
            continue
        card["image"] = f"/images/projects/{card['slug']}.png"
        summary.append(f"- 🖼 Added {kind} for `{card['slug']}`")
    return summary
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 27 passed (19 existing + 8 new).

- [ ] **Step 6: Commit**

```bash
git add scripts/screenshots.py scripts/test_screenshots.py scripts/requirements.txt
git commit -m "Add image filling module with Playwright and OG fallback"
```

---

### Task 2: liveUrl + techBasis on generated cards

**Files:**
- Modify: `scripts/sync_projects.py` (`OVERRIDABLE` line 41, `build_card` line 44; new `tech_basis` after `gather_content`)
- Test: `scripts/test_sync_projects.py` (append)

**Interfaces:**
- Consumes: existing `build_card(repo, text, overrides=None)`.
- Produces:
  - `build_card(repo, text, overrides=None, languages=None) -> dict` — now also sets `liveUrl` (repo `homepage` or None; `.portfolio.yml` override wins) and `techBasis` (from `tech_basis`).
  - `tech_basis(repo: dict, languages: list[str]) -> list[str]` — sorted set-union of `repo["topics"]` and `languages`.
  - `OVERRIDABLE` now includes `"liveUrl"`.

- [ ] **Step 1: Write the failing tests**

Append to `scripts/test_sync_projects.py`:

```python
def test_tech_basis_is_sorted_union():
    r = {**repo("x"), "topics": ["portfolio", "docker"]}
    assert sp.tech_basis(r, ["Java", "docker"]) == ["Java", "docker", "portfolio"]


def test_build_card_sets_live_url_from_homepage():
    r = {**repo("x"), "homepage": "https://andreasgabel.dk/x/"}
    c = sp.build_card(r, TEXT, None)
    assert c["liveUrl"] == "https://andreasgabel.dk/x/"


def test_build_card_live_url_none_and_override_wins():
    assert sp.build_card(repo("x"), TEXT, None)["liveUrl"] is None
    c = sp.build_card(repo("x"), TEXT, {"liveUrl": "https://manual.dk"})
    assert c["liveUrl"] == "https://manual.dk"


def test_build_card_sets_tech_basis():
    r = {**repo("x"), "topics": ["portfolio"]}
    c = sp.build_card(r, TEXT, None, languages=["Python"])
    assert c["techBasis"] == ["Python", "portfolio"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest scripts/test_sync_projects.py -v`
Expected: 4 new FAILs — `no attribute 'tech_basis'` / missing keys.

- [ ] **Step 3: Implement**

In `scripts/sync_projects.py`, change line 41 to:

```python
OVERRIDABLE = {"title", "description", "descriptionDa", "tech", "image", "liveUrl"}
```

Add after `gather_content`:

```python
def tech_basis(repo, languages):
    """Sorted snapshot of topics + languages; used to detect stack changes."""
    return sorted(set(repo.get("topics", [])) | set(languages))
```

Replace `build_card` with:

```python
def build_card(repo, text, overrides=None, languages=None):
    card = {
        "slug": repo["name"].lower(),
        "title": text["title"],
        "description": text["description"],
        "descriptionDa": text["descriptionDa"],
        "tech": text["tech"],
        "github": repo["html_url"],
        "liveUrl": repo.get("homepage") or None,
        "image": None,
        "generated": True,
        "techBasis": tech_basis(repo, languages or []),
    }
    for key, value in (overrides or {}).items():
        if key in OVERRIDABLE:
            card[key] = value
    return card
```

In `main()`, pass languages through (line 203): `cards.append(build_card(repo, text, overrides, languages))`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 31 passed.

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_projects.py scripts/test_sync_projects.py
git commit -m "Add liveUrl and techBasis to generated cards"
```

---

### Task 3: Tech re-curation

**Files:**
- Modify: `scripts/sync_projects.py` (append after `generate_card_text`)
- Test: `scripts/test_sync_projects.py` (append)

**Interfaces:**
- Consumes: `MODEL`, `fetch_languages(token, full_name)`, `fetch_portfolio_yml(token, full_name)`, `tech_basis(repo, languages)`, test helper `FakeAnthropicClient` (its payload becomes the JSON of the response text block).
- Produces:
  - `recurate_tech(client, current_tech: list[str], basis: list[str]) -> list[str]`
  - `refresh_tech_lists(cards, repos_by_url: dict[str, dict], token, client_factory) -> list[str]` — mutates matching cards' `tech`+`techBasis`; returns summary lines. `client_factory` is called at most once, only if something changed (pass the `Anthropic` class itself).

- [ ] **Step 1: Write the failing tests**

Append to `scripts/test_sync_projects.py`:

```python
def gen_card(slug, tech=None, basis=None):
    c = card(slug, generated=True)
    c["tech"] = tech or ["Java"]
    if basis is not None:
        c["techBasis"] = basis
    return c


def test_recurate_tech_calls_model_with_schema():
    client = FakeAnthropicClient({"tech": ["Spring Boot", "Docker"]})
    result = sp.recurate_tech(client, ["Java"], ["docker", "java", "spring-boot"])
    assert result == ["Spring Boot", "Docker"]
    kwargs = client.last_kwargs
    assert kwargs["model"] == "claude-opus-4-8"
    assert kwargs["output_config"]["format"]["schema"] == sp.TECH_SCHEMA


def _wire_refresh(monkeypatch, languages, overrides=None):
    monkeypatch.setattr(sp, "fetch_languages", lambda tok, fn: languages)
    monkeypatch.setattr(sp, "fetch_portfolio_yml", lambda tok, fn: overrides or {})


def test_refresh_skips_unchanged_basis(monkeypatch):
    _wire_refresh(monkeypatch, ["Java"])
    r = {**repo("a"), "topics": ["portfolio"]}
    c = gen_card("a", basis=sp.tech_basis(r, ["Java"]))
    summary = sp.refresh_tech_lists([c], {r["html_url"]: r}, "tok",
                                    lambda: (_ for _ in ()).throw(AssertionError("no client")))
    assert summary == [] and c["tech"] == ["Java"]


def test_refresh_recurates_on_changed_basis(monkeypatch):
    _wire_refresh(monkeypatch, ["Java", "Dockerfile"])
    r = {**repo("a"), "topics": ["portfolio"]}
    c = gen_card("a", basis=["Java", "portfolio"])
    factory = lambda: FakeAnthropicClient({"tech": ["Java 21", "Docker"]})
    summary = sp.refresh_tech_lists([c], {r["html_url"]: r}, "tok", factory)
    assert c["tech"] == ["Java 21", "Docker"]
    assert c["techBasis"] == sp.tech_basis(r, ["Java", "Dockerfile"])
    assert summary and "a" in summary[0]


def test_refresh_respects_tech_override(monkeypatch):
    _wire_refresh(monkeypatch, ["Go"], overrides={"tech": ["Manual"]})
    r = {**repo("a"), "topics": ["portfolio"]}
    c = gen_card("a", basis=["old"])
    summary = sp.refresh_tech_lists([c], {r["html_url"]: r}, "tok",
                                    lambda: (_ for _ in ()).throw(AssertionError("no client")))
    assert summary == [] and c["tech"] == ["Java"] and c["techBasis"] == ["old"]


def test_refresh_ignores_handwritten_and_unmatched(monkeypatch):
    _wire_refresh(monkeypatch, ["Go"])
    hand = card("hand")
    orphan = gen_card("orphan", basis=["old"])  # repo not in repos_by_url
    summary = sp.refresh_tech_lists([hand, orphan], {}, "tok",
                                    lambda: (_ for _ in ()).throw(AssertionError("no client")))
    assert summary == []
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest scripts/test_sync_projects.py -v`
Expected: new FAILs — `no attribute 'recurate_tech'`.

- [ ] **Step 3: Implement**

Append to `scripts/sync_projects.py` (after `generate_card_text`):

```python
TECH_SCHEMA = {
    "type": "object",
    "properties": {"tech": {"type": "array", "items": {"type": "string"}}},
    "required": ["tech"],
    "additionalProperties": False,
}

TECH_PROMPT = """The tech stack of a portfolio project changed on GitHub. Curate an updated \
tech list in the same style as the current one (short display names like "Spring Boot", \
"Docker" — max 6). Keep entries that are still accurate; add/remove based on the new data.

Current tech list: {current}
New GitHub topics + languages: {basis}

Return only the updated tech list."""


def recurate_tech(client, current_tech, basis):
    msg = client.messages.create(
        model=MODEL,
        max_tokens=1024,
        output_config={"format": {"type": "json_schema", "schema": TECH_SCHEMA}},
        messages=[{"role": "user", "content": TECH_PROMPT.format(
            current=json.dumps(current_tech, ensure_ascii=False),
            basis=json.dumps(basis, ensure_ascii=False))}],
    )
    text = next(b.text for b in msg.content if b.type == "text")
    return json.loads(text)["tech"]


def refresh_tech_lists(cards, repos_by_url, token, client_factory):
    """Re-curate tech on generated cards whose topics+languages changed.

    A `tech` override in .portfolio.yml wins: the card is left untouched.
    client_factory is called lazily, at most once.
    """
    summary, client = [], None
    for card in cards:
        if not card.get("generated") or card.get("github") not in repos_by_url:
            continue
        repo = repos_by_url[card["github"]]
        languages = fetch_languages(token, repo["full_name"])
        basis = tech_basis(repo, languages)
        if basis == card.get("techBasis"):
            continue
        if "tech" in fetch_portfolio_yml(token, repo["full_name"]):
            continue
        if client is None:
            client = client_factory()
        card["tech"] = recurate_tech(client, card["tech"], basis)
        card["techBasis"] = basis
        summary.append(f"- 🔄 Tech updated for `{card['slug']}` (stack changed on GitHub)")
    return summary
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 36 passed.

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_projects.py scripts/test_sync_projects.py
git commit -m "Re-curate tech lists when a generated card's stack changes"
```

---

### Task 4: main() wiring

**Files:**
- Modify: `scripts/sync_projects.py` (`main()`, imports)
- Test: `scripts/test_sync_projects.py` (append)

**Interfaces:**
- Consumes: `fill_images` from `screenshots` (Task 1), `refresh_tech_lists` (Task 3), `Anthropic` (existing import).
- Produces: `main()` now runs, after the removal filter and before the no-changes check: (1) `refresh_tech_lists(cards, repos_by_url, token, Anthropic)`; (2) `fill_images(cards)` — skipped under `--dry-run` with the printed note `"(dry-run: image filling skipped)"`.

- [ ] **Step 1: Write the failing tests**

Append to `scripts/test_sync_projects.py`:

```python
def test_main_fills_images_and_refreshes_tech(monkeypatch, tmp_path):
    projects = tmp_path / "projects.json"
    projects.write_text(json.dumps([card("hand")]))
    monkeypatch.setattr(sp, "PROJECTS_JSON", projects)
    monkeypatch.setenv("GITHUB_TOKEN", "tok")
    monkeypatch.delenv("GITHUB_OUTPUT", raising=False)
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(sp, "fetch_portfolio_repos", lambda tok: [repo("hand")])

    def fake_fill(cards):
        for c in cards:
            if c.get("image") is None:
                c["image"] = f"/images/projects/{c['slug']}.png"
        return ["- 🖼 Added screenshot for `hand`"]

    monkeypatch.setattr(sp, "fill_images", fake_fill)
    monkeypatch.setattr(sp, "refresh_tech_lists", lambda *a: ["- 🔄 Tech updated for `x`"])

    assert sp.main([]) == 0
    result = json.loads(projects.read_text())
    assert result[0]["image"] == "/images/projects/hand.png"
    body = (tmp_path / "pr-body.md").read_text()
    assert "🖼" in body and "🔄" in body


def test_main_dry_run_skips_image_filling(monkeypatch, tmp_path, capsys):
    projects = tmp_path / "projects.json"
    projects.write_text(json.dumps([card("hand")]))
    monkeypatch.setattr(sp, "PROJECTS_JSON", projects)
    monkeypatch.setenv("GITHUB_TOKEN", "tok")
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(sp, "fetch_portfolio_repos", lambda tok: [repo("hand")])
    monkeypatch.setattr(sp, "fill_images",
                        lambda cards: (_ for _ in ()).throw(AssertionError("must not run")))
    monkeypatch.setattr(sp, "refresh_tech_lists", lambda *a: ["- 🔄 Tech updated for `x`"])

    assert sp.main(["--dry-run"]) == 0
    assert "dry-run: image filling skipped" in capsys.readouterr().out
    assert json.loads(projects.read_text())[0]["image"] is None  # nothing written
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest scripts/test_sync_projects.py -v`
Expected: 2 new FAILs (no `fill_images` attribute on module / summaries missing from pr-body).

- [ ] **Step 3: Implement**

In `scripts/sync_projects.py` add to the imports (after `from anthropic import Anthropic`):

```python
from screenshots import fill_images
```

In `main()`, insert between the removal-filter block (`summary.extend(f"- ➖ Removed: ...` line 209) and the `if not summary:` check (line 211):

```python
    repos_by_url = {r["html_url"]: r for r in repos}
    summary.extend(refresh_tech_lists(cards, repos_by_url, token, Anthropic))
    if args.dry_run:
        print("(dry-run: image filling skipped)")
    else:
        summary.extend(fill_images(cards))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/ -v`
Expected: 38 passed.

- [ ] **Step 5: Commit**

```bash
git add scripts/sync_projects.py scripts/test_sync_projects.py
git commit -m "Wire image filling and tech refresh into the sync run"
```

---

### Task 5: Workflow — chromium + images in the PR

**Files:**
- Modify: `.github/workflows/sync-projects.yml`

**Interfaces:**
- Consumes: the workflow as shipped in PR #10 (pending-guard step `id: pending`, sync step `id: sync`, PR step with `add-paths: frontend/data/projects.json`).

- [ ] **Step 1: Add chromium install step**

Insert directly after the "Install dependencies" step (keep the same `if:` guard):

```yaml
      - name: Install chromium for screenshots
        if: steps.pending.outputs.open == '0'
        run: python -m playwright install --with-deps chromium
```

- [ ] **Step 2: Extend add-paths**

Change the PR step's `add-paths` to:

```yaml
          add-paths: |
            frontend/data/projects.json
            frontend/images/projects/
```

- [ ] **Step 3: Validate YAML**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/sync-projects.yml')); print('OK')"`
Expected: `OK`

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/sync-projects.yml
git commit -m "Install chromium and commit screenshots in the sync PR"
```

---

### Task 6: Migration + live validation (controller/user — after merge of Tasks 1–5)

**Files:**
- Modify: `frontend/data/projects.json` (add `liveUrl` to hand-written cards — pre-merge, on the feature branch)
- No other repo files.

- [ ] **Step 1: Add liveUrl to hand-written cards (on the feature branch, before PR)**

Edit `frontend/data/projects.json` — add `"liveUrl"` to these cards (gabelgundogs already has one; monkknows deliberately gets none → OG fallback):

| slug | liveUrl |
|---|---|
| portfolio | `https://andreasgabel.dk` |
| raidfines | `https://andreasgabel.dk/raidfines/` |
| talent-api | `https://andreasgabel.dk/talent-api/` |
| webcrawler | `https://andreasgabel.dk/webcrawler/` |
| beskyttelsesrum | `https://andreasgabel.dk/beskyttelsesrum/` |

Validate: `python3 -c "import json; json.load(open('frontend/data/projects.json')); print('OK')"` → `OK`
Commit: `git add frontend/data/projects.json && git commit -m "Add live URLs to hand-written cards for screenshot capture"`

- [ ] **Step 2: Set homepage on the live repos (mechanical source going forward)**

```bash
gh repo edit Gabel1998/portfolio --homepage "https://andreasgabel.dk"
gh repo edit Gabel1998/raid-fines --homepage "https://andreasgabel.dk/raidfines/"
gh repo edit Gabel1998/talent-api --homepage "https://andreasgabel.dk/talent-api/"
gh repo edit Gabel1998/webCrawler --homepage "https://andreasgabel.dk/webcrawler/"
gh repo edit Gabel1998/beskyttelsesrum --homepage "https://andreasgabel.dk/beskyttelsesrum/"
```

- [ ] **Step 3: Live validation (after the feature PR is merged to main)**

```bash
gh workflow run sync-projects.yml -R Gabel1998/portfolio
gh run watch -R Gabel1998/portfolio
gh pr view auto/sync-projects -R Gabel1998/portfolio
```

Expected: one PR filling `image` on 7 cards — 6 Playwright screenshots (the 5 newly added liveUrls + gabelgundogs' existing one) and 1 OG image (monkknows, parsed from nasOps/MonkKnows). insolvens-intelligence has a github URL → OG image too, so 8 filled in total if its repo resolves. Inspect the PNGs in the PR, merge, verify the site renders images.

- [ ] **Step 4: No-change run stays silent**

```bash
gh workflow run sync-projects.yml -R Gabel1998/portfolio
gh run watch -R Gabel1998/portfolio
```

Expected: `No changes.` — all images filled, no tech drift, no PR.
