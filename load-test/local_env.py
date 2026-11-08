"""Read the local ignored env file without executing it as shell code."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def values():
    path = ROOT / ".env"
    if not path.exists():
        return {}
    result = {}
    for line in path.read_text().splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def get(key):
    value = values().get(key)
    if not value:
        raise RuntimeError(f"Missing {key}; run infra/stages/ensure-local-env.py")
    return value


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: local_env.py KEY")
    print(get(sys.argv[1]))
