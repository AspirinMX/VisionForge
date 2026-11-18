#!/usr/bin/env python3
"""Local mock of the image/video generation upstreams used by VisionForge.

The server implements the subset of the OpenAI, Volcengine Ark and Alibaba
DashScope media contracts that the provider adapters actually call. It is a
development aid: outputs are deterministic generated PNGs and one generated
MP4, never paid model results.

Run with Python 3.10+:

    python scripts/mock_media_server.py

Options:
    --host / --port          listen address (default 127.0.0.1:8000)
    --public-base-url        URL prefix returned in image/video result URLs
    --poll-count             polls before a task succeeds (default 2)
    --slow-poll-count        polls for prompts containing [mock:slow] (default 4)
    --video-file             use an existing MP4 instead of generating one
    --cert / --key           serve HTTPS with the given certificate
    --api-key                require this bearer token (default: accept any)

Prompts can inject behaviour:
    [mock:fail]   task or request fails
    [mock:slow]   task needs more polls
    [mock:empty]  success response without a usable artifact
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import ssl
import struct
import subprocess
import tempfile
import threading
import time
import uuid
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

TASKS: dict[str, dict] = {}
MEDIA: dict[str, tuple[str, bytes]] = {}
PNG_CACHE: dict[tuple[int, int, int], bytes] = {}
LOCK = threading.RLock()

CONFIG = {
    "public_base_url": "http://127.0.0.1:8000",
    "api_key": None,
    "poll_count": 2,
    "slow_poll_count": 4,
}


class BadRequest(Exception):
    pass


def crc32(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


def prompt_seed(prompt: str) -> int:
    return zlib.crc32(prompt.encode("utf-8")) & 0xFFFFFF


def parse_size(value: object) -> tuple[int, int]:
    """Return a small, fast-to-render size while keeping the requested ratio."""
    if value is None:
        return 512, 512
    text = str(value).strip().lower()
    if text in {"1k", "2k", "4k", "auto"}:
        size = 768
        return size, size
    match = re.match(r"^(\d+)\s*[x*]\s*(\d+)$", text)
    if not match:
        return 512, 512
    width = max(64, min(int(match.group(1)), 768))
    height = max(64, min(int(match.group(2)), 768))
    return width, height


def png_bytes(width: int, height: int, seed: int) -> bytes:
    key = (width, height, seed)
    with LOCK:
        cached = PNG_CACHE.get(key)
        if cached is not None:
            return cached

    red = 40 + (seed % 170)
    green = 40 + ((seed >> 8) % 170)
    blue = 40 + ((seed >> 16) % 170)
    rows = bytearray()
    for y in range(height):
        rows.append(0)  # PNG filter: None
        for x in range(width):
            rows.append((red + x * 140 // width) % 256)
            rows.append((green + y * 110 // height) % 256)
            rows.append((blue + (x + y) * 80 // (width + height)) % 256)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", crc32(kind + data))
        )

    result = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(rows), 6))
        + chunk(b"IEND", b"")
    )
    with LOCK:
        PNG_CACHE[key] = result
    return result


def public_url(name: str) -> str:
    return f"{CONFIG['public_base_url']}/media/{name}"


def save_media(content_type: str, data: bytes, suffix: str) -> str:
    name = f"{uuid.uuid4().hex}{suffix}"
    with LOCK:
        MEDIA[name] = (content_type, data)
    return public_url(name)


def new_task(kind: str, prompt: str, model: str) -> str:
    task_id = f"{'cgt' if kind == 'ark-video' else 'task'}-{uuid.uuid4().hex[:12]}"
    with LOCK:
        TASKS[task_id] = {
            "id": task_id,
            "kind": kind,
            "prompt": prompt,
            "model": model,
            "polls": 0,
            "created": time.time(),
        }
    return task_id


def get_task(task_id: str) -> dict | None:
    with LOCK:
        return TASKS.get(task_id)


def task_status(task: dict) -> str:
    """Advance one poll and return a lowercase canonical status."""
    with LOCK:
        task["polls"] += 1
        polls = task["polls"]
    if "[mock:fail]" in task["prompt"]:
        return "failed"
    required = (
        CONFIG["slow_poll_count"]
        if "[mock:slow]" in task["prompt"]
        else CONFIG["poll_count"]
    )
    if polls < required:
        return "queued" if polls == 1 else "running"
    return "succeeded"


def json_body(raw: bytes) -> dict:
    try:
        value = json.loads(raw.decode("utf-8") or "{}")
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BadRequest(f"invalid JSON body: {exc}") from exc
    if not isinstance(value, dict):
        raise BadRequest("JSON body must be an object")
    return value


def multipart_prompt(raw: bytes) -> str:
    marker = raw.find(b'name="prompt"')
    if marker < 0:
        return "mock edit"
    head_end = raw.find(b"\r\n\r\n", marker)
    if head_end < 0:
        return "mock edit"
    start = head_end + 4
    end = raw.find(b"\r\n--", start)
    if end < 0:
        end = len(raw)
    return raw[start:end].decode("utf-8", "replace")


def ark_text(content: object) -> str:
    if not isinstance(content, list):
        return ""
    parts = []
    for item in content:
        if isinstance(item, dict) and item.get("type") == "text":
            parts.append(str(item.get("text", "")))
    return " ".join(parts)


def dashscope_text(value: object) -> str:
    if not isinstance(value, dict):
        return ""
    messages = value.get("messages")
    if not isinstance(messages, list):
        return ""
    parts = []
    for message in messages:
        if not isinstance(message, dict):
            continue
        content = message.get("content")
        if not isinstance(content, list):
            continue
        for item in content:
            if isinstance(item, dict) and "text" in item:
                parts.append(str(item["text"]))
    return " ".join(parts)


def prepare_video(path: str | None) -> bytes | None:
    if path:
        with open(path, "rb") as handle:
            return handle.read()
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("[mock] ffmpeg not found; video endpoints will return 503")
        return None
    output = os.path.join(tempfile.mkdtemp(prefix="visionforge-mock-"), "mock.mp4")
    command = [
        ffmpeg,
        "-y",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "testsrc=size=640x360:rate=24",
        "-t",
        "2",
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        output,
    ]
    try:
        subprocess.run(command, check=True)
        with open(output, "rb") as handle:
            return handle.read()
    except (OSError, subprocess.CalledProcessError) as exc:
        print(f"[mock] could not generate the test video: {exc}")
        return None


class Handler(BaseHTTPRequestHandler):
    server_version = "VisionForgeMock/1.0"
    protocol_version = "HTTP/1.1"

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_HEAD(self) -> None:
        self._dispatch("HEAD")

    def do_POST(self) -> None:
        self._dispatch("POST")

    # -- response helpers -------------------------------------------------

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header(
            "Access-Control-Allow-Headers",
            "Authorization, Content-Type, X-DashScope-Async",
        )
        self.send_header("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS")

    def _json(self, status: int, payload: object) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _bytes(self, status: int, content_type: str, data: bytes) -> None:
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _error(self, status: int, code: str, message: str) -> None:
        self._json(status, {"error": {"code": code, "message": message}})

    # -- request helpers --------------------------------------------------

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def _authorized(self) -> bool:
        expected = CONFIG["api_key"]
        if not expected:
            return True
        return self.headers.get("Authorization") == f"Bearer {expected}"

    def _require_async(self) -> None:
        if self.headers.get("X-DashScope-Async", "").lower() != "enable":
            raise BadRequest("X-DashScope-Async: enable is required")

    def _video_ready(self) -> bool:
        with LOCK:
            return "mock.mp4" in MEDIA

    # -- routing ----------------------------------------------------------

    def _dispatch(self, method: str) -> None:
        path = urlparse(self.path).path
        try:
            body = self._read_body() if method == "POST" else b""
            if path == "/health":
                with LOCK:
                    payload = {
                        "status": "ok",
                        "tasks": len(TASKS),
                        "videoReady": "mock.mp4" in MEDIA,
                    }
                return self._json(200, payload)
            if path.startswith("/media/"):
                return self._media(path[len("/media/"):])
            if not self._authorized():
                return self._error(401, "Unauthorized", "missing or invalid API key")
            if method == "POST":
                handled = self._post(path, body)
                if handled:
                    return
            if method in {"GET", "HEAD"}:
                handled = self._get(path)
                if handled:
                    return
            self._error(404, "NotFound", path)
        except BadRequest as exc:
            self._error(400, "InvalidParameter", str(exc))
        except BrokenPipeError:
            pass
        except Exception as exc:  # pragma: no cover - defensive server guard
            print(f"[mock] request failed: {method} {path}: {exc}")
            try:
                self._error(500, "MockError", str(exc))
            except OSError:
                pass

    def _post(self, path: str, body: bytes) -> bool:
        if path == "/v1/images/generations":
            self._openai_image(json_body(body))
            return True
        if path == "/v1/images/edits":
            self._openai_image({"prompt": multipart_prompt(body)})
            return True
        if path == "/api/v3/images/generations":
            self._ark_image(json_body(body))
            return True
        if path == "/api/v3/contents/generations/tasks":
            self._ark_video_task(json_body(body))
            return True
        if path == "/api/v1/services/aigc/multimodal-generation/generation":
            self._dashscope_image_message(json_body(body))
            return True
        if path == "/api/v1/services/aigc/text2image/image-synthesis":
            self._require_async()
            self._dashscope_task(json_body(body), "ds-t2i", "prompt")
            return True
        if path == "/api/v1/services/aigc/video-generation/video-synthesis":
            self._require_async()
            self._dashscope_task(json_body(body), "ds-video", "prompt")
            return True
        return False

    def _get(self, path: str) -> bool:
        match = re.fullmatch(r"/api/v3/contents/generations/tasks/([^/]+)", path)
        if match:
            self._ark_video_query(match.group(1))
            return True
        match = re.fullmatch(r"/api/v1/tasks/([^/]+)", path)
        if match:
            self._dashscope_task_query(match.group(1))
            return True
        return False

    # -- OpenAI Images ----------------------------------------------------

    def _openai_image(self, request: dict) -> None:
        prompt = str(request.get("prompt", ""))
        if "[mock:fail]" in prompt:
            return self._error(500, "server_error", "mock generation failure")
        if "[mock:empty]" in prompt:
            return self._json(200, {"created": int(time.time()), "data": []})
        width, height = parse_size(request.get("size"))
        image = png_bytes(width, height, prompt_seed(prompt))
        self._json(
            200,
            {
                "created": int(time.time()),
                "data": [{"b64_json": base64.b64encode(image).decode("ascii")}],
            },
        )

    # -- Volcengine Ark ---------------------------------------------------

    def _ark_image(self, request: dict) -> None:
        prompt = str(request.get("prompt", ""))
        if "[mock:fail]" in prompt:
            return self._error(400, "MockFailure", "mock image generation failure")
        width, height = parse_size(request.get("size"))
        image = png_bytes(width, height, prompt_seed(prompt))
        item: dict[str, object] = {
            "size": f"{width}x{height}",
            "output_format": "png",
        }
        if request.get("response_format") == "b64_json":
            item["b64_json"] = base64.b64encode(image).decode("ascii")
        else:
            item["url"] = save_media("image/png", image, ".png")
        data = [] if "[mock:empty]" in prompt else [item]
        self._json(
            200,
            {
                "model": request.get("model", "mock-seedream"),
                "created": int(time.time()),
                "data": data,
                "usage": {
                    "generated_images": len(data),
                    "output_tokens": 1024,
                    "total_tokens": 1024,
                },
            },
        )

    def _ark_video_task(self, request: dict) -> None:
        if not self._video_ready():
            return self._error(503, "MockVideoUnavailable", "ffmpeg is required")
        prompt = ark_text(request.get("content"))
        task_id = new_task("ark-video", prompt, str(request.get("model", "")))
        self._json(200, {"id": task_id, "model": request.get("model", "mock-seedance")})

    def _ark_video_query(self, task_id: str) -> None:
        task = get_task(task_id)
        if task is None or task["kind"] != "ark-video":
            return self._error(404, "NotFound", f"unknown task {task_id}")
        status = task_status(task)
        payload: dict[str, object] = {
            "id": task_id,
            "model": task["model"] or "mock-seedance",
            "status": status,
            "created_at": int(task["created"]),
            "updated_at": int(time.time()),
            "duration": 5,
            "framespersecond": 24,
            "ratio": "16:9",
            "resolution": "720p",
            "generate_audio": False,
            "content": None,
            "error": None,
        }
        if status == "succeeded":
            if "[mock:empty]" not in task["prompt"]:
                payload["content"] = {"video_url": public_url("mock.mp4")}
            payload["usage"] = {"completion_tokens": 1000, "total_tokens": 1000}
        elif status == "failed":
            payload["error"] = {"code": "MockFailure", "message": "mock video failure"}
        elif status == "cancelled":
            payload["error"] = {"code": "Cancelled", "message": "task cancelled"}
        self._json(200, payload)

    # -- Alibaba DashScope ------------------------------------------------

    def _dashscope_image_message(self, request: dict) -> None:
        prompt = dashscope_text(request.get("input"))
        request_id = f"mock-{uuid.uuid4().hex[:12]}"
        if "[mock:fail]" in prompt:
            return self._error(400, "MockFailure", "mock image generation failure")
        content = []
        if "[mock:empty]" not in prompt:
            parameters = request.get("parameters")
            if not isinstance(parameters, dict):
                parameters = {}
            width, height = parse_size(parameters.get("size"))
            image = png_bytes(width, height, prompt_seed(prompt))
            content.append(
                {"type": "image", "image": save_media("image/png", image, ".png")}
            )
        self._json(
            200,
            {
                "request_id": request_id,
                "output": {
                    "choices": [
                        {
                            "finish_reason": "stop",
                            "message": {"role": "assistant", "content": content},
                        }
                    ]
                },
                "usage": {"image_count": len(content)},
            },
        )

    def _dashscope_task(self, request: dict, kind: str, prompt_field: str) -> None:
        if kind == "ds-video" and not self._video_ready():
            return self._error(503, "MockVideoUnavailable", "ffmpeg is required")
        value = request.get("input", {})
        prompt = str(value.get(prompt_field, "")) if isinstance(value, dict) else ""
        if not prompt and isinstance(value, dict):
            prompt = dashscope_text(value)
        task_id = new_task(kind, prompt, str(request.get("model", "")))
        self._json(
            200,
            {
                "request_id": f"mock-{uuid.uuid4().hex[:12]}",
                "output": {"task_id": task_id, "task_status": "PENDING"},
            },
        )

    def _dashscope_task_query(self, task_id: str) -> None:
        task = get_task(task_id)
        if task is None or task["kind"] not in {"ds-t2i", "ds-video"}:
            return self._error(404, "InvalidTaskId", f"unknown task {task_id}")
        status = task_status(task).upper()
        output: dict[str, object] = {
            "task_id": task_id,
            "task_status": status,
            "submit_time": time.strftime(
                "%Y-%m-%d %H:%M:%S", time.localtime(task["created"])
            ),
        }
        usage: dict[str, object] = {}
        if status == "SUCCEEDED":
            empty = "[mock:empty]" in task["prompt"]
            if task["kind"] == "ds-t2i":
                results = []
                if not empty:
                    width, height = 768, 768
                    image = png_bytes(width, height, prompt_seed(task["prompt"]))
                    results.append({"url": save_media("image/png", image, ".png")})
                output["results"] = results
                output["task_metrics"] = {
                    "TOTAL": 1,
                    "SUCCEEDED": 0 if empty else 1,
                    "FAILED": 0,
                }
                usage = {"image_count": 0 if empty else 1}
            else:
                if not empty:
                    output["video_url"] = public_url("mock.mp4")
                usage = {
                    "video_count": 0 if empty else 1,
                    "duration": 5.0,
                    "input_video_duration": 0.0,
                    "output_video_duration": 5.0,
                    "SR": "720",
                    "size": "1280*720",
                }
        elif status == "FAILED":
            output["code"] = "MockFailure"
            output["message"] = "mock task failure"
        elif status == "CANCELED":
            output["code"] = "Cancelled"
            output["message"] = "task cancelled"
        self._json(
            200,
            {
                "request_id": f"mock-{uuid.uuid4().hex[:12]}",
                "output": output,
                "usage": usage,
            },
        )

    # -- media ------------------------------------------------------------

    def _media(self, name: str) -> None:
        with LOCK:
            item = MEDIA.get(name)
        if item is None:
            return self._error(404, "NotFound", f"unknown media {name}")
        content_type, data = item
        range_header = self.headers.get("Range")
        if not range_header:
            return self._bytes(200, content_type, data)
        match = re.match(r"bytes=(\d*)-(\d*)$", range_header.strip())
        if not match:
            return self._bytes(200, content_type, data)
        length = len(data)
        start_text, end_text = match.group(1), match.group(2)
        if start_text == "" and end_text == "":
            return self._bytes(200, content_type, data)
        if start_text == "":
            start = max(0, length - int(end_text))
            end = length - 1
        else:
            start = int(start_text)
            end = int(end_text) if end_text else length - 1
        if start >= length:
            self.send_response(416)
            self._cors()
            self.send_header("Content-Range", f"bytes */{length}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        end = min(end, length - 1)
        chunk = data[start:end + 1]
        self.send_response(206)
        self._cors()
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Range", f"bytes {start}-{end}/{length}")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(chunk)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(chunk)


def main() -> None:
    parser = argparse.ArgumentParser(description="VisionForge media mock server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--public-base-url", default=None)
    parser.add_argument("--poll-count", type=int, default=2)
    parser.add_argument("--slow-poll-count", type=int, default=4)
    parser.add_argument("--video-file", default=None)
    parser.add_argument("--cert", default=None)
    parser.add_argument("--key", default=None)
    parser.add_argument("--api-key", default=None)
    args = parser.parse_args()

    CONFIG["poll_count"] = max(1, args.poll_count)
    CONFIG["slow_poll_count"] = max(CONFIG["poll_count"], args.slow_poll_count)
    CONFIG["api_key"] = args.api_key

    video = prepare_video(args.video_file)
    if video:
        MEDIA["mock.mp4"] = ("video/mp4", video)

    scheme = "https" if args.cert and args.key else "http"
    host = "127.0.0.1" if args.host in {"0.0.0.0", "::"} else args.host
    CONFIG["public_base_url"] = (
        args.public_base_url or f"{scheme}://{host}:{args.port}"
    ).rstrip("/")

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    if args.cert and args.key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(args.cert, args.key)
        server.socket = context.wrap_socket(server.socket, server_side=True)

    print(f"[mock] listening on {scheme}://{args.host}:{args.port}")
    print(f"[mock] public base URL: {CONFIG['public_base_url']}")
    print(f"[mock] video ready: {bool(video)}")
    print("[mock] model base URLs:")
    print(f"[mock]   OPENAI_IMAGES            {CONFIG['public_base_url']}/v1")
    print(f"[mock]   ARK_IMAGES/ARK_VIDEO     {CONFIG['public_base_url']}")
    print(f"[mock]   DASHSCOPE_*              {CONFIG['public_base_url']}/api/v1")
    print("[mock] set MEDIA_ALLOW_HTTP=true in the worker when using http")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] stopping")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
