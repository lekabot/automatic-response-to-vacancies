"""
Строгая релевантность: языковые токены только literal; экосистема не заменяет язык.
"""
from __future__ import annotations

import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from src.hh.schemas import VacancySchema

_QUERY_TOKEN_TO_LANGUAGE: dict[str, str] = {
    "java": "java",
    "python": "python",
    "go": "go",
    "golang": "go",
    "kotlin": "kotlin",
    "scala": "scala",
    "rust": "rust",
    "php": "php",
    "ruby": "ruby",
    "swift": "swift",
    "javascript": "javascript",
    "js": "javascript",
    "typescript": "typescript",
    "ts": "typescript",
    "c++": "cpp",
    "cpp": "cpp",
}

_ECOSYSTEM_TOKENS = frozenset(
    {
        "spring",
        "django",
        "fastapi",
        "flask",
        "react",
        "vue",
        "angular",
        "node",
        "nodejs",
        "nestjs",
        "kafka",
        "kubernetes",
        "docker",
        "terraform",
        "jvm",
        "boot",
    }
)

_GENERIC_TOKENS = frozenset(
    {
        "middle",
        "mid",
        "senior",
        "junior",
        "lead",
        "staff",
        "principal",
        "developer",
        "engineer",
        "разработчик",
        "программист",
        "software",
        "full",
        "stack",
        "fullstack",
        "full-stack",
        "backend",
        "back-end",
        "frontend",
        "front-end",
        "remote",
        "удалённ",
        "удаленн",
    }
)


def normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").lower().strip())


def tokenize_query(query: str) -> list[str]:
    return [t for t in re.findall(r"[a-zA-Zа-яА-ЯёЁ0-9+#]+", query.lower()) if len(t) > 1]


def _vacancy_blob(v: "VacancySchema") -> str:
    parts = [v.name, v.employer.name]
    if v.snippet:
        parts.append(v.snippet.requirement or "")
        parts.append(v.snippet.responsibility or "")
    return normalize_text(" ".join(parts))


def _vacancy_tokens(blob: str) -> set[str]:
    return set(re.findall(r"[a-zа-яё0-9+#]+", blob.lower()))


def _extract_language_requirements(query: str) -> list[str]:
    qlow = (query or "").lower()
    out: list[str] = []
    seen: set[str] = set()

    def add(lang: str) -> None:
        if lang not in seen:
            seen.add(lang)
            out.append(lang)

    if re.search(r"c\s*#|c#|csharp|\bdotnet\b|\.net", qlow):
        add("csharp")

    for t in tokenize_query(query):
        lang = _QUERY_TOKEN_TO_LANGUAGE.get(t)
        if lang:
            add(lang)

    return out


def _language_literal_satisfied(lang: str, tokens: set[str], blob: str) -> bool:
    if lang == "java":
        return "java" in tokens
    if lang == "python":
        return "python" in tokens
    if lang == "go":
        return "go" in tokens or "golang" in tokens
    if lang == "javascript":
        return "javascript" in tokens or "js" in tokens
    if lang == "typescript":
        return "typescript" in tokens or "ts" in tokens
    if lang == "kotlin":
        return "kotlin" in tokens or "kt" in tokens
    if lang == "php":
        return "php" in tokens
    if lang == "rust":
        return "rust" in tokens
    if lang == "ruby":
        return "ruby" in tokens
    if lang == "swift":
        return "swift" in tokens
    if lang == "scala":
        return "scala" in tokens
    if lang == "cpp":
        return "c++" in blob or "cpp" in tokens
    if lang == "csharp":
        return "c#" in blob or "csharp" in tokens or "dotnet" in tokens or ".net" in blob
    return False


def _extra_keywords(toks: list[str]) -> list[str]:
    """Слова запроса кроме generic / языка / экосистемы — должны встречаться во вакансии."""
    return [
        t
        for t in toks
        if t not in _GENERIC_TOKENS
        and t not in _QUERY_TOKEN_TO_LANGUAGE
        and t not in _ECOSYSTEM_TOKENS
        and len(t) > 2
    ]


def is_vacancy_relevant_to_query(
    query: str, vacancy: "VacancySchema"
) -> tuple[bool, list[str], list[str], list[str]]:
    """
    (ok, matched_tokens, missing_tokens, missing_required_language_tokens)
    """
    q_raw = (query or "").strip()
    if not q_raw:
        return True, [], [], []

    blob = _vacancy_blob(vacancy)
    title_n = normalize_text(vacancy.name)
    vac_tokens = _vacancy_tokens(blob)
    toks = tokenize_query(q_raw)

    phrase = normalize_text(q_raw)
    if len(phrase) >= 4 and phrase in title_n:
        return True, ["phrase_exact_title"], [], []

    langs = _extract_language_requirements(q_raw)
    miss_lang = [
        lang
        for lang in langs
        if not _language_literal_satisfied(lang, vac_tokens, blob)
    ]
    if miss_lang:
        return False, [], [], miss_lang

    extra = _extra_keywords(toks)
    if extra:
        miss = [t for t in extra if t not in blob]
        if miss:
            return False, [t for t in extra if t in blob], miss, []

    eco = [t for t in toks if t in _ECOSYSTEM_TOKENS]
    if eco and not langs:
        miss = [t for t in eco if t not in blob and t not in vac_tokens]
        if miss:
            matched = [t for t in eco if t not in miss]
            return False, matched, miss, []
        return True, eco, [], []

    if not langs and not eco:
        meaningful = [t for t in toks if t not in _GENERIC_TOKENS and len(t) > 2]
        if len(meaningful) >= 2:
            miss = [t for t in meaningful if t not in blob]
            if miss:
                return False, [t for t in meaningful if t in blob], miss, []
            return True, meaningful, [], []
        if len(meaningful) == 1:
            t = meaningful[0]
            if t in blob:
                return True, [t], [], []
            return False, [], [t], []

    if langs:
        return True, [f"lang:{l}" for l in langs], [], []

    return False, [], ["no_meaningful_tokens"], []


def vacancy_matches_keywords_strict(
    vacancy: "VacancySchema", keywords: list[str]
) -> bool:
    for kw in keywords:
        ok, _, _, _ = is_vacancy_relevant_to_query(kw, vacancy)
        if ok:
            return True
    return False
