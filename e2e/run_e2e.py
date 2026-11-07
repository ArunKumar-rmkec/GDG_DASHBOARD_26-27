#!/usr/bin/env python3
"""
Standalone CLI E2E Test Runner for GDGoC Campus Dashboard Backend Core (GDG_DASHBOARD_26-27).

Features:
- Ensures `pytest` and `requests` are installed (PEP 668 compatible).
- Supports `E2E_BASE_URL` / `GDG_BASE_URL` (default `http://localhost:18080`), `--base-url`, and `--tier {1,2,3,4,all}`.
- Probes `GET {base_url}/actuator/health`. If already UP, reuses the running server.
- If unreachable and targeting localhost/127.0.0.1, safely auto-starts the Spring Boot server on the target port
  without running `mvn clean` (preserving `target/` for any concurrent processes), waits up to 90s for health UP,
  runs pytest, and cleanly terminates the managed process group in `finally`.
"""

import argparse
import os
import signal
import subprocess
import sys
import tempfile
import time
import urllib.parse
from pathlib import Path

E2E_DIR = Path(__file__).resolve().parent
REPO_ROOT = E2E_DIR.parent

TIER_FILES = {
    "1": [str(E2E_DIR / "test_tier1_features.py")],
    "2": [str(E2E_DIR / "test_tier2_boundaries.py")],
    "3": [str(E2E_DIR / "test_tier3_combinations.py")],
    "4": [str(E2E_DIR / "test_tier4_scenarios.py")],
    "all": [
        str(E2E_DIR / "test_tier1_features.py"),
        str(E2E_DIR / "test_tier2_boundaries.py"),
        str(E2E_DIR / "test_tier3_combinations.py"),
        str(E2E_DIR / "test_tier4_scenarios.py"),
    ],
}


def ensure_dependencies() -> None:
    """Ensure pytest and requests are installed in user site-packages."""
    try:
        import pytest  # noqa: F401
        import requests  # noqa: F401
    except ImportError:
        print("[run_e2e] Installing missing Python dependencies (pytest, requests)...", flush=True)
        subprocess.check_call(
            [
                sys.executable,
                "-m",
                "pip",
                "install",
                "--user",
                "--break-system-packages",
                "pytest",
                "requests",
            ]
        )


def is_server_healthy(base_url: str, timeout: float = 2.0) -> bool:
    """Return True if GET {base_url}/actuator/health responds with HTTP 200 and status UP."""
    import requests

    health_url = f"{base_url.rstrip('/')}/actuator/health"
    try:
        resp = requests.get(health_url, timeout=timeout)
        if resp.status_code == 200:
            data = resp.json() if resp.content else {}
            if data.get("status") == "UP" or resp.status_code == 200:
                return True
    except Exception:
        pass
    return False


def is_local_url(base_url: str) -> bool:
    parsed = urllib.parse.urlparse(base_url)
    host = (parsed.hostname or "").lower()
    return host in ("localhost", "127.0.0.1", "0.0.0.0", "::1")


def extract_port(base_url: str, default_port: int = 18080) -> int:
    parsed = urllib.parse.urlparse(base_url)
    return parsed.port or default_port


def start_managed_backend(base_url: str, startup_timeout_sec: int = 90):
    """
    Start Spring Boot backend on the target port without running `mvn clean`.
    Returns (process, log_file_path).
    """
    port = extract_port(base_url, default_port=18080)
    log_fd, log_path = tempfile.mkstemp(prefix=f"gdg_e2e_server_{port}_", suffix=".log")
    log_file = os.fdopen(log_fd, "w")

    cmd = [
        "mvn",
        "-q",
        "spring-boot:run",
        f"-Dspring-boot.run.arguments=--server.port={port} --spring.profiles.active=local",
    ]
    print(f"[run_e2e] Starting managed backend at {base_url} (log: {log_path})...", flush=True)
    proc = subprocess.Popen(
        cmd,
        cwd=str(REPO_ROOT),
        stdout=log_file,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )

    deadline = time.time() + startup_timeout_sec
    while time.time() < deadline:
        if is_server_healthy(base_url, timeout=2.0):
            print(f"[run_e2e] Backend at {base_url} is UP and healthy.", flush=True)
            return proc, log_file, log_path
        if proc.poll() is not None:
            log_file.flush()
            tail = _read_log_tail(log_path, max_lines=80)
            raise RuntimeError(
                f"[run_e2e] Managed Spring Boot process exited prematurely with code {proc.returncode}.\n"
                f"--- Startup Log Tail ({log_path}) ---\n{tail}"
            )
        time.sleep(1.0)

    # Timed out waiting for health UP
    terminate_process_group(proc)
    log_file.close()
    tail = _read_log_tail(log_path, max_lines=80)
    raise RuntimeError(
        f"[run_e2e] Timed out after {startup_timeout_sec}s waiting for {base_url}/actuator/health.\n"
        f"--- Startup Log Tail ({log_path}) ---\n{tail}"
    )


def _read_log_tail(log_path: str, max_lines: int = 80) -> str:
    try:
        with open(log_path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
            return "".join(lines[-max_lines:])
    except Exception as exc:
        return f"<unable to read log {log_path}: {exc}>"


def terminate_process_group(proc: subprocess.Popen) -> None:
    """Safely terminate a background process group started with start_new_session=True."""
    if proc is None or proc.poll() is not None:
        return
    try:
        pgid = os.getpgid(proc.pid)
        os.killpg(pgid, signal.SIGTERM)
        deadline = time.time() + 10.0
        while time.time() < deadline:
            if proc.poll() is not None:
                return
            time.sleep(0.25)
        os.killpg(pgid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except Exception as exc:
        print(f"[run_e2e] Warning while terminating process group: {exc}", file=sys.stderr)


def main() -> int:
    ensure_dependencies()

    parser = argparse.ArgumentParser(description="Run GDGoC Campus Dashboard E2E Test Suite")
    parser.add_argument(
        "--tier",
        choices=["1", "2", "3", "4", "all"],
        default="all",
        help="Select E2E test tier to execute (default: all)",
    )
    parser.add_argument(
        "--base-url",
        default=None,
        help="Target backend base URL (overrides E2E_BASE_URL / GDG_BASE_URL, default: http://localhost:18080)",
    )
    parser.add_argument(
        "--startup-timeout",
        type=int,
        default=90,
        help="Seconds to wait for auto-started Spring Boot server health check (default: 90)",
    )
    args, passthrough_args = parser.parse_known_args()

    default_url = (
        os.environ.get("E2E_BASE_URL")
        or os.environ.get("GDG_BASE_URL")
        or "http://localhost:18080"
    )
    base_url = (args.base_url or default_url).rstrip("/")

    selected_files = TIER_FILES[args.tier]
    collect_only = "--collect-only" in passthrough_args

    managed_proc = None
    managed_log_file = None

    try:
        if not collect_only:
            if is_server_healthy(base_url):
                print(
                    f"[run_e2e] Detected running backend at {base_url}; reusing existing instance.",
                    flush=True,
                )
            elif is_local_url(base_url):
                managed_proc, managed_log_file, _ = start_managed_backend(
                    base_url, startup_timeout_sec=args.startup_timeout
                )
            else:
                print(
                    f"[run_e2e] Error: Remote backend at {base_url} is not reachable.",
                    file=sys.stderr,
                )
                return 2

        env = os.environ.copy()
        env["E2E_BASE_URL"] = base_url
        env["GDG_BASE_URL"] = base_url

        pytest_cmd = [
            sys.executable,
            "-m",
            "pytest",
            "-v",
            "--tb=short",
            *selected_files,
            *passthrough_args,
        ]
        print(f"[run_e2e] Executing: {' '.join(pytest_cmd)}", flush=True)
        result = subprocess.run(pytest_cmd, cwd=str(REPO_ROOT), env=env)
        return result.returncode
    except Exception as exc:
        print(f"[run_e2e] Fatal error: {exc}", file=sys.stderr, flush=True)
        return 2
    finally:
        if managed_proc is not None:
            print("[run_e2e] Stopping managed Spring Boot backend...", flush=True)
            terminate_process_group(managed_proc)
        if managed_log_file is not None:
            try:
                managed_log_file.close()
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())
