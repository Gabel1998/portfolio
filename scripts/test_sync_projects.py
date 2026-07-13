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
