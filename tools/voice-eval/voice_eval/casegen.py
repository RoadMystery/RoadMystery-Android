"""Cases a model writes from the authoring rules alone, the way a scenario generator or a player uploading a
scenario would (docs/voice-agent/scenario-rules.md).

case_request builds the request from the style, the rules and the JSON schemas (gen-prompts/
casegen_system.ko.md) and the brief, and from no existing case. parse_case_reply takes the files out of the
reply and write_case lays them out under <root>/cases/<id>/; voice_eval.validate.validate_case then decides
whether the case may be measured, and fix_message sends its problems back for another try.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

PROMPT_PATH = Path(__file__).resolve().parents[1] / "gen-prompts" / "casegen_system.ko.md"
STYLES = {
    "ai": (
        "너는 차량용 음성 추리 게임의 시나리오를 만드는 AI다. 운전자가 말로 묻고 답을 들으며 푸는 추리 사건을 쓴다. "
        "규칙을 빠짐없이 지키고, 단서와 용의자의 반응으로 범인을 가려낼 수 있게 짜임새 있게 쓴다."
    ),
    "user": (
        "너는 이 게임에 직접 만든 시나리오를 올리려는 일반 사용자다. 추리 소설을 좋아하는 직장인이고 시나리오는 처음 써 본다. "
        "꼭 지켜야 하는 규칙만 지키고, 문장은 다듬지 않고 생각나는 대로 쓴다. "
        "선택 칸(mentions, demeanor, pressure)은 쓰지 않는다."
    ),
}
"""Who writes the case: a generator that follows every rule, or a first-time author."""
TIPS_HEADING = "## 대사가 잘 통하는 법"
"""The rules section a first-time author is taken not to have read: advice, not something checked."""
_FILE_ID = re.compile(r"[a-z][a-z0-9-]*")
_JSON_BLOCK = re.compile(r"```json\s*\n(.*?)\n```", re.DOTALL)


def rules_for(style: str, rules: str) -> str:
    """[rules] as the author of [style] reads them."""
    if style != "user" or TIPS_HEADING not in rules:
        return rules
    head, _, tail = rules.partition(TIPS_HEADING)
    _, heading, rest = tail.partition("\n## ")
    return head + (heading.lstrip("\n") + rest if heading else "")


def case_request(
    style: str, case_id: str, brief: str, rules: str, schema_dir: Path, template: str | None = None
) -> tuple[str, str]:
    """(system, user) asking for case [case_id] set in [brief]; raises KeyError for an unknown style."""
    system = (template or PROMPT_PATH.read_text(encoding="utf-8")).format(
        style=STYLES[style],
        rules=rules_for(style, rules).strip(),
        case_schema=(schema_dir / "case.schema.json").read_text(encoding="utf-8").strip(),
        suspect_schema=(schema_dir / "suspect.schema.json").read_text(encoding="utf-8").strip(),
    )
    user = f"사건 id: {case_id}\n설정: {brief}\n용의자 셋, 단서 넷으로 쓴다."
    return system, user


def parse_case_reply(text: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """(case.json, suspect files) from the reply's JSON block; raises ValueError when there is none."""
    match = _JSON_BLOCK.search(text)
    body = match.group(1) if match else text[text.find("{") : text.rfind("}") + 1]
    try:
        data = json.loads(body)
    except json.JSONDecodeError as error:
        raise ValueError(f"답에서 JSON을 읽지 못했습니다 ({error})") from error
    # A reply of the wrong shape is a malformed reply like one that is not JSON at all, hence ValueError.
    shaped = (
        isinstance(data, dict)
        and isinstance(data.get("case"), dict)
        and isinstance(data.get("suspects"), list)
        and all(isinstance(suspect, dict) for suspect in data["suspects"])
    )
    if not shaped:
        raise ValueError('답이 {"case": {...}, "suspects": [{...}, ...]} 모양이 아닙니다')
    return data["case"], data["suspects"]


def _dump(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def write_case(root: Path, case_id: str, case: dict[str, Any], suspects: list[dict[str, Any]]) -> Path:
    """Write the case as <root>/cases/<case_id>/ with its schema references; returns that directory."""
    ids = [str(suspect.get("id", "")) for suspect in suspects]
    if bad := [suspect_id for suspect_id in [case_id, *ids] if not _FILE_ID.fullmatch(suspect_id)]:
        raise ValueError(f"파일 이름으로 쓸 수 없는 id가 있습니다 {bad}")
    case_dir = root / "cases" / case_id
    (case_dir / "suspects").mkdir(parents=True, exist_ok=True)
    body = {key: value for key, value in case.items() if key != "$schema"}
    _dump(case_dir / "case.json", {"$schema": "../../schema/case.schema.json", **body, "id": case_id})
    for suspect in suspects:
        body = {key: value for key, value in suspect.items() if key != "$schema"}
        _dump(case_dir / "suspects" / f"{suspect['id']}.json", {"$schema": "../../../schema/suspect.schema.json", **body})
    return case_dir


def fix_message(problems: list[str]) -> str:
    """What the author is told when the case does not pass the rules."""
    listed = "\n".join(f"- {problem}" for problem in problems)
    return f"검사에서 아래 문제가 나왔다. 모두 고친 사건 전체를 같은 형식으로 다시 답한다.\n{listed}"
