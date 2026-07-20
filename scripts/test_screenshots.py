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
