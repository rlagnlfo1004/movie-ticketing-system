#!/usr/bin/env python3
"""Add the stage's non-secret configuration to a captured run README."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def main(stage, run, profile=None):
    profile = profile or stage.split("-", 1)[0]
    directory = ROOT / "load-test/results" / stage / run
    readme = directory / "README.md"
    if not readme.exists():
        raise SystemExit(f"Capture README not found: {readme}")
    stage_file = ROOT / "infra/stages" / f"{profile}.env"
    if not stage_file.exists():
        raise SystemExit(f"Stage env not found: {stage_file}")
    settings = dict(line.split("=", 1) for line in stage_file.read_text().splitlines()
                    if line and not line.startswith("#") and "=" in line)
    existing = readme.read_text().split("\n## 단계별로 달라진 설정", 1)[0].rstrip()
    rows = [f"| {key} | `{settings[key]}` |" for key in (
        "STAGE_CODE_TAG", "REDIS_CONFIG", "REDIS_APPENDONLY", "REDIS_APPENDFSYNC",
        "HOLD_DURATION", "PAYMENT_MODE", "ACTIVE_CHECK_MODE", "SPRING_THREADS_VIRTUAL_ENABLED")]
    readme.write_text(existing + "\n\n## 단계별로 달라진 설정\n\n"
                      + f"원본: [infra/stages/{profile}.env](../../../../infra/stages/{profile}.env)\n\n"
                      + "| 설정 | 값 |\n| --- | --- |\n" + "\n".join(rows) + "\n")
    print(readme)


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        raise SystemExit("usage: report.py <stage> <run> [profile]")
    main(*sys.argv[1:])
