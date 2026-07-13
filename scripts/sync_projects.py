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
