"""
orchestrator.py — combines results from the three agents based on input text.

- Always runs scam_text_agent
- If URL detected → also runs url_risk_agent (all URLs, parallel)
- If phone number detected → also runs ledger_agent check (all numbers, parallel)
- Returns overallRisk = max(risks) with high > medium > low > unknown

Response shape is Section 4's, plus a purely additive `meta` block carrying per-agent
latencies and the serving mode. The Android detail sheet renders those directly, so a
user can see *which* agent produced a verdict, how long it took, and whether Granite
was live or mocked — instead of one opaque risk word.
"""
import asyncio
import re
import time
from typing import Any, Dict, List, Tuple

from agents.scam_text_agent import classify_text
from agents.url_risk_agent import check_url
from agents.ledger_agent import LEDGER_MODE, check_number_reputation
from agents.ledger_store import get_store
from agents.watsonx_client import mode_info as granite_mode_info

# URL regex: http/https, www., and bare domains with suspicious TLDs
_URL_RE = re.compile(
    r"""(?:https?://[^\s<>"']+|www\.[^\s<>"']+|[a-zA-Z0-9-]+\.(?:in|com|co\.in|xyz|tk|top|site|online|click|link|info|net|org)[^\s<>"']*)""",
    re.IGNORECASE,
)

# Phone regex: Indian 10-digit + optional +91, plus generic intl
_PHONE_RE = re.compile(
    r"""(?:\+91[\s\-]?)?[6-9]\d{9}\b|\+?\d{1,3}[\s\-]?\(?\d{3}\)?[\s\-]?\d{3}[\s\-]?\d{4}\b|\b\d{10}\b"""
)

_RISK_ORDER = {"high": 3, "medium": 2, "low": 1, "unknown": 0}


def _risk_level_to_number(risk: str) -> int:
    return _RISK_ORDER.get(risk, 0)


def _max_risk(risks: List[str]) -> str:
    if not risks:
        return "low"
    # unknown is treated as low for overallRisk, but preserved in details
    # if all unknown, overall is low
    best = "low"
    best_score = -1
    for r in risks:
        score = _RISK_ORDER.get(r, 0)
        # treat unknown as 0, but keep best as low if only unknown
        if r == "unknown":
            score = 0
        if score > best_score:
            best = r if r != "unknown" else "low"
            best_score = score
    return best


def extract_urls(text: str) -> List[str]:
    raw = _URL_RE.findall(text)
    # dedupe preserving order, strip trailing punctuation
    seen = set()
    out: List[str] = []
    for u in raw:
        # strip trailing punctuation that regex may capture
        u = u.rstrip(".,!;:)]}>\"'")
        # filter obvious false positives like "account.in" inside sentence without path? keep it — url_risk_agent will judge
        key = u.lower()
        if key not in seen:
            seen.add(key)
            out.append(u)
    return out


def extract_phones(text: str) -> List[str]:
    raw = _PHONE_RE.findall(text)
    # _PHONE_RE with groups returns tuples if groups present; flatten
    candidates: List[str] = []
    if raw and isinstance(raw[0], tuple):
        for tup in raw:
            # pick first non-empty group
            for part in tup:
                if part:
                    candidates.append(part)
                    break
    else:
        candidates = raw

    # Fallback: also search simple 10-digit
    if not candidates:
        candidates = re.findall(r"\b[6-9]\d{9}\b", text)

    seen = set()
    out: List[str] = []
    for p in candidates:
        norm = re.sub(r"[\s\-()]", "", p)
        if norm and norm not in seen:
            seen.add(norm)
            out.append(p.strip())
    return out


async def _timed(awaitable) -> Tuple[Any, int]:
    """
    Run an agent and report how long it took.

    Returns (result_or_exception, elapsed_ms) and never raises, so a single failing
    agent cannot take down the whole scan — the caller records it as an "unknown"
    detail and the other agents' verdicts still reach the user.
    """
    t0 = time.perf_counter()
    try:
        result = await awaitable
    except Exception as e:  # noqa: BLE001 — deliberately broad, see docstring
        return e, int((time.perf_counter() - t0) * 1000)
    return result, int((time.perf_counter() - t0) * 1000)


def _ledger_store_name() -> str:
    if LEDGER_MODE == "fabric":
        return "fabric"
    try:
        return get_store().name
    except Exception:
        return "unavailable"


def _mode_block() -> Dict:
    """What actually served this request — shown as chips in the app's detail sheet."""
    g = granite_mode_info()
    return {
        "granite": g["granite"],
        "model": g["model"],
        "graniteNote": g.get("reason"),
        "ledger": _ledger_store_name(),
        "ledgerMode": LEDGER_MODE,
    }


async def analyze(text: str) -> Dict:
    """
    Main orchestration entrypoint.
    Returns dict with overallRisk and details[] as per Section 4, plus meta{}.
    """
    started = time.perf_counter()

    if not text or not text.strip():
        return {
            "overallRisk": "low",
            "details": [{"source": "text", "risk": "low", "category": "none", "reason": "empty input"}],
            "meta": {
                "detectedUrls": [],
                "detectedPhones": [],
                "textLength": 0,
                "timings": {"textMs": 0, "urlMs": 0, "numberMs": 0, "totalMs": 0},
                "mode": _mode_block(),
            },
        }

    text = text.strip()

    # Start text classification immediately
    text_task = asyncio.create_task(_timed(classify_text(text)))

    urls = extract_urls(text)
    phones = extract_phones(text)

    # Fire parallel checks for urls and numbers
    url_tasks = [asyncio.create_task(_timed(check_url(u))) for u in urls]
    phone_tasks = [asyncio.create_task(_timed(check_number_reputation(p))) for p in phones]

    # Wait for text
    text_result, text_ms = await text_task
    if isinstance(text_result, Exception):
        text_result = {"risk": "unknown", "category": "none", "reason": f"text agent error: {text_result}"}

    details: List[Dict] = []
    risks: List[str] = []

    # Text detail
    details.append({
        "source": "text",
        "risk": text_result.get("risk", "unknown"),
        "category": text_result.get("category", "none"),
        "reason": text_result.get("reason", "could not classify"),
        "latencyMs": text_ms,
    })
    risks.append(text_result.get("risk", "unknown"))

    # URLs — slowest URL check, since they run concurrently with each other
    url_ms = 0
    if url_tasks:
        url_results = await asyncio.gather(*url_tasks, return_exceptions=True)
        for url, entry in zip(urls, url_results):
            if isinstance(entry, Exception):
                res, ms = entry, 0
            else:
                res, ms = entry
            url_ms = max(url_ms, ms)
            if isinstance(res, Exception):
                details.append({"source": "url", "url": url, "risk": "unknown", "reason": f"error: {res}", "latencyMs": ms})
                risks.append("unknown")
            else:
                details.append({
                    "source": "url",
                    "url": url,
                    "risk": res.get("risk", "unknown"),
                    "reason": res.get("reason", ""),
                    "flagged_by": res.get("flagged_by", "none"),
                    "latencyMs": ms,
                })
                risks.append(res.get("risk", "unknown"))

    # Phone numbers
    number_ms = 0
    if phone_tasks:
        phone_results = await asyncio.gather(*phone_tasks, return_exceptions=True)
        for phone, entry in zip(phones, phone_results):
            if isinstance(entry, Exception):
                res, ms = entry, 0
            else:
                res, ms = entry
            number_ms = max(number_ms, ms)
            if isinstance(res, Exception):
                details.append({"source": "number", "number": phone, "reported": False, "reportCount": 0, "riskLevel": "unknown", "error": str(res), "latencyMs": ms})
                risks.append("unknown")
            else:
                details.append({
                    "source": "number",
                    "number": phone,
                    "reported": res.get("reported", False),
                    "reportCount": res.get("reportCount", 0),
                    "riskLevel": res.get("riskLevel", "low"),
                    "mode": res.get("mode", "fallback"),
                    "store": res.get("store"),
                    "entries": res.get("entries", []),
                    "latencyMs": ms,
                })
                risks.append(res.get("riskLevel", "low"))

    overall = _max_risk(risks)
    total_ms = int((time.perf_counter() - started) * 1000)

    return {
        "overallRisk": overall,
        "details": details,
        "meta": {
            "detectedUrls": urls,
            "detectedPhones": phones,
            "textLength": len(text),
            "timings": {
                "textMs": text_ms,
                "urlMs": url_ms,
                "numberMs": number_ms,
                "totalMs": total_ms,
            },
            "mode": _mode_block(),
        },
    }
