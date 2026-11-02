from pathlib import Path

import pytest

from voice_eval.env import load_env_file


def _write(tmp_path: Path, text: str, encoding: str = "utf-8") -> Path:
    path = tmp_path / ".env"
    path.write_text(text, encoding=encoding)
    return path


def test_loads_values_and_returns_only_names(tmp_path: Path) -> None:
    environ: dict[str, str] = {}
    path = _write(
        tmp_path,
        "# 주석\n\nANTHROPIC_API_KEY=sk-test-123\nexport PROXY_TOKEN = 'dev token'\nQUOTED=\"a b\"\n",
    )

    loaded = load_env_file(path, environ)

    assert loaded == ("ANTHROPIC_API_KEY", "PROXY_TOKEN", "QUOTED")
    assert environ == {
        "ANTHROPIC_API_KEY": "sk-test-123",
        "PROXY_TOKEN": "dev token",
        "QUOTED": "a b",
    }


def test_existing_non_empty_value_wins(tmp_path: Path) -> None:
    environ = {"ANTHROPIC_API_KEY": "from-shell", "EMPTY": ""}
    path = _write(tmp_path, "ANTHROPIC_API_KEY=from-file\nEMPTY=filled\n")

    loaded = load_env_file(path, environ)

    assert loaded == ("EMPTY",)
    assert environ == {"ANTHROPIC_API_KEY": "from-shell", "EMPTY": "filled"}


def test_blank_values_from_the_example_template_are_skipped(tmp_path: Path) -> None:
    environ: dict[str, str] = {}
    assert load_env_file(_write(tmp_path, "ANTHROPIC_API_KEY=\nBROKEN LINE\n=x\n"), environ) == ()
    assert environ == {}


def test_byte_order_mark_from_windows_editors_is_ignored(tmp_path: Path) -> None:
    environ: dict[str, str] = {}
    path = _write(tmp_path, "ANTHROPIC_API_KEY=sk-bom\n", encoding="utf-8-sig")
    load_env_file(path, environ)
    assert environ == {"ANTHROPIC_API_KEY": "sk-bom"}


def test_missing_file_loads_nothing(tmp_path: Path) -> None:
    environ: dict[str, str] = {}
    assert load_env_file(tmp_path / "nope.env", environ) == ()
    assert environ == {}


@pytest.mark.parametrize("encoding", ["utf-16", "cp949"])
def test_a_file_that_is_not_utf8_is_skipped_without_showing_it(
    tmp_path: Path, caplog: pytest.LogCaptureFixture, encoding: str
) -> None:
    environ: dict[str, str] = {}
    path = _write(tmp_path, "# 한글 주석\nANTHROPIC_API_KEY=sk-not-utf8\n", encoding=encoding)

    assert load_env_file(path, environ) == ()

    assert environ == {}
    assert "UTF-8" in caplog.text
    assert "sk-not-utf8" not in caplog.text


def test_inline_comments_are_not_part_of_the_value(tmp_path: Path) -> None:
    environ: dict[str, str] = {}
    path = _write(
        tmp_path,
        "VOICE_PROXY_TOKEN=abc123 # dev\nANTHROPIC_API_KEY=\"sk-x\"  # note\nHASH=a#b\nQUOTED='a # b'\n",
    )

    load_env_file(path, environ)

    assert environ == {"VOICE_PROXY_TOKEN": "abc123", "ANTHROPIC_API_KEY": "sk-x", "HASH": "a#b",
                       "QUOTED": "a # b"}
