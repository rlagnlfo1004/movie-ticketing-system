#!/usr/bin/env python3
"""Create local-only secrets, preserving any existing .env entries."""
import base64
import json
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[2]
ENV = ROOT / ".env"


def parse(lines):
    result = {}
    for line in lines:
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def mysql_container_env():
    try:
        ids = subprocess.run([
            "docker", "ps", "-a", "--filter", "label=com.docker.compose.project=movie-ticketing-system",
            "--filter", "label=com.docker.compose.service=mysql", "--format", "{{.ID}}",
        ], capture_output=True, text=True, check=True).stdout.splitlines()
        if not ids:
            return {}
        raw = subprocess.run(["docker", "inspect", "--format", "{{json .Config.Env}}", ids[0]],
                             capture_output=True, text=True, check=True).stdout
        return dict(item.split("=", 1) for item in json.loads(raw) if "=" in item)
    except (FileNotFoundError, subprocess.CalledProcessError, ValueError):
        return {}


def main():
    lines = ENV.read_text().splitlines() if ENV.exists() else ["# Local movie-ticketing values; ignored by git."]
    existing = parse(lines)
    mysql = mysql_container_env()
    webhook = existing.get("MOCK_PG_WEBHOOK_SECRET") or "whsec_" + base64.b64encode(secrets.token_bytes(32)).decode()
    api_secret = existing.get("MOCK_PG_API_SECRET") or secrets.token_urlsafe(32)
    values = {
        "SEAT_DB_NAME": "movie_seats",
        "SEAT_DB_USER": mysql.get("MYSQL_USER", "movie"),
        "SEAT_DB_PASSWORD": mysql.get("MYSQL_PASSWORD") or secrets.token_urlsafe(24),
        "SEAT_DB_ROOT_PASSWORD": mysql.get("MYSQL_ROOT_PASSWORD") or secrets.token_urlsafe(24),
        "MOCK_PG_API_SECRET": api_secret,
        "MOCK_PG_WEBHOOK_SECRET": webhook,
        "RESERVATION_MOCK_PG_API_SECRET": api_secret,
        "RESERVATION_WEBHOOK_SECRET": webhook,
        "RESERVATION_RECONCILIATION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
        "GRAFANA_ADMIN_PASSWORD": secrets.token_urlsafe(24),
        "MOCK_PG_STORE_ID": "store-mock",
    }
    if existing.get("RESERVATION_MOCK_PG_API_SECRET") and existing["RESERVATION_MOCK_PG_API_SECRET"] != api_secret:
        raise SystemExit("Mock PG API secrets differ; fix .env")
    if existing.get("RESERVATION_WEBHOOK_SECRET") and existing["RESERVATION_WEBHOOK_SECRET"] != webhook:
        raise SystemExit("Mock PG webhook secrets differ; fix .env")
    for key, value in values.items():
        if existing.get(key):
            continue
        index = next((i for i, line in enumerate(lines) if line.startswith(key + "=")), None)
        if index is None:
            lines.append(key + "=" + value)
        else:
            lines[index] = key + "=" + value
    ENV.write_text("\n".join(lines) + "\n")
    ENV.chmod(0o600)
    local = ROOT / ".local"
    local.mkdir(exist_ok=True)
    exporter = local / "mysql-exporter.cnf"
    final = parse(ENV.read_text().splitlines())
    exporter.write_text("[client]\nuser=root\npassword=" + final["SEAT_DB_ROOT_PASSWORD"]
                        + "\nhost=mysql\nport=3306\n")
    exporter.chmod(0o600)
    print("Local env and exporter config are ready")


if __name__ == "__main__":
    main()
