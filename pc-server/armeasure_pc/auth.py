"""Bearer-token auth with constant-time comparison."""
from __future__ import annotations

import hmac

from fastapi import HTTPException, Request


def check_bearer(header: str | None, token: str) -> bool:
    if not header:
        return False
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer":
        return False
    return hmac.compare_digest(value.strip().encode("utf-8"), token.encode("utf-8"))


async def require_auth(request: Request) -> None:
    if not check_bearer(request.headers.get("authorization"), request.app.state.token):
        raise HTTPException(status_code=401, detail="invalid or missing token",
                            headers={"WWW-Authenticate": "Bearer"})
