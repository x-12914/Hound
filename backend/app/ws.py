"""Real-time push to dashboard clients over WebSockets.

A connected dashboard authenticates with its JWT (passed as ?token=...).
- admins receive every event
- owners receive only events for alerts they own
"""

import asyncio
from collections import defaultdict

from fastapi import WebSocket

from .models import Role, User


class ConnectionManager:
    def __init__(self) -> None:
        # user_id -> set of sockets (a user may have several tabs open)
        self._conns: dict[int, set[WebSocket]] = defaultdict(set)
        self._roles: dict[int, Role] = {}
        self._lock = asyncio.Lock()

    async def connect(self, websocket: WebSocket, user: User) -> None:
        await websocket.accept()
        async with self._lock:
            self._conns[user.id].add(websocket)
            self._roles[user.id] = user.role

    async def disconnect(self, websocket: WebSocket, user_id: int) -> None:
        async with self._lock:
            self._conns[user_id].discard(websocket)
            if not self._conns[user_id]:
                self._conns.pop(user_id, None)
                self._roles.pop(user_id, None)

    async def broadcast(self, event: dict, owner_id: int) -> None:
        """Send an event to the alert's owner and to every admin."""
        async with self._lock:
            targets: list[WebSocket] = []
            for uid, sockets in self._conns.items():
                if uid == owner_id or self._roles.get(uid) == Role.admin:
                    targets.extend(sockets)

        dead: list[tuple[int, WebSocket]] = []
        for ws in targets:
            try:
                await ws.send_json(event)
            except Exception:
                # collect for cleanup; find its owner id
                for uid, sockets in self._conns.items():
                    if ws in sockets:
                        dead.append((uid, ws))
                        break
        for uid, ws in dead:
            await self.disconnect(ws, uid)


manager = ConnectionManager()
