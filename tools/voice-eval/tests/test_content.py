import json
import shutil
from pathlib import Path

import pytest
from conftest import CASE_DIR, PROBES_PATH

from voice_eval.content import (
    KOREAN_SPEAKERS,
    Case,
    ContentError,
    Suspect,
    load_case,
    load_probes,
    load_suspect,
)
from voice_eval.prompt import allowed_facts

BUTLER_PATH = CASE_DIR / "suspects" / "butler.json"
JADE_DIR = CASE_DIR.parent / "jade-crown"
SCHEMA_DIR = CASE_DIR.parent.parent / "schema"


def _write_variant(tmp_path: Path, source: Path, mutate) -> Path:
    data = json.loads(source.read_text(encoding="utf-8"))
    mutate(data)
    target = tmp_path / source.name
    target.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return target


def test_case_loads_cast_clues_and_solution(case: Case) -> None:
    assert case.id == "demo-sapphire"
    assert case.theme == "family"
    assert [clue.id for clue in case.clues] == [
        "clue-fusebox",
        "clue-wet-gloves",
        "clue-cellar-key",
        "clue-pawn-ticket",
    ]
    assert case.member("butler").name == "한도윤"
    assert case.solution.culprit == "butler"
    assert "동백" in case.forbidden_phrases


def test_hidden_facts_are_not_copies_of_what_any_suspect_may_say(case: Case) -> None:
    hidden = [fact for clue in case.clues for fact in clue.hidden_facts] + list(case.solution.hidden_facts)
    suspects = [load_suspect(CASE_DIR / "suspects" / f"{sid}.json", case) for sid in ("butler", "niece", "cook")]
    public = {fact for suspect in suspects for fact in allowed_facts(case, suspect, frozenset())}

    assert all(clue.hidden_facts for clue in case.clues)
    assert case.solution.hidden_facts
    # Exact copies only; a paraphrase of a public fact needs a person to read the list.
    assert not set(hidden) & public
    # The butler's alibi (wine from the cellar) is public, so the solution facts must not repeat it.
    assert not any("와인" in fact for fact in case.solution.hidden_facts)


def test_before_found_lines_load_with_their_clue(case: Case, butler: Suspect) -> None:
    assert [line.clue for line in butler.before_found] == [clue.id for clue in case.clues]
    assert all(line.text for line in butler.before_found)


def test_before_found_must_reference_existing_clue(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["knowledge"]["beforeFound"][0]["clue"] = "clue-nope"

    path = _write_variant(tmp_path, BUTLER_PATH, mutate)
    with pytest.raises(ContentError, match="clue-nope"):
        load_suspect(path, case)


def test_before_found_has_one_line_per_clue(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        lines = data["knowledge"]["beforeFound"]
        lines.append(dict(lines[0]))

    path = _write_variant(tmp_path, BUTLER_PATH, mutate)
    with pytest.raises(ContentError, match="duplicate beforeFound clue"):
        load_suspect(path, case)


def _reaction(data: dict, fact_id: str) -> dict:
    return next(fact for fact in data["knowledge"]["unlockable"] if fact["id"] == fact_id)


def _add_reaction_to_both_clues(data: dict, *replaces: str) -> None:
    data["knowledge"]["unlockable"].append(
        {
            "id": "k-both",
            "unlockedBy": ["clue-fusebox", "clue-wet-gloves"],
            "text": "두 단서를 다 보고 털어놓는 말",
            "replaces": list(replaces),
        }
    )


def _replacing(fact_id: str, *targets: str):
    def mutate(data: dict) -> None:
        _reaction(data, fact_id)["replaces"] = list(targets)

    return mutate


def _replacing_a_key_reaction(data: dict) -> None:
    _reaction(data, "k-fusebox")["key"] = True
    _add_reaction_to_both_clues(data, "k-fusebox")


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (_replacing("k-fusebox", "k-nope"), "unknown fact 'k-nope'"),
        (_replacing("k-fusebox", "k-fusebox"), "replaces itself"),
        (_replacing("k-fusebox", "k-gloves"), "needs clues"),
        (_replacing_a_key_reaction, "replaces key reaction 'k-fusebox'"),
    ],
)
def test_a_reaction_replaces_only_lines_already_shown_that_no_key_reaction_needs(
    tmp_path: Path, case: Case, mutate, message: str
) -> None:
    path = _write_variant(tmp_path, BUTLER_PATH, mutate)
    with pytest.raises(ContentError, match=message):
        load_suspect(path, case)


def test_a_reaction_may_replace_public_facts_and_reactions_shown_before_it(tmp_path: Path, case: Case) -> None:
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: _add_reaction_to_both_clues(data, "k-alibi-claim", "k-fusebox"))
    suspect = load_suspect(path, case)

    assert {fact.id: fact.replaces for fact in suspect.unlockable}["k-both"] == ("k-alibi-claim", "k-fusebox")
    assert all(fact.replaces == () for fact in suspect.unlockable if fact.id != "k-both")


def test_hidden_facts_and_before_found_are_optional(tmp_path: Path, case: Case) -> None:
    case_dir = tmp_path / "case"
    case_dir.mkdir()

    def strip_case(data: dict) -> None:
        data["solution"].pop("hiddenFacts")
        for clue in data["clues"]:
            clue.pop("hiddenFacts")

    _write_variant(case_dir, CASE_DIR / "case.json", strip_case)
    plain_case = load_case(case_dir, schema_dir=CASE_DIR.parent.parent / "schema")
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: data["knowledge"].pop("beforeFound"))

    assert all(clue.hidden_facts == () for clue in plain_case.clues)
    assert plain_case.solution.hidden_facts == ()
    assert load_suspect(path, case).before_found == ()


def test_a_fact_line_is_what_the_prompt_shows_and_its_text_stays_the_answer_key(
    tmp_path: Path, case: Case
) -> None:
    def mutate(data: dict) -> None:
        data["knowledge"]["public"][0]["line"] = "정전 직전에 회장님 지시로 생신용 와인을 가지러 내려갔습니다."
        data["knowledge"]["unlockable"][0]["line"] = "두꺼비집이 저장고 바로 옆에 있는 건 맞습니다."

    butler = load_suspect(_write_variant(tmp_path, BUTLER_PATH, mutate), case)
    alibi, unchanged = butler.public[0], butler.public[1]

    assert alibi.prompt_text == "정전 직전에 회장님 지시로 생신용 와인을 가지러 내려갔습니다."
    assert alibi.text.endswith("말한다.")
    assert unchanged.line is None and unchanged.prompt_text == unchanged.text
    assert butler.unlockable[0].prompt_text == "두꺼비집이 저장고 바로 옆에 있는 건 맞습니다."


def test_narrator_hints_load_and_are_optional(case: Case) -> None:
    jade = load_case(JADE_DIR)

    assert all(clue.hint for clue in jade.clues) and jade.spoiler_hint
    assert all(clue.hint is None for clue in case.clues) and case.spoiler_hint is None


def test_mentions_key_reactions_and_pressure_lines_load_and_are_optional(case: Case) -> None:
    jade = load_case(JADE_DIR)
    jade_suspects = [
        load_suspect(JADE_DIR / "suspects" / f"{member.id}.json", jade) for member in jade.cast if member.is_suspect
    ]
    demo_suspects = [load_suspect(CASE_DIR / "suspects" / f"{sid}.json", case) for sid in ("butler", "niece", "cook")]

    assert jade.clue("clue-window").mentions == ("창문", "서재 창", "창밖", "창 아래", "창가", "창을 열", "창 열", "실내화")
    assert {fact.id for suspect in jade_suspects for fact in suspect.unlockable if fact.key} == {
        "k-son-shoes",
        "k-niece-window",
        "k-maid-back-door",
    }
    assert all([stage for stage, _ in suspect.pressure] == ["pressing", "ready"] for suspect in jade_suspects)
    assert all(clue.mentions == () for clue in case.clues)
    assert not any(fact.key for suspect in demo_suspects for fact in suspect.unlockable)
    assert all(suspect.pressure == () for suspect in demo_suspects)


def test_pressure_lines_load_in_stage_order(tmp_path: Path, case: Case) -> None:
    path = _write_variant(
        tmp_path,
        BUTLER_PATH,
        lambda data: data.update(pressure={"ready": "다 말씀드렸습니다.", "pressing": "면목이 없습니다."}),
    )

    assert load_suspect(path, case).pressure == (("pressing", "면목이 없습니다."), ("ready", "다 말씀드렸습니다."))


@pytest.mark.parametrize("stage", ["opening", "gathering", "finale"])
def test_pressure_lines_are_only_for_pressing_and_ready(tmp_path: Path, case: Case, stage: str) -> None:
    # Nothing is added to the prompt before "pressing", so a line for an earlier stage would never be used.
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: data.update(pressure={stage: "면목이 없습니다."}))
    with pytest.raises(ContentError, match=rf"pressure: .*'{stage}'"):
        load_suspect(path, case)


def test_a_one_letter_mention_is_rejected(tmp_path: Path) -> None:
    # Matched with spacing ignored and inside longer words, so 창 would also find 창고.
    case_dir = tmp_path / "case"
    case_dir.mkdir()
    _write_variant(case_dir, CASE_DIR / "case.json", lambda data: data["clues"][0].update(mentions=["창"]))
    with pytest.raises(ContentError, match="clues/0/mentions/0"):
        load_case(case_dir, schema_dir=CASE_DIR.parent.parent / "schema")


def test_a_clue_lists_each_mention_once(tmp_path: Path) -> None:
    case_dir = tmp_path / "case"
    case_dir.mkdir()
    _write_variant(case_dir, CASE_DIR / "case.json", lambda data: data["clues"][0].update(mentions=["뒷문", "뒷문"]))
    with pytest.raises(ContentError, match="clues/0/mentions: .*non-unique"):
        load_case(case_dir, schema_dir=SCHEMA_DIR)


@pytest.mark.parametrize(
    ("mutate", "where"),
    [
        pytest.param(lambda data: data["clues"][0].update(hint=""), "clues/0/hint", id="clue"),
        pytest.param(lambda data: data["spoilerGuard"].update(hint=""), "spoilerGuard/hint", id="spoiler-guard"),
    ],
)
def test_an_empty_hint_is_rejected(tmp_path: Path, mutate, where: str) -> None:
    case_dir = tmp_path / "case"
    case_dir.mkdir()
    _write_variant(case_dir, CASE_DIR / "case.json", mutate)
    with pytest.raises(ContentError, match=f"{where}: '' should be non-empty"):
        load_case(case_dir, schema_dir=SCHEMA_DIR)


@pytest.mark.parametrize(
    "line",
    ["", " 앞에 빈칸이 있습니다.", "뒤에 빈칸이 있습니다. ", "가운데서\n줄이 바뀝니다.", "끝에서 줄이 바뀝니다.\n"],
)
def test_a_fact_line_is_one_line_without_surrounding_whitespace(tmp_path: Path, case: Case, line: str) -> None:
    # The prompt shows a line after "- " on a line of its own. A final line break is the case a '$' in the
    # schema's pattern would let through, since Python's '$' also matches before one.
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: data["knowledge"]["public"][0].update(line=line))
    with pytest.raises(ContentError, match="knowledge/public/0/line: "):
        load_suspect(path, case)


@pytest.mark.parametrize("value", ["", " 면목이 없습니다.", "면목이 없습니다.\n"])
def test_a_pressure_line_is_one_line_without_surrounding_whitespace(tmp_path: Path, case: Case, value: str) -> None:
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: data.update(pressure={"pressing": value}))
    with pytest.raises(ContentError, match="pressure/pressing: "):
        load_suspect(path, case)


def test_a_secret_takes_no_line(tmp_path: Path, case: Case) -> None:
    path = _write_variant(
        tmp_path, BUTLER_PATH, lambda data: data["knowledge"]["secret"][0].update(line="말씀드릴 수 없습니다.")
    )
    with pytest.raises(ContentError, match=r"knowledge/secret/0: .*'line'"):
        load_suspect(path, case)


def test_suspect_takes_name_and_role_from_cast(butler: Suspect) -> None:
    assert butler.name == "한도윤"
    assert butler.role == "집사"
    assert butler.pleos_speaker in KOREAN_SPEAKERS
    assert len(butler.secret) == 3


def test_unknown_clue_lookup_raises_key_error(case: Case) -> None:
    with pytest.raises(KeyError):
        case.clue("clue-does-not-exist")


def test_unlocked_by_must_reference_existing_clue(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["knowledge"]["unlockable"][0]["unlockedBy"] = ["clue-nope"]

    path = _write_variant(tmp_path, BUTLER_PATH, mutate)
    with pytest.raises(ContentError, match="clue-nope"):
        load_suspect(path, case)


def test_schema_violation_names_missing_field(tmp_path: Path, case: Case) -> None:
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: data.pop("fallbackLines"))
    with pytest.raises(ContentError, match="fallbackLines"):
        load_suspect(path, case)


def test_suspect_must_be_a_suspect_in_cast(tmp_path: Path, case: Case) -> None:
    path = _write_variant(tmp_path, BUTLER_PATH, lambda data: data.update(id="chairman"))
    with pytest.raises(ContentError, match="chairman"):
        load_suspect(path, case)


def test_relationship_target_must_exist(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["relationships"].append({"target": "ghost", "attitude": "모름"})

    path = _write_variant(tmp_path, BUTLER_PATH, mutate)
    with pytest.raises(ContentError, match="ghost"):
        load_suspect(path, case)


def test_solution_culprit_must_be_cast_member(tmp_path: Path) -> None:
    case_dir = tmp_path / "case"
    case_dir.mkdir()
    _write_variant(
        case_dir,
        CASE_DIR / "case.json",
        lambda data: data["solution"].update(culprit="ghost"),
    )
    schema_dir = CASE_DIR.parent.parent / "schema"
    with pytest.raises(ContentError, match="ghost"):
        load_case(case_dir, schema_dir=schema_dir)


def test_probes_load_and_reference_known_clues(case: Case) -> None:
    probes = load_probes(PROBES_PATH, case)
    assert len(probes) >= 15
    known = {clue.id for clue in case.clues}
    assert all(set(probe.unlocked) <= known for probe in probes)
    assert {probe.category for probe in probes} >= {"in_scope", "spoiler", "jailbreak", "off_topic"}


def test_probe_with_unknown_clue_is_rejected(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["probes"][0]["unlocked"] = ["clue-nope"]

    path = _write_variant(tmp_path, PROBES_PATH, mutate)
    with pytest.raises(ContentError, match="clue-nope"):
        load_probes(path, case)


def test_a_probe_stage_loads_and_defaults_to_opening(tmp_path: Path, case: Case) -> None:
    every_clue = [clue.id for clue in case.clues]
    path = _write_variant(
        tmp_path, PROBES_PATH, lambda data: data["probes"][0].update(stage="pressing", unlocked=every_clue)
    )
    first, *rest = load_probes(path, case)

    assert first.stage == "pressing"
    assert rest and all(probe.stage == "opening" for probe in rest)


def test_a_late_stage_probe_must_unlock_every_clue(tmp_path: Path, case: Case) -> None:
    path = _write_variant(
        tmp_path, PROBES_PATH, lambda data: data["probes"][0].update(stage="ready", unlocked=["clue-fusebox"])
    )
    with pytest.raises(ContentError, match="without every clue unlocked"):
        load_probes(path, case)


def test_probe_with_unknown_stage_is_rejected(tmp_path: Path, case: Case) -> None:
    path = _write_variant(tmp_path, PROBES_PATH, lambda data: data["probes"][0].update(stage="finale"))
    with pytest.raises(ContentError, match="unknown stage 'finale'"):
        load_probes(path, case)


def test_probe_with_unknown_key_is_rejected(tmp_path: Path, case: Case) -> None:
    # Dropped quietly, a misspelt "stage" would ask a probe meant for "pressing" at "opening".
    path = _write_variant(tmp_path, PROBES_PATH, lambda data: data["probes"][0].update(stgae="pressing"))
    with pytest.raises(ContentError, match=r"unknown key\(s\): 'stgae'"):
        load_probes(path, case)


@pytest.mark.parametrize("suspect_id", ["butler", "niece", "cook"])
def test_every_suspect_has_a_persona_and_probes(case: Case, suspect_id: str) -> None:
    suspect = load_suspect(CASE_DIR / "suspects" / f"{suspect_id}.json", case)
    probes = load_probes(PROBES_PATH.parent / f"{suspect_id}.json", case)

    assert suspect.name == case.member(suspect_id).name
    assert suspect.secret and suspect.unlockable
    assert len(probes) >= 15
    assert {"in_scope", "clue_reaction", "spoiler", "jailbreak", "stt_noise"} <= {
        probe.category for probe in probes
    }


def test_each_suspect_has_a_distinct_voice(case: Case) -> None:
    speakers = {
        load_suspect(CASE_DIR / "suspects" / f"{suspect_id}.json", case).pleos_speaker
        for suspect_id in ("butler", "niece", "cook")
    }
    assert len(speakers) == 3


def test_probe_history_is_checked_when_loading(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["probes"][0]["history"] = [{"role": "user", "content": "먼저 한 질문"}]

    path = _write_variant(tmp_path, PROBES_PATH, mutate)
    with pytest.raises(ContentError, match="assistant"):
        load_probes(path, case)


def test_probe_ids_must_be_unique(tmp_path: Path, case: Case) -> None:
    def mutate(data: dict) -> None:
        data["probes"][1]["id"] = data["probes"][0]["id"]

    path = _write_variant(tmp_path, PROBES_PATH, mutate)
    with pytest.raises(ContentError, match="duplicate"):
        load_probes(path, case)


def test_case_dir_copy_loads_with_default_schema_location(tmp_path: Path) -> None:
    content_root = tmp_path / "content"
    shutil.copytree(CASE_DIR.parent.parent, content_root)
    loaded = load_case(content_root / "cases" / "demo-sapphire")
    assert loaded.id == "demo-sapphire"
