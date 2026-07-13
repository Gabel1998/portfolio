#!/usr/bin/env python3
"""Sync portfolio cards in frontend/data/projects.json from GitHub repos.

Repos on the account opt in with the `portfolio` topic. New repos get a card
generated via the Claude API; hand-written cards (no `"generated": true`) are
read-only. Spec: docs/superpowers/specs/2026-07-13-auto-portfolio-cards-design.md
"""

import base64
from pathlib import Path

import requests
import yaml

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
