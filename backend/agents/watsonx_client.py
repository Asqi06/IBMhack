"""
watsonx_client.py — thin wrapper around IBM watsonx.ai Granite REST API.

Handles:
 - IAM token exchange (api_key -> bearer token) with 1h cache
 - Generation call to /ml/v1/text/generation
 - Fallback to /ml/v1/text/chat if generation endpoint 404
 - MOCK_GRANITE mode for local dev without credentials
 - retry-once on transient 429/5xx/timeout

Environment:
  WATSONX_API_KEY, WATSONX_PROJECT_ID, WATSONX_URL, WATSONX_MODEL_ID, WATSONX_IAM_URL, MOCK_GRANITE
"""
import os
import time
import json
import re
from typing import Optional

import httpx
from dotenv import load_dotenv
from pathlib import Path

from http_client import get_client

# Load from backend/.env regardless of cwd (finds file relative to this module)
load_dotenv(dotenv_path=Path(__file__).resolve().parents[1] / ".env")
# Also try cwd for Code Engine where .env may be at /app/.env
load_dotenv()

WATSONX_API_KEY = os.getenv("WATSONX_API_KEY", "")
WATSONX_PROJECT_ID = os.getenv("WATSONX_PROJECT_ID", "")
WATSONX_URL = os.getenv("WATSONX_URL", "https://us-south.ml.cloud.ibm.com").rstrip("/")
WATSONX_MODEL_ID = os.getenv("WATSONX_MODEL_ID", "ibm/granite-3-8b-instruct")
WATSONX_IAM_URL = os.getenv("WATSONX_IAM_URL", "https://iam.cloud.ibm.com/identity/token")
MOCK_GRANITE = os.getenv("MOCK_GRANITE", "false").lower() in ("1", "true", "yes")

# IAM token cache
_iam_token: Optional[str] = None
_iam_expiry: float = 0

# Heuristic keywords for mock mode — mirrors the tuned prompt logic
_MOCK_HIGH_KEYWORDS = ["otp", "pin", "blocked", "act now", "urgent", "kyc", "refund", "verify", "account will be blocked", "suspended", "share otp"]
_MOCK_PHISH_DOMAINS = ["arnaz0n", "amaz0n", "flipkart-kyc", "sbi-kyc", "paytm-kyc", "secure-kyc", "account-verify"]


def _is_mock_mode() -> bool:
    if MOCK_GRANITE:
        return True
    if not WATSONX_API_KEY or not WATSONX_PROJECT_ID:
        return True
    return False


def mode_info() -> dict:
    """
    Non-secret description of how Granite is being served, surfaced by /health and in
    every /analyze response's meta. The app shows this so nobody has to guess whether
    a verdict came from real Granite or the offline heuristic.
    """
    mock = _is_mock_mode()
    return {
        "granite": "mock" if mock else "live",
        "model": WATSONX_MODEL_ID,
        "region": WATSONX_URL,
        # Distinguishes "operator chose mock" from "keys are missing" — the second is
        # a misconfiguration worth seeing on a deployed instance.
        "reason": ("MOCK_GRANITE=true" if MOCK_GRANITE else "missing WATSONX_API_KEY/PROJECT_ID") if mock else None,
    }


def _extract_user_message(prompt: str) -> str:
    """Extract the actual user message from the wrapped prompt template."""
    # Look for Message to classify: "..."  (verbatim template marker)
    m = re.search(r'Message to classify:\s*"(.*?)"\s*$', prompt, re.DOTALL)
    if m:
        return m.group(1)
    # Fallback for URL prompt: URL to judge: "..."
    m2 = re.search(r'URL to judge:\s*"(.*?)"\s*$', prompt, re.DOTALL)
    if m2:
        return m2.group(1)
    # Last quoted block fallback
    m3 = re.findall(r'"([^"]*)"', prompt)
    if m3:
        return m3[-1]
    return prompt


def _mock_generate(prompt: str) -> str:
    """
    Deterministic mock that returns valid JSON shaped like Granite's expected output.
    IMPORTANT: Only evaluates the extracted user message, not the prompt template
    (template itself contains keywords like OTP/PIN which would otherwise false-trigger).
    Used when keys are missing or MOCK_GRANITE=true so backend remains testable.
    This is NOT the real AI — clearly flagged for judges via reason suffix.
    """
    lower_template = prompt.lower()
    is_url_prompt = "lookalike" in lower_template or "spoofed domain" in lower_template or "domain looks" in lower_template

    # Extract actual user content for heuristic evaluation
    user_content = _extract_user_message(prompt)
    lower = user_content.lower()

    if is_url_prompt:
        # target is the URL itself
        target = user_content.lower().strip()
        # also try to find url inside
        urls = re.findall(r"https?://[^\s\"']+", prompt)
        # prefer extracted user_content if it looks like url
        if not target.startswith("http") and urls:
            target = urls[0].lower()
        for bad in _MOCK_PHISH_DOMAINS:
            if bad in target:
                return json.dumps({"risk": "high", "reason": f"mock: domain resembles spoofed pattern '{bad}' (mock mode)"})
        # generic short domain check
        if any(x in target for x in [".in/", "-kyc", "-verify", "secure-", "login-"]):
            return json.dumps({"risk": "medium", "reason": "mock: URL contains suspicious KYC/verify pattern (mock mode)"})
        return json.dumps({"risk": "low", "reason": "mock: domain looks legitimate (mock mode)"})

    # Normal scam text classification — evaluate ONLY user_content
    if any(kw in lower for kw in ["otp", "share otp", "pin"]):
        return json.dumps({"risk": "high", "category": "OTP scam", "reason": "mock: requests OTP/PIN sharing (mock mode)"})
    if any(kw in lower for kw in ["kyc", "refund", "account will be blocked", "blocked", "suspended"]):
        # distinguish
        if "refund" in lower:
            return json.dumps({"risk": "high", "category": "fake refund/KYC", "reason": "mock: fake refund/KYC urgency detected (mock mode)"})
        return json.dumps({"risk": "high", "category": "fake refund/KYC", "reason": "mock: fake KYC/block urgency detected (mock mode)"})
    if "http" in lower or "www." in lower or "click" in lower or "link" in lower:
        # check phishing
        for bad in _MOCK_PHISH_DOMAINS:
            if bad in lower:
                return json.dumps({"risk": "high", "category": "phishing link", "reason": "mock: phishing link with spoofed domain (mock mode)"})
        return json.dumps({"risk": "medium", "category": "phishing link", "reason": "mock: contains link, verify sender (mock mode)"})
    if any(kw in lower for kw in ["bank", "government", "police", "officer", "delivery", "impersonat"]):
        # weak heuristic for impersonation
        if "urgent" in lower or "act now" in lower or "immediately" in lower:
            return json.dumps({"risk": "high", "category": "impersonation", "reason": "mock: impersonation with urgency (mock mode)"})
    if "act now" in lower or "urgent" in lower or "immediately" in lower:
        return json.dumps({"risk": "medium", "category": "impersonation", "reason": "mock: urgency language without clear impersonation (mock mode)"})

    return json.dumps({"risk": "low", "category": "none", "reason": "mock: no scam patterns detected (mock mode)"})


async def _get_iam_token(client: httpx.AsyncClient) -> str:
    global _iam_token, _iam_expiry
    now = time.time()
    if _iam_token and now < (_iam_expiry - 60):
        return _iam_token

    # IBM IAM expects form-encoded body
    data = {
        "grant_type": "urn:ibm:params:oauth:grant-type:apikey",
        "apikey": WATSONX_API_KEY,
    }
    headers = {"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"}
    resp = await client.post(WATSONX_IAM_URL, data=data, headers=headers, timeout=15.0)
    resp.raise_for_status()
    body = resp.json()
    _iam_token = body.get("access_token") or body.get("accessToken") or ""
    expires_in = int(body.get("expires_in", 3600))
    _iam_expiry = now + expires_in
    if not _iam_token:
        raise RuntimeError(f"IAM token response missing access_token: {body}")
    return _iam_token


def _build_generation_payload(prompt: str) -> dict:
    return {
        "model_id": WATSONX_MODEL_ID,
        "input": prompt,
        "project_id": WATSONX_PROJECT_ID,
        "parameters": {
            "decoding_method": "greedy",
            "max_new_tokens": 300,
            "min_new_tokens": 0,
            "temperature": 0,
            "repetition_penalty": 1.0,
        },
    }


def _build_chat_payload(prompt: str) -> dict:
    """
    Matches your Prompt Lab curl exactly:
      POST https://us-south.ml.cloud.ibm.com/ml/v1/text/chat?version=2023-05-29
      { "messages": [{"role":"user","content":prompt}], "project_id": "...", "model_id":"ibm/granite-4-h-small",
        "frequency_penalty":0, "presence_penalty":0, "temperature":0, "max_tokens":300, "top_p":1, "seed":null, "stop":[] }
    We send both the modern `parameters` nesting and the flat top-level keys for compatibility across watsonx versions.
    """
    base_messages = [{"role": "user", "content": prompt}]
    payload = {
        "model_id": WATSONX_MODEL_ID,
        "project_id": WATSONX_PROJECT_ID,
        "messages": base_messages,
        # flat keys as in your curl
        "frequency_penalty": 0,
        "presence_penalty": 0,
        "temperature": 0,
        "max_tokens": 400,
        "top_p": 1,
        "stop": [],
        # also `parameters` for older endpoint shape
        "parameters": {
            "temperature": 0,
            "max_tokens": 400,
            "top_p": 1,
            "frequency_penalty": 0,
            "presence_penalty": 0,
        },
    }
    return payload


def _extract_text_from_generation_response(body: dict) -> str:
    # /ml/v1/text/generation shape: {"results": [{"generated_text": "..."}]}
    if "results" in body and isinstance(body["results"], list) and body["results"]:
        return body["results"][0].get("generated_text", "") or ""
    # chat shape fallback
    if "choices" in body and body["choices"]:
        choice = body["choices"][0]
        # chat: choices[0].message.content
        if isinstance(choice, dict):
            msg = choice.get("message", {})
            if isinstance(msg, dict) and "content" in msg:
                return msg["content"]
            return choice.get("text", "") or choice.get("generated_text", "") or ""
    # direct fields
    for k in ("generated_text", "text", "content", "response", "output"):
        if k in body and isinstance(body[k], str):
            return body[k]
    return json.dumps(body)  # last resort, let caller try to parse


async def generate(prompt: str) -> str:
    """
    Calls watsonx.ai Granite with the given prompt and returns raw generated text.
    Pinned to YOUR endpoint from Prompt Lab: POST /ml/v1/text/chat?version=2023-05-29
    with ibm/granite-4-h-small. We try CHAT first (correct for granite-4), then fallback to generation.
    Implements retry-once on transient errors. In mock mode, returns heuristic JSON.
    """
    if _is_mock_mode():
        return _mock_generate(prompt)

    last_exc: Optional[Exception] = None
    for attempt in range(2):
        try:
            # Shared pooled client — see http_client.py. Do NOT switch back to a
            # per-call `async with httpx.AsyncClient()`: that pays a fresh TLS
            # handshake to us-south on every bubble tap.
            client = get_client()
            token = await _get_iam_token(client)
            headers = {
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            }
            # Try CHAT first — this is what your Prompt Lab curl uses for granite-4-h-small
            url_chat = f"{WATSONX_URL}/ml/v1/text/chat?version=2023-05-29"
            payload_chat = _build_chat_payload(prompt)
            resp = await client.post(url_chat, headers=headers, json=payload_chat, timeout=40.0)

            # Fallback to generation if chat endpoint not found/unsupported (covers older granites)
            if resp.status_code in (404, 405, 422):
                print(f"[watsonx] chat endpoint {resp.status_code}, trying generation fallback")
                url_gen = f"{WATSONX_URL}/ml/v1/text/generation?version=2023-05-29"
                payload = _build_generation_payload(prompt)
                resp = await client.post(url_gen, headers=headers, json=payload, timeout=40.0)

            # Transient retry trigger — handle 429 with longer wait (free plan limit = 10 concurrent)
            if resp.status_code in (429, 500, 502, 503, 504):
                # Try to respect Retry-After
                retry_after = resp.headers.get("Retry-After")
                if retry_after and retry_after.isdigit():
                    print(f"[watsonx] transient {resp.status_code}, Retry-After {retry_after}s")
                else:
                    print(f"[watsonx] transient {resp.status_code}, will retry after backoff")
                raise httpx.HTTPStatusError(f"transient {resp.status_code}", request=resp.request, response=resp)

            if resp.status_code >= 400:
                body_text = resp.text[:800]
                print(f"[watsonx] error {resp.status_code}: {body_text}")
                resp.raise_for_status()

            body = resp.json()
            text = _extract_text_from_generation_response(body)
            if not text:
                text = json.dumps(body)
            return text.strip()

        except (httpx.TimeoutException, httpx.ConnectError, httpx.HTTPStatusError) as e:
            last_exc = e
            if attempt == 0:
                # Longer backoff for 429 (free plan)
                is_429 = "429" in str(e) or (hasattr(e, 'response') and getattr(e.response, 'status_code', None) == 429)
                wait = 3.5 if is_429 else 0.8
                # try to use Retry-After if available
                try:
                    ra = e.response.headers.get("Retry-After") if hasattr(e, 'response') and e.response else None
                    if ra and ra.isdigit():
                        wait = float(ra) + 0.5
                except: pass
                print(f"[watsonx] retrying after {wait}s due to {e}")
                await _sleep(wait)
                continue
            raise
        except Exception as e:
            last_exc = e
            if attempt == 0 and "transient" in str(e).lower():
                await _sleep(0.8)
                continue
            raise

    if last_exc:
        raise last_exc
    return ""


async def _sleep(seconds: float):
    import asyncio
    await asyncio.sleep(seconds)
