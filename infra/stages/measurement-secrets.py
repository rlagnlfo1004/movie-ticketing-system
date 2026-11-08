#!/usr/bin/env python3
"""Create or discard the measurement-day Grafana password outside the repository."""
from pathlib import Path
import secrets
import sys

PATH = Path.home() / ".config/movie-ticketing/loadtest-grafana.env"


def main(action):
    if action == "create":
        PATH.parent.mkdir(parents=True, exist_ok=True)
        if not PATH.exists():
            PATH.write_text("GRAFANA_ADMIN_PASSWORD=" + secrets.token_urlsafe(32) + "\n")
        PATH.chmod(0o600)
    elif action == "delete":
        if PATH.exists():
            PATH.unlink()
    else:
        raise SystemExit("usage: measurement-secrets.py create|delete")
    print(PATH)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: measurement-secrets.py create|delete")
    main(sys.argv[1])
