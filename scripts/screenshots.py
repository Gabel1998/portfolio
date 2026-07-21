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
        try:
            page = browser.new_page(viewport=VIEWPORT)
            page.goto(url, wait_until="networkidle", timeout=30_000)
            page.wait_for_timeout(2_000)  # settle SPAs after networkidle
            page.screenshot(path=str(dest))
        finally:
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
