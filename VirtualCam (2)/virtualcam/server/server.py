#!/usr/bin/env python3
"""
VirtualCam PC Server
Captures OBS window or full screen and streams JPEG frames
over WebSocket to Android client(s).
"""

import asyncio
import time
import struct
import logging
import argparse
import sys
from typing import Set

import cv2
import numpy as np
import websockets
from websockets.server import WebSocketServerProtocol

# Optional: window-specific capture on Windows/macOS
try:
    import mss
    MSS_AVAILABLE = True
except ImportError:
    MSS_AVAILABLE = False

try:
    import pygetwindow as gw
    PYGETWINDOW_AVAILABLE = True
except ImportError:
    PYGETWINDOW_AVAILABLE = False

# ─────────────────────────────────────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────────────────────────────────────

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8765
DEFAULT_FPS = 30
DEFAULT_JPEG_QUALITY = 75          # 0–100; higher = better quality, more bandwidth
DEFAULT_WIDTH = 1280
DEFAULT_HEIGHT = 720
STATS_INTERVAL_SEC = 5             # How often to print FPS / client stats
RECONNECT_GRACE_MS = 100           # ms to wait before dropping a slow client


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("virtualcam-server")


# ─────────────────────────────────────────────────────────────────────────────
# Connected client registry
# ─────────────────────────────────────────────────────────────────────────────

CLIENTS: Set[WebSocketServerProtocol] = set()


async def register(ws: WebSocketServerProtocol) -> None:
    CLIENTS.add(ws)
    log.info("Client connected  [%s]  total=%d", ws.remote_address, len(CLIENTS))


async def unregister(ws: WebSocketServerProtocol) -> None:
    CLIENTS.discard(ws)
    log.info("Client disconnected  [%s]  total=%d", ws.remote_address, len(CLIENTS))


async def handler(ws: WebSocketServerProtocol) -> None:
    await register(ws)
    try:
        async for _ in ws:          # keep connection alive; we push frames, not pull
            pass
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        await unregister(ws)


# ─────────────────────────────────────────────────────────────────────────────
# Screen capture helpers
# ─────────────────────────────────────────────────────────────────────────────

class ScreenCapture:
    """
    Thin abstraction over mss (preferred) or OpenCV VideoCapture(0) fallback.
    On Windows/macOS with pygetwindow, can target a specific window title.
    """

    def __init__(self, window_title: str | None, width: int, height: int):
        self.width = width
        self.height = height
        self._monitor = None
        self._sct = None
        self._cap = None            # OpenCV fallback

        if MSS_AVAILABLE:
            self._sct = mss.mss()
            if window_title and PYGETWINDOW_AVAILABLE:
                self._monitor = self._find_window_monitor(window_title)
            if self._monitor is None:
                # Full primary screen
                self._monitor = self._sct.monitors[1]
            log.info("Capture via mss  monitor=%s", self._monitor)
        else:
            log.warning("mss not available – falling back to OpenCV screen capture")
            self._cap = cv2.VideoCapture(0)   # webcam fallback for dev environments

    def _find_window_monitor(self, title: str) -> dict | None:
        if not PYGETWINDOW_AVAILABLE:
            return None
        wins = gw.getWindowsWithTitle(title)
        if not wins:
            log.warning("Window '%s' not found; falling back to full screen", title)
            return None
        w = wins[0]
        return {"top": w.top, "left": w.left, "width": w.width, "height": w.height}

    def grab(self) -> np.ndarray | None:
        if self._sct:
            raw = self._sct.grab(self._monitor)
            frame = np.array(raw)
            frame = cv2.cvtColor(frame, cv2.COLOR_BGRA2BGR)
        elif self._cap:
            ret, frame = self._cap.read()
            if not ret:
                return None
        else:
            return None

        # Resize to target resolution
        if frame.shape[1] != self.width or frame.shape[0] != self.height:
            frame = cv2.resize(frame, (self.width, self.height), interpolation=cv2.INTER_LINEAR)
        return frame

    def release(self):
        if self._sct:
            self._sct.close()
        if self._cap:
            self._cap.release()


# ─────────────────────────────────────────────────────────────────────────────
# Frame encoding
# ─────────────────────────────────────────────────────────────────────────────

def encode_frame(frame: np.ndarray, quality: int) -> bytes:
    """
    Encode a BGR numpy frame to JPEG bytes.
    Prepends an 8-byte header: [timestamp_ms (int64 big-endian)]
    so the Android client can compute round-trip latency.
    """
    encode_params = [cv2.IMWRITE_JPEG_QUALITY, quality]
    _, buf = cv2.imencode(".jpg", frame, encode_params)
    jpeg_bytes = buf.tobytes()

    timestamp_ms = int(time.time() * 1000)
    header = struct.pack(">q", timestamp_ms)   # 8 bytes, signed int64, big-endian
    return header + jpeg_bytes


# ─────────────────────────────────────────────────────────────────────────────
# Broadcast loop
# ─────────────────────────────────────────────────────────────────────────────

async def broadcast_loop(capture: ScreenCapture, fps: int, quality: int) -> None:
    interval = 1.0 / fps
    frame_count = 0
    dropped = 0
    last_stats = time.perf_counter()

    log.info("Broadcast loop started  fps=%d  quality=%d  res=%dx%d",
             fps, quality, capture.width, capture.height)

    while True:
        t_start = time.perf_counter()

        frame = capture.grab()
        if frame is None:
            await asyncio.sleep(interval)
            continue

        payload = encode_frame(frame, quality)

        if CLIENTS:
            tasks = [asyncio.create_task(_send_to(ws, payload)) for ws in list(CLIENTS)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            dropped += sum(1 for r in results if isinstance(r, Exception))

        frame_count += 1

        # ── Print stats every STATS_INTERVAL_SEC seconds ──
        now = time.perf_counter()
        if now - last_stats >= STATS_INTERVAL_SEC:
            elapsed = now - last_stats
            actual_fps = frame_count / elapsed
            log.info("FPS=%.1f  clients=%d  dropped_sends=%d  frame_bytes=%d KB",
                     actual_fps, len(CLIENTS), dropped, len(payload) // 1024)
            frame_count = 0
            dropped = 0
            last_stats = now

        # ── Maintain target frame rate ──
        elapsed = time.perf_counter() - t_start
        sleep_for = interval - elapsed
        if sleep_for > 0:
            await asyncio.sleep(sleep_for)


async def _send_to(ws: WebSocketServerProtocol, payload: bytes) -> None:
    """Send payload to one client; raises on failure so the caller can count drops."""
    try:
        await asyncio.wait_for(ws.send(payload), timeout=RECONNECT_GRACE_MS / 1000)
    except (websockets.exceptions.ConnectionClosed, asyncio.TimeoutError) as exc:
        raise exc


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="VirtualCam PC Server")
    p.add_argument("--host", default=DEFAULT_HOST)
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--fps", type=int, default=DEFAULT_FPS)
    p.add_argument("--quality", type=int, default=DEFAULT_JPEG_QUALITY,
                   help="JPEG quality 0–100 (default 75)")
    p.add_argument("--width", type=int, default=DEFAULT_WIDTH)
    p.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
    p.add_argument("--window", default=None,
                   help="Partial window title to capture (e.g. 'OBS'). "
                        "Omit for full-screen capture.")
    return p.parse_args()


async def main() -> None:
    args = parse_args()

    capture = ScreenCapture(
        window_title=args.window,
        width=args.width,
        height=args.height,
    )

    log.info("Starting WebSocket server on ws://%s:%d", args.host, args.port)

    async with websockets.serve(
        handler,
        args.host,
        args.port,
        max_size=10 * 1024 * 1024,   # 10 MB max message size
        ping_interval=20,
        ping_timeout=10,
    ):
        try:
            await broadcast_loop(capture, args.fps, args.quality)
        finally:
            capture.release()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("Server stopped by user.")
        sys.exit(0)
