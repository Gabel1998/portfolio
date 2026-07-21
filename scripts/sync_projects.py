#!/usr/bin/env python3
"""Sync portfolio cards in frontend/data/projects.json from GitHub repos.

Repos on the account opt in with the `portfolio` topic. New repos get a card
generated via the Claude API; hand-written cards (no `"generated": true`) are
read-only. Spec: docs/superpowers/specs/2026-07-13-auto-portfolio-cards-design.md
"""

import argparse
import base64
import json
import os
from pathlib import Path

import requests
import yaml
from anthropic import Anthropic

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


THIN_THRESHOLD = 300  # chars of stripped README below which content is "thin"
OVERRIDABLE = {"title", "description", "descriptionDa", "tech", "image", "liveUrl"}


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


def tech_basis(repo, languages):
    """Sorted snapshot of topics + languages; used to detect stack changes."""
    return sorted(set(repo.get("topics", [])) | set(languages))


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
    parsed = yaml.safe_load(base64.b64decode(data["content"]))
    return parsed if isinstance(parsed, dict) else {}


def fetch_languages(token, full_name):
    langs = gh_get(f"/repos/{full_name}/languages", token)
    return sorted(langs, key=langs.get, reverse=True)


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
            cards.append(build_card(repo, text, overrides, languages))
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
