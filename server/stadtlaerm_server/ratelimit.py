"""In-process token buckets (DESIGN.md §8 abuse limits). One process, so no shared store."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable


class TokenBuckets:
    """`capacity` requests per `period_s`, refilled continuously, one bucket per key."""

    def __init__(self, capacity: int, period_s: float, clock: Callable[[], float] = time.monotonic):
        self.capacity = float(capacity)
        self.rate = capacity / period_s
        self.clock = clock
        self._buckets: dict[str, tuple[float, float]] = {}
        self._lock = threading.Lock()

    def take(self, key: str) -> float:
        """Takes one token. Returns 0 if allowed, else the seconds until a token is free."""
        now = self.clock()
        with self._lock:
            tokens, last = self._buckets.get(key, (self.capacity, now))
            tokens = min(self.capacity, tokens + (now - last) * self.rate)
            if tokens >= 1:
                self._buckets[key] = (tokens - 1, now)
                return 0.0
            self._buckets[key] = (tokens, now)
            return (1 - tokens) / self.rate

    def prune(self) -> None:
        """Forgets full buckets so the dict does not grow without bound."""
        now = self.clock()
        with self._lock:
            for k, (tokens, last) in list(self._buckets.items()):
                if tokens + (now - last) * self.rate >= self.capacity:
                    del self._buckets[k]
