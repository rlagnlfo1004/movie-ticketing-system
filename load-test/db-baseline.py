#!/usr/bin/env python3
"""Measure fsync and one hot MySQL row before application load."""
import argparse
import atexit
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import socket

import mysql.connector

ROOT = Path(__file__).resolve().parents[1]


def env_file(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if line and not line.startswith("#") and "=" in line)


def query(connection, sql):
    cursor = connection.cursor()
    cursor.execute(sql)
    values = cursor.fetchall()
    cursor.close()
    return values


def percentile(values, fraction):
    if not values:
        return None
    return sorted(values)[min(len(values) - 1, int((len(values) - 1) * fraction))]


def hot_row(config, workers, seconds):
    deadline = time.monotonic() + seconds

    def work(_):
        connection = mysql.connector.connect(**config, autocommit=True)
        durations = []
        try:
            cursor = connection.cursor()
            while time.monotonic() < deadline:
                start = time.monotonic_ns()
                cursor.execute("UPDATE measurement_hot_row SET n = n - 1 WHERE id = 1")
                durations.append((time.monotonic_ns() - start) / 1_000_000)
            cursor.close()
        finally:
            connection.close()
        return durations

    start = time.monotonic()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        samples = [sample for result in pool.map(work, range(workers)) for sample in result]
    elapsed = time.monotonic() - start
    return {"connections": workers, "elapsedSeconds": elapsed, "updates": len(samples),
            "tps": len(samples) / elapsed, "p50Ms": percentile(samples, .50),
            "p95Ms": percentile(samples, .95), "p99Ms": percentile(samples, .99)}


def fio_local(output, smoke):
    mysql_id = subprocess.check_output(["docker", "compose", "ps", "-q", "mysql"], cwd=ROOT, text=True).strip()
    if not mysql_id:
        raise RuntimeError("Local MySQL container is not running")
    command = ["docker", "run", "--rm", "--volumes-from", mysql_id, "alpine:3.20", "sh", "-ec",
               "apk add --no-cache fio >/dev/null; "
               "fio --name=fsync --filename=/var/lib/mysql/measurement-fio.tmp "
               "--rw=write --bs=16k --size=" + ("16m" if smoke else "256m") + " "
               "--fsync=1 --ioengine=sync --output-format=json; "
               "rm -f /var/lib/mysql/measurement-fio.tmp"]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=True)
    output.write_text(result.stdout)


def prepare_remote(hosts_file, key):
    from remote_stage import read_env, deploy, compose, tunnel, wait_healthy
    from run_stage import build_tag
    account = subprocess.check_output(["aws", "sts", "get-caller-identity", "--profile", "movie-loadtest",
                                       "--query", "Account", "--output", "text"], text=True).strip()
    if account != "976071095824":
        raise RuntimeError("AWS account mismatch; DB baseline stopped")
    if not hosts_file.is_file() and hosts_file == ROOT / "infra/aws/hosts.env":
        subprocess.run([str(ROOT / "infra/aws/render-hosts-env.sh")], cwd=ROOT, check=True)
    if not hosts_file.is_file() or not key.is_file():
        raise RuntimeError("hosts.env or SSH key is missing")
    subprocess.run(["python3", str(ROOT / "infra/stages/ensure-local-env.py")], cwd=ROOT, check=True)
    subprocess.run(["python3", str(ROOT / "infra/stages/measurement-secrets.py"), "create"], cwd=ROOT, check=True)
    build_tag("v1-db", 1)
    hosts = read_env(hosts_file)
    output = ROOT / ".local/aws/db-baseline"
    output.mkdir(parents=True, exist_ok=True)
    deploy(hosts_file, hosts, key, "v1", output)
    compose(hosts, key, "mysql", "up", "-d", "--no-deps", "mysql")
    wait_healthy(hosts, key, "mysql", "mysql")
    return hosts, tunnel(hosts, key, "mysql", ((13306, 3306),))


def fio_remote(output, smoke, hosts, key):
    from remote_stage import remote
    remote(hosts, key, "mysql", "sudo", "dnf", "install", "-y", "fio")
    command = ["sudo", "fio", "--name=fsync", "--filename=/mnt/movie-mysql/measurement-fio.tmp",
               "--rw=write", "--bs=16k", "--size=" + ("16m" if smoke else "256m"),
               "--fsync=1", "--ioengine=sync", "--output-format=json"]
    try:
        result = remote(hosts, key, "mysql", *command, capture=True)
        output.write_bytes(result.stdout)
    finally:
        remote(hosts, key, "mysql", "sudo", "rm", "-f", "/mnt/movie-mysql/measurement-fio.tmp", check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--smoke", action="store_true", help="Short local script check")
    parser.add_argument("--mysql-host", default="127.0.0.1", help="Reachable MySQL host or SSH tunnel")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--fio-json", help="Raw fio JSON from the MySQL data volume; skips local fio")
    parser.add_argument("--hosts-env", type=Path, help="Deploy the V1 DB host and run the AWS baseline")
    parser.add_argument("--ssh-key", type=Path, default=Path.home() / ".ssh/id_ed25519")
    args = parser.parse_args()
    tunnel_process = None
    hosts = None
    if args.hosts_env:
        hosts, tunnel_process = prepare_remote(args.hosts_env.resolve(), args.ssh_key.expanduser().resolve())
        atexit.register(tunnel_process.terminate)
        time.sleep(1)
        if tunnel_process.poll() is not None:
            raise RuntimeError("MySQL SSH port forwarding failed")
        args.mysql_host = "127.0.0.1"
        args.mysql_port = 13306
        for _ in range(30):
            try:
                with socket.create_connection((args.mysql_host, args.mysql_port), timeout=1):
                    break
            except OSError:
                time.sleep(1)
        else:
            raise RuntimeError("MySQL SSH tunnel did not open")
    env = env_file(ROOT / ".env")
    config = {"host": args.mysql_host, "port": args.mysql_port, "database": env["SEAT_DB_NAME"],
              "user": "root", "password": env["SEAT_DB_ROOT_PASSWORD"]}
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    directory = ROOT / "load-test/results/db-baseline" / stamp
    evidence = ROOT / "docs/evidence/db-baseline"
    directory.mkdir(parents=True, exist_ok=True)
    evidence.mkdir(parents=True, exist_ok=True)
    fio_path = directory / "fio.json"
    if args.fio_json:
        fio_path.write_bytes(Path(args.fio_json).read_bytes())
    elif hosts:
        fio_remote(fio_path, args.smoke, hosts, args.ssh_key.expanduser().resolve())
    else:
        fio_local(fio_path, args.smoke)
    fio = json.loads(fio_path.read_text())
    shutil.copy2(fio_path, evidence / f"{stamp}-fio.json")
    connection = mysql.connector.connect(**config, autocommit=True)
    cursor = connection.cursor()
    try:
        cursor.execute("CREATE TABLE IF NOT EXISTS measurement_hot_row (id INT PRIMARY KEY, n BIGINT NOT NULL) ENGINE=InnoDB")
        cursor.execute("INSERT INTO measurement_hot_row (id,n) VALUES (1,1000000000) ON DUPLICATE KEY UPDATE n=1000000000")
        before = query(connection, "SELECT name,count FROM information_schema.innodb_metrics WHERE name LIKE 'lock_row_lock_%'")
        settings = query(connection, "SELECT @@innodb_flush_log_at_trx_commit, @@sync_binlog")
        if settings[0] != (1, 1):
            raise RuntimeError(f"Durability settings differ from baseline: {settings[0]}")
        samples = [hot_row(config, workers, 1 if args.smoke else 30) for workers in (1, 8, 32, 128)]
        after = query(connection, "SELECT name,count FROM information_schema.innodb_metrics WHERE name LIKE 'lock_row_lock_%'")
        cursor.execute("DROP TABLE measurement_hot_row")
    finally:
        cursor.close()
        connection.close()
    result = {"capturedAt": datetime.now(timezone.utc).isoformat(), "smoke": args.smoke,
              "durability": {"innodb_flush_log_at_trx_commit": 1, "sync_binlog": 1},
              "fioSyncLatency": fio.get("jobs", [{}])[0].get("sync", {}),
              "hotRow": samples, "innodbBefore": dict(before), "innodbAfter": dict(after)}
    (directory / "summary.json").write_text(json.dumps(result, indent=2, default=str) + "\n")
    (evidence / f"{stamp}.json").write_text(json.dumps(result, indent=2, default=str) + "\n")
    rows = ["# DB 단독 기준 측정", "", f"- 실행: {result['capturedAt']}",
            f"- 성격: {'로컬 스크립트 검증(성능 비교 제외)' if args.smoke else '정식 측정'}",
            "- 원본: [fio](fio.json) · [요약](summary.json)",
            "- 내구성: `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`", "",
            "| 동시 접속 | UPDATE 건수 | TPS | p50(ms) | p99(ms) |",
            "| ---: | ---: | ---: | ---: | ---: |"]
    for item in samples:
        rows.append(f"| {item['connections']} | {item['updates']} | {item['tps']:.2f} | "
                    f"{item['p50Ms']:.2f} | {item['p99Ms']:.2f} |")
    sync = result["fioSyncLatency"].get("lat_ns", {}).get("percentile", {})
    if sync:
        rows += ["", f"fio fsync 지연: p50 {int(sync['50.000000']) / 1_000_000:.3f}ms, "
                 f"p99 {int(sync['99.000000']) / 1_000_000:.3f}ms."]
    before_lock = result["innodbBefore"].get("lock_row_lock_time_avg")
    after_lock = result["innodbAfter"].get("lock_row_lock_time_avg")
    rows += [f"InnoDB `lock_row_lock_time_avg`: 실행 전 {before_lock if before_lock is not None else '미계측'}, "
             f"실행 후 {after_lock if after_lock is not None else '미계측'}. 원본은 `summary.json`에 있다."]
    ceiling = max(item["tps"] for item in samples)
    estimate = 1000 / samples[0]["p50Ms"] if samples[0]["p50Ms"] else 0
    rows += ["", "건당 락 점유 시간 ≈ fsync 지연 + UPDATE 실행 시간이며 상한 ≈ 1 ÷ 점유 시간이다. "
             f"단일 접속 UPDATE p50을 점유 시간의 근사값으로 쓰면 {estimate:.2f} TPS, "
             f"관찰한 최대치는 {ceiling:.2f} TPS다. 이 근사는 CPU·스케줄링 영향을 분리하지 못한다.", ""]
    (directory / "README.md").write_text("\n".join(rows))
    print(directory / "README.md")
    if tunnel_process:
        tunnel_process.terminate()
        tunnel_process.wait(timeout=5)


if __name__ == "__main__":
    main()
