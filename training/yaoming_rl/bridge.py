"""Local NDJSON subprocess transport. Never connects to the deployed game."""
from __future__ import annotations

from collections import deque
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
from typing import Any


class SimulatorError(RuntimeError):
    pass


def find_java(explicit: str | None = None) -> str:
    if explicit:
        return explicit
    task_java_home = os.environ.get("JAVA_HOME")
    if task_java_home:
        path = Path(task_java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not path.is_file():
            raise SimulatorError(f"JAVA_HOME does not contain a Java runtime: {path}")
        return str(path)
    found = shutil.which("java")
    if not found:
        raise SimulatorError("Java 21 not found. Install Java 21 and reopen the terminal, or set JAVA_HOME.")
    return found


class Simulator:
    def __init__(self, jar_dir: str | Path | None = None, java: str | None = None,
                 workers: int = 4, heap_mb: int = 1024, timeout: float = 120.0):
        if not 1 <= workers <= 64 or not 128 <= heap_mb <= 32768 or timeout <= 0:
            raise ValueError("workers must be 1..64, heap_mb 128..32768, and timeout positive")
        self.jar_dir = Path(jar_dir) if jar_dir else Path(__file__).resolve().parents[1] / "simulator"
        self.jar_dir = self.jar_dir.resolve()
        manifest_path = self.jar_dir / "manifest.json"
        if not manifest_path.is_file():
            raise SimulatorError("Missing simulator/manifest.json. Use the full release ZIP or run python build_simulator.py --test with JDK 21.")
        self.manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if self.manifest.get("format") != 1 or self.manifest.get("protocol") != 1:
            raise SimulatorError("Unsupported simulator manifest format")
        artifacts = self.manifest.get("artifacts", {})
        if not isinstance(artifacts, dict) or "simulator.jar" not in artifacts:
            raise SimulatorError("Invalid simulator manifest: simulator.jar must be verified")
        verified_jars = []
        for name, expected in artifacts.items():
            path = (self.jar_dir / name).resolve()
            if not path.is_relative_to(self.jar_dir) or not path.is_file() or path.suffix != ".jar":
                raise SimulatorError(f"Missing/invalid simulator artifact: {name}")
            with path.open("rb") as source:
                actual = hashlib.file_digest(source, "sha256").hexdigest()
            if actual != expected:
                raise SimulatorError(f"Simulator checksum mismatch: {name}. Rebuild or re-extract the complete package.")
            verified_jars.append(str(path))
        self.engine_hash = self.manifest["engine_hash"]
        self.timeout = timeout
        self._closed = False
        self._lock = threading.RLock()
        self._lines: queue.Queue[str | None] = queue.Queue()
        self._errors: deque[str] = deque(maxlen=30)
        # Never load an extra unverified JAR dropped into lib/ via a wildcard.
        classpath = os.pathsep.join(verified_jars)
        command = [find_java(java), f"-Xmx{heap_mb}m", "-Dfile.encoding=UTF-8", "-cp", classpath,
                   "com.mahjong.yaoming.YmTrainingMain", "--workers", str(workers)]
        kwargs = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, encoding="utf-8", bufsize=1,
                                        cwd=self.jar_dir, **kwargs)
        self._reader = threading.Thread(target=self._read_stdout, daemon=True)
        self._stderr_reader = threading.Thread(target=self._read_stderr, daemon=True)
        self._reader.start()
        self._stderr_reader.start()
        try:
            info = self.hello()
            if info.get("protocol") != 1 or info.get("actionSize") != 194:
                raise SimulatorError("Incompatible simulator protocol/action encoding")
        except BaseException:
            self.close()
            raise

    def _read_stdout(self) -> None:
        assert self.process.stdout is not None
        try:
            for line in self.process.stdout:
                self._lines.put(line)
        finally:
            self._lines.put(None)

    def _read_stderr(self) -> None:
        assert self.process.stderr is not None
        for line in self.process.stderr:
            self._errors.append(line.rstrip())

    def request(self, payload: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            if self._closed:
                raise SimulatorError("Simulator is closed")
            try:
                assert self.process.stdin is not None
                self.process.stdin.write(json.dumps(payload, ensure_ascii=True, allow_nan=False) + "\n")
                self.process.stdin.flush()
                line = self._lines.get(timeout=self.timeout)
            except (BrokenPipeError, OSError, queue.Empty) as exc:
                self.close()
                raise SimulatorError(f"Simulator transport failed: {exc}\n" + "\n".join(self._errors)) from exc
            if line is None:
                self._stderr_reader.join(timeout=0.2)
                raise SimulatorError("Simulator exited. Java 21 is required.\n" + "\n".join(self._errors))
            try:
                result = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SimulatorError(f"Simulator produced non-JSON output: {line[:300]}") from exc
            if not isinstance(result, dict) or not result.get("ok"):
                raise SimulatorError(str(result.get("error", result)) if isinstance(result, dict) else str(result))
            return result

    def hello(self) -> dict[str, Any]:
        info = self.request({"cmd": "hello"})
        info["engineHash"] = self.engine_hash
        return info

    def batch(self, requests: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if not requests:
            return []
        result = self.request({"cmd": "batch", "requests": requests})
        states = result["results"]
        if len(states) != len(requests):
            raise SimulatorError("Simulator batch result length mismatch")
        for state in states:
            if not state.get("ok"):
                raise SimulatorError(state.get("error", "Simulator batch item failed"))
        return states

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            try:
                if self.process.poll() is None and self.process.stdin:
                    self.process.stdin.write('{"cmd":"close"}\n')
                    self.process.stdin.flush()
                    self.process.wait(timeout=3)
            except (OSError, subprocess.TimeoutExpired):
                if self.process.poll() is None:
                    self.process.kill()
                    self.process.wait(timeout=3)
            finally:
                for name in ("stdin",):
                    stream = getattr(self.process, name)
                    if stream:
                        stream.close()
                self._reader.join(timeout=1)
                self._stderr_reader.join(timeout=1)
                for name in ("stdout", "stderr"):
                    stream = getattr(self.process, name)
                    if stream and not self.process.poll() is None:
                        stream.close()

    def __enter__(self) -> "Simulator":
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.close()
