"""
url_risk_agent.py — checks URLs for phishing/spoofing.

Logic (Section 6):
 1. First check Google Safe Browsing v4 lookup API if key is configured.
    If flagged → high risk immediately (saves Granite call).
 2. Else ask Granite to judge whether domain looks like lookalike/spoofed
    (e.g. arnaz0n-kyc.in vs amazon.in), same JSON-only pattern.

Returns: {"risk": "high"|"medium"|"low"|"unknown", "reason": str, "flagged_by": "safebrowsing"|"granite"|"none"}
"""
import os
import re
import json
from typing import Dict, Optional

import httpx
from dotenv import load_dotenv
from pathlib import Path

from http_client import get_client
from .watsonx_client import generate

load_dotenv(dotenv_path=Path(__file__).resolve().parents[1] / ".env")
load_dotenv()

SAFE_BROWSING_KEY = os.getenv("GOOGLE_SAFE_BROWSING_API_KEY", "").strip()

URL_PROMPT_TEMPLATE = """You are a URL phishing detector for users in India.
Given a URL, judge whether its domain looks like a lookalike or spoofed domain
(e.g. "arnaz0n-kyc.in" trying to mimic "amazon.in", "sbi-kyc-update.com" vs "sbi.co.in",
"flipkart-offer.xyz" vs "flipkart.com", shortened or suspicious domains).

Respond ONLY in this exact JSON format, nothing else:

{
  "risk": "high" | "medium" | "low",
  "reason": "one short sentence explaining why"
}

Consider: typosquatting (0 for o, rn for m), extra keywords like kyc/verify/secure/account/refund,
unusual TLDs (.xyz, .tk, .in variants), URL shorteners, and mismatched brand names.
If the URL looks legitimate and matches the expected brand domain, return risk "low".

URL to judge:
"{url}"
"""


async def _check_safe_browsing(url: str) -> Optional[Dict]:
    """
    Calls Google Safe Browsing Lookup API v4.
    Returns dict if flagged, None if safe or key missing/error.
    API: POST https://safebrowsing.googleapis.com/v4/threatMatches:find?key=...
    """
    if not SAFE_BROWSING_KEY:
        return None

    # Minimal threatTypes that matter for phishing/malware in India
    body = {
        "client": {"clientId": "scamshield", "clientVersion": "1.0"},
        "threatInfo": {
            "threatTypes": ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"],
            "platformTypes": ["ANY_PLATFORM"],
            "threatEntryTypes": ["URL"],
            "threatEntries": [{"url": url}],
        },
    }
    try:
        client = get_client()
        resp = await client.post(
            f"https://safebrowsing.googleapis.com/v4/threatMatches:find?key={SAFE_BROWSING_KEY}",
            json=body,
            timeout=10.0,
        )
        if resp.status_code != 200:
            print(f"[url_risk_agent] SafeBrowsing non-200: {resp.status_code} {resp.text[:300]}")
            return None
        data = resp.json()
        # If "matches" present, URL is flagged
        if data.get("matches"):
            first = data["matches"][0]
            threat_type = first.get("threatType", "SOCIAL_ENGINEERING")
            return {
                "flagged": True,
                "threat_type": threat_type,
                "raw": data,
            }
        return None
    except Exception as e:
        print(f"[url_risk_agent] SafeBrowsing check failed: {e}")
        return None


def _extract_json(text: str) -> Dict:
    text = text.strip()
    if "```" in text:
        m = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
        if m:
            text = m.group(1)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(0))
        except json.JSONDecodeError:
            pass
    raise ValueError(f"no JSON in: {text[:500]}")


async def check_url(url: str) -> Dict:
    """
    Checks a single URL. Returns {"risk", "reason", "flagged_by"}.
    Never raises — returns unknown on failure.
    """
    url = url.strip().strip('"').strip("'")
    if not url:
        return {"risk": "unknown", "reason": "empty URL", "flagged_by": "none"}

    # Normalize: add scheme if missing for SafeBrowsing (it needs http)
    normalized = url
    if not re.match(r"^https?://", normalized, re.I):
        if normalized.startswith("www."):
            normalized = "http://" + normalized
        else:
            normalized = "http://" + normalized

    # Step 1: Safe Browsing
    sb = await _check_safe_browsing(normalized)
    if sb and sb.get("flagged"):
        return {
            "risk": "high",
            "reason": f"flagged by Google Safe Browsing as {sb.get('threat_type', 'unsafe')}",
            "flagged_by": "safebrowsing",
        }

    # Step 2: Granite spoof judgment
    prompt = URL_PROMPT_TEMPLATE.replace("{url}", normalized)
    for attempt in range(2):
        try:
            raw = await generate(prompt)
            parsed = _extract_json(raw)
            risk = str(parsed.get("risk", "unknown")).lower().strip()
            reason = str(parsed.get("reason", "could not classify")).strip()
            if risk not in ("high", "medium", "low", "unknown"):
                risk = "unknown"
            return {"risk": risk, "reason": reason[:250], "flagged_by": "granite"}
        except Exception as e:
            if attempt == 0:
                continue
            print(f"[url_risk_agent] Granite URL check failed after retry: {e}")
            return {"risk": "unknown", "reason": "could not classify URL", "flagged_by": "none"}

    return {"risk": "unknown", "reason": "could not classify URL", "flagged_by": "none"}
