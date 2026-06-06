from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from sqlmodel import Session

from ..database import engine
from ..models import User
from ..security import decode_token
from ..ws import manager

router = APIRouter()


@router.websocket("/ws")
async def dashboard_ws(websocket: WebSocket, token: str = Query(...)):
    """Dashboard real-time channel. Authenticate with ?token=<jwt>."""
    subject = decode_token(token)
    if subject is None:
        await websocket.close(code=4401)
        return
    try:
        user_id = int(subject)
    except ValueError:
        await websocket.close(code=4401)
        return

    with Session(engine) as session:
        user = session.get(User, user_id)
        if user is None or not user.is_active:
            await websocket.close(code=4401)
            return

    await manager.connect(websocket, user)
    try:
        # Keep the socket alive; we don't expect inbound messages, but draining
        # them lets us detect disconnects promptly.
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        await manager.disconnect(websocket, user_id)
    except Exception:
        await manager.disconnect(websocket, user_id)
