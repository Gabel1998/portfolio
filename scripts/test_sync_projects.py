import base64

import pytest
import requests

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


def test_non_404_http_errors_propagate(monkeypatch):
    monkeypatch.setattr(sp.requests, "get",
                        fake_get({"/repos/Gabel1998/a/readme": FakeResponse({}, status=500)}))
    with pytest.raises(requests.HTTPError):
        sp.fetch_readme("tok", "Gabel1998/a")
