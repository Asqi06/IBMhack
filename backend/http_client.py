"""
http_client.py — one shared httpx.AsyncClient for all outbound calls.

Every agent used to open `async with httpx.AsyncClient()` per request, which throws
away the connection afterwards. On the watsonx path that meant a fresh DNS lookup +
TCP handshake + TLS handshake on every single scan — several hundred ms of pure
overhead per tap of the bubble, on top of Granite's own latency. Keeping one pooled
client alive turns the second and later scans into a warm-connection request.

Created lazily so importing this module never needs a running event loop, and closed
from main.py's lifespan on shutdown.
"""
from typing import Optional

import httpx

_client: Optional[httpx.AsyncClient] = None

# Generous ceilings: watsonx generation can legitimately take ~10s on a cold model,
# and the per-call timeout is passed explicitly at each call site anyway.
_LIMITS = httpx.Limits(max_connections=20, max_keepalive_connections=10, keepalive_expiry=60.0)
_DEFAULT_TIMEOUT = httpx.Timeout(connect=10.0, read=45.0, write=15.0, pool=10.0)


def get_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(limits=_LIMITS, timeout=_DEFAULT_TIMEOUT, http2=False)
    return _client


async def aclose() -> None:
    global _client
    if _client is not None and not _client.is_closed:
        try:
            await _client.aclose()
        finally:
            _client = None
