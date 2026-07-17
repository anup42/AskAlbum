from __future__ import annotations

import hashlib
import time

from redis import Redis
from redis.exceptions import RedisError

from .config import settings


class RateLimiter:
    """A Redis-backed fixed-window limit shared by every API process."""

    def __init__(self) -> None:
        self._redis: Redis | None = None

    def _client(self) -> Redis:
        if self._redis is None:
            self._redis = Redis.from_url(
                settings.redis_url,
                decode_responses=True,
                socket_connect_timeout=0.5,
                socket_timeout=0.5,
            )
        return self._redis

    def allowed(self, identity: str, bucket: str, limit: int) -> bool:
        if not settings.rate_limit_enabled:
            return True
        minute = int(time.time() // 60)
        digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24]
        key = f"askphotos:rate:{bucket}:{minute}:{digest}"
        try:
            pipeline = self._client().pipeline()
            pipeline.incr(key)
            pipeline.expire(key, 90)
            count, _ = pipeline.execute()
            return int(count) <= limit
        except RedisError:
            # A queue outage must not make ordinary photo browsing unavailable.
            return True


rate_limiter = RateLimiter()
