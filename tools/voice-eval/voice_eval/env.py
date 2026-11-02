"""Load KEY=VALUE pairs from a local .env file into the process environment.

The file holds secrets (ANTHROPIC_API_KEY), so values are never printed or logged; only the
names of the variables that were set are returned. A non-empty value already in the
environment wins, so a shell variable can override the file.
"""

from __future__ import annotations

import logging
import os
import re
from collections.abc import MutableMapping
from pathlib import Path

logger = logging.getLogger(__name__)

_QUOTES = ("'", '"')
_INLINE_COMMENT = re.compile(r"\s+#")


def _value(raw: str) -> str:
    """A quoted value ends at its closing quote; an unquoted one at a ' #' comment."""
    if raw[:1] in _QUOTES:
        closing = raw.find(raw[0], 1)
        return raw[1:closing] if closing > 0 else raw
    return _INLINE_COMMENT.split(raw, maxsplit=1)[0].rstrip()


def _parse_line(raw: str) -> tuple[str, str] | None:
    line = raw.strip()
    if not line or line.startswith("#") or "=" not in line:
        return None
    if line.startswith("export "):
        line = line[len("export ") :].lstrip()
    key, value = (part.strip() for part in line.split("=", 1))
    value = _value(value)
    if not key or not value:
        return None
    return key, value


def load_env_file(
    path: Path, environ: MutableMapping[str, str] | None = None
) -> tuple[str, ...]:
    """Set variables from ``path`` that are not already set; return their names."""
    target = os.environ if environ is None else environ
    if not path.is_file():
        return ()
    try:
        text = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError) as exc:
        # Never echo the content: the file holds secrets.
        logger.warning("%s을(를) 읽지 못했습니다(%s). UTF-8로 저장했는지 확인하세요.",
                       path.name, type(exc).__name__)
        return ()
    loaded = []
    for raw in text.splitlines():
        parsed = _parse_line(raw)
        if parsed is None:
            continue
        key, value = parsed
        if target.get(key):
            continue
        target[key] = value
        loaded.append(key)
    return tuple(loaded)
