"""
scam_text_agent.py — calls IBM Granite with the VERBATIM tuned prompt from the brief.
Spec: Section 5 exact prompt, JSON-only response, retry-once, fallback on invalid JSON.

Prompt template (do not edit — copy verbatim from brief):
"""
import json
import re
from typing import Dict

from .watsonx_client import generate

# Exact prompt from brief — {{message}} will be substituted
PROMPT_TEMPLATE = """You are a fraud-detection assistant protecting users in India from scams.
Given a message, classify it and respond ONLY in this exact JSON format,
nothing else, no explanation outside the JSON:

{
  "risk": "high" | "medium" | "low",
  "category": "OTP scam" | "fake refund/KYC" | "phishing link" | "impersonation" | "none",
  "reason": "one short sentence explaining why"
}

Look specifically for: urgency language ("act now", "account will be blocked"),
requests to share an OTP or PIN, fake bank/government/delivery impersonation,
suspicious or shortened links, and unusual payment/refund requests.
If the message is ordinary and shows none of these patterns, return risk "low"
and category "none".

Message to classify:
"{{message}}"
"""

VALID_RISKS = {"high", "medium", "low", "unknown"}
VALID_CATEGORIES = {"OTP scam", "fake refund/KYC", "phishing link", "impersonation", "none"}

FALLBACK = {"risk": "unknown", "category": "none", "reason": "could not classify"}


def _extract_json(text: str) -> Dict:
    """
    Tries to parse JSON even if model wraps it in markdown or adds extra text.
    """
    text = text.strip()
    # Strip markdown code fences
    if "```" in text:
        # extract first json block
        m = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
        if m:
            text = m.group(1)
    # Try direct parse
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    # Try to find first {...} JSON object
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(0))
        except json.JSONDecodeError:
            pass
    raise ValueError(f"no valid JSON in: {text[:500]}")


def _normalize(result: Dict) -> Dict:
    risk = str(result.get("risk", "unknown")).lower().strip()
    category = str(result.get("category", "none")).strip()
    reason = str(result.get("reason", "could not classify")).strip()

    # Normalize risk
    if risk not in VALID_RISKS:
        # map common variants
        if risk in ("critical", "very high"):
            risk = "high"
        else:
            risk = "unknown"

    # Normalize category — keep string as-is if not in known set, but default to none
    if category not in VALID_CATEGORIES:
        # attempt fuzzy mapping
        lower = category.lower()
        if "otp" in lower:
            category = "OTP scam"
        elif "kyc" in lower or "refund" in lower:
            category = "fake refund/KYC"
        elif "phish" in lower:
            category = "phishing link"
        elif "imperson" in lower:
            category = "impersonation"
        else:
            category = "none"

    if not reason:
        reason = "could not classify"

    return {"risk": risk, "category": category, "reason": reason[:200]}


async def classify_text(message: str) -> Dict:
    """
    Classifies a message using Granite. Returns {"risk","category","reason"}.
    Never raises — returns FALLBACK on any failure (invalid JSON, API error).
    """
    if not message or not message.strip():
        return {"risk": "low", "category": "none", "reason": "empty message"}

    prompt = PROMPT_TEMPLATE.replace("{{message}}", message)

    last_error = None
    for attempt in range(2):  # retry-once on transient API errors
        try:
            raw = await generate(prompt)
            if not raw or not raw.strip():
                raise ValueError("empty response from Granite")
            parsed = _extract_json(raw)
            return _normalize(parsed)
        except Exception as e:
            last_error = e
            if attempt == 0:
                # transient — retry once
                continue
            # after retry, fall through to fallback
            break

    # Log for debugging (visible in uvicorn logs) but return safe fallback
    print(f"[scam_text_agent] classification failed after retry: {last_error}; returning fallback")
    return FALLBACK.copy()
