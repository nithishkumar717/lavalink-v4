#!/usr/bin/env python3
"""Update Lavalink's YouTube refresh token from a saved console log and SFTP it."""

from __future__ import annotations

import argparse
import getpass
import os
import re
import sys
import tempfile
from pathlib import Path


TOKEN_RE = re.compile(
    r"Token retrieved successfully\. Store your refresh token as this can be reused\.\s*\((1//[^)\s]+)\)"
)
REFRESH_LINE_RE = re.compile(r"(?m)^(\s*)refreshToken\s*:\s*.*$")


def extract_token(log_text: str) -> str:
    matches = TOKEN_RE.findall(log_text)
    if not matches:
        raise ValueError("No refresh token found. Use the console log printed after Google authorization.")
    return matches[-1]


def replace_token(config_text: str, token: str) -> str:
    # The project config has a single active refreshToken property under
    # plugins.youtube.oauth. Keep the token out of script output and Git.
    matches = list(REFRESH_LINE_RE.finditer(config_text))
    if len(matches) != 1:
        raise ValueError(
            "Expected exactly one active refreshToken: line in application.yml. "
            "Add it under plugins.youtube.oauth first."
        )
    match = matches[0]
    indent = match.group(1)
    return config_text[: match.start()] + f'{indent}refreshToken: "{token}"' + config_text[match.end() :]


def atomic_write(path: Path, content: str) -> None:
    path = path.resolve()
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", newline="", dir=path.parent, delete=False
    ) as temp_file:
        temp_file.write(content)
        temp_path = Path(temp_file.name)
    os.replace(temp_path, path)


def upload_sftp(local_path: Path, host: str, port: int, username: str, password: str, remote_path: str) -> None:
    try:
        import paramiko
    except ImportError as exc:
        raise RuntimeError("Missing SFTP library. Install it with: py -m pip install paramiko") from exc

    transport = paramiko.Transport((host, port))
    try:
        transport.connect(username=username, password=password)
        sftp = paramiko.SFTPClient.from_transport(transport)
        if sftp is None:
            raise RuntimeError("Could not open an SFTP session.")
        try:
            sftp.put(str(local_path), remote_path)
        finally:
            sftp.close()
    finally:
        transport.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Extract a YouTube OAuth refresh token from a saved Lavalink log, update application.yml, and upload it."
    )
    parser.add_argument("log", type=Path, help="Path to a text file containing the HidencCloud console log")
    parser.add_argument("--config", type=Path, default=Path(__file__).resolve().parents[1] / "application.yml")
    parser.add_argument("--host", required=True, help="SFTP host shown in HidencCloud Settings")
    parser.add_argument("--port", type=int, default=2022, help="SFTP port shown in HidencCloud Settings")
    parser.add_argument("--username", help="SFTP username; prompted if omitted")
    parser.add_argument("--remote", default="application.yml", help="Destination path inside the SFTP server root")
    args = parser.parse_args()

    try:
        log_text = args.log.read_text(encoding="utf-8", errors="replace")
        config_text = args.config.read_text(encoding="utf-8")
        token = extract_token(log_text)
        updated_config = replace_token(config_text, token)
        atomic_write(args.config, updated_config)

        username = args.username or input("HidencCloud SFTP username: ").strip()
        password = getpass.getpass("HidencCloud SFTP password: ")
        upload_sftp(args.config, args.host, args.port, username, password, args.remote)
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        # Avoid printing exception details: SSH libraries can include sensitive
        # connection metadata in their exception messages.
        print(f"SFTP upload failed ({type(exc).__name__}). Check the SFTP host, username, password, and path.", file=sys.stderr)
        return 1

    print("Updated application.yml and uploaded it to HidencCloud. Restart Lavalink in the panel.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
