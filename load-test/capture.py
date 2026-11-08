#!/usr/bin/env python3
"""Snapshot local state and export every provisioned panel before teardown."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse
import urllib.request
from local_env import get as local_env
from remote_compose import route

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "load-test/results"
DASHBOARD = json.loads((ROOT / "observability/grafana/provisioning/dashboards/json/seat.json").read_text())
PROMETHEUS = os.environ.get("PROMETHEUS_URL", "http://localhost:9090")
GRAFANA = os.environ.get("GRAFANA_URL", "http://localhost:3000")
GRAFANA_USER = os.environ.get("GRAFANA_USER", "admin")
GRAFANA_PASSWORD = os.environ.get("GRAFANA_PASSWORD") or local_env("GRAFANA_ADMIN_PASSWORD")
MYSQL_ROOT_PASSWORD = os.environ.get("SEAT_DB_ROOT_PASSWORD") or local_env("SEAT_DB_ROOT_PASSWORD")
STREAM_KEY = os.environ.get("STREAM_KEY", "stream:{1}:reservations")


def run(*args):
    result = subprocess.run(route(args), cwd=ROOT, capture_output=True, text=True, check=False)
    return result.stdout + result.stderr


def snapshot(directory, phase):
    directory.mkdir(parents=True, exist_ok=True)
    mysql = ("docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={MYSQL_ROOT_PASSWORD}", "mysql", "mysql", "-uroot", "-N", "-B")
    (directory / f"innodb-status-{phase}.txt").write_text(run(*mysql, "-e", "SHOW ENGINE INNODB STATUS\\G"))
    (directory / f"innodb-metrics-{phase}.tsv").write_text(run(*mysql, "-e", "SELECT name,count FROM information_schema.innodb_metrics WHERE name LIKE 'lock_%' ORDER BY name"))
    (directory / f"mysql-global-status-{phase}.tsv").write_text(run(*mysql, "-e", "SHOW GLOBAL STATUS"))
    (directory / f"redis-info-{phase}.txt").write_text(run("docker", "compose", "exec", "-T", "redis", "redis-cli", "INFO", "all"))
    (directory / f"redis-stream-groups-{phase}.txt").write_text(run("docker", "compose", "exec", "-T", "redis", "redis-cli", "XINFO", "GROUPS", STREAM_KEY))


def counters(path):
    result = {}
    for line in path.read_text().splitlines():
        fields = line.split("\t")
        if len(fields) == 2:
            try:
                result[fields[0]] = float(fields[1])
            except ValueError:
                pass
    return result


def difference(directory, name):
    before = counters(directory / f"{name}-before.tsv")
    after = counters(directory / f"{name}-after.tsv")
    delta = {key: after[key] - value for key, value in before.items() if key in after}
    (directory / f"{name}-delta.json").write_text(json.dumps(delta, indent=2, sort_keys=True) + "\n")


def get(url, auth=False):
    headers = {}
    if auth:
        credentials = base64.b64encode(f"{GRAFANA_USER}:{GRAFANA_PASSWORD}".encode()).decode()
        headers["Authorization"] = f"Basic {credentials}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as response:
        return response.read()


def capture(directory, start, end):
    snapshot(directory, "after")
    for name in ("innodb-metrics", "mysql-global-status"):
        difference(directory, name)
    panels = directory / "panels"
    metrics = directory / "prometheus"
    panels.mkdir(exist_ok=True)
    metrics.mkdir(exist_ok=True)
    render_args = urllib.parse.urlencode({"from": str(int(start * 1000)), "to": str(int(end * 1000)), "width": 1600, "height": 900})
    full_height = 3000
    dashboard_args = urllib.parse.urlencode({"from": str(int(start * 1000)), "to": str(int(end * 1000)), "width": 1600, "height": full_height})
    dashboard_url = f"{GRAFANA}/render/d/seat-redesign/seat-redesign?{dashboard_args}"
    (directory / "dashboard.png").write_bytes(get(dashboard_url, auth=True))
    empty = []
    for panel in DASHBOARD["panels"]:
        panel_id = panel["id"]
        panel_url = f"{GRAFANA}/render/d-solo/seat-redesign/seat-redesign?panelId={panel_id}&{render_args}"
        (panels / f"{panel_id:02d}.png").write_bytes(get(panel_url, auth=True))
        expression = panel["targets"][0]["expr"]
        query = urllib.parse.urlencode({"query": expression, "start": start, "end": end, "step": 5})
        raw = get(f"{PROMETHEUS}/api/v1/query_range?{query}")
        result = json.loads(raw)
        (metrics / f"{panel_id:02d}.json").write_text(json.dumps(result, indent=2) + "\n")
        if not result.get("data", {}).get("result"):
            empty.append(panel["title"])
    (directory / "README.md").write_text(
        f"# Measurement capture\n\nWindow: {start} to {end} (Unix seconds).\n\n"
        "`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, "
        "counter deltas and `summary.json` are the raw evidence.\n\n"
        "No series in this run: " + (", ".join(empty) if empty else "none") + ".\n"
    )
    print(directory)


if __name__ == "__main__":
    if len(sys.argv) < 5 or sys.argv[1] not in ("snapshot", "capture"):
        raise SystemExit("usage: capture.py snapshot <stage> <run> before | capture <stage> <run> <from> <to>")
    mode, stage, run_id = sys.argv[1:4]
    directory = RESULTS / stage / run_id
    if mode == "snapshot":
        snapshot(directory, sys.argv[4])
    else:
        if len(sys.argv) != 6:
            raise SystemExit("capture requires from and to Unix seconds")
        capture(directory, float(sys.argv[4]), float(sys.argv[5]))
