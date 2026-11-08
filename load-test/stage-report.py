#!/usr/bin/env python3
"""Build the measurement method and evidence index without inventing values."""
from datetime import datetime, timezone
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "load-test/results"
HOSTS = [
    ("lg", "c7g.xlarge", 4), ("edge", "c7g.large", 2),
    ("app-waiting", "m7g.xlarge", 4), ("app-reservation", "m7g.xlarge", 4),
    ("redis", "r7g.large", 2), ("mysql", "r7g.xlarge", 4),
    ("mon", "t4g.medium", 2),
]
TAGS = ("v1-db", "v2-db-fixed", "v3-pubsub", "v4-streams",
        "v5-lua-active", "v6-aof-reconcile")
DASHBOARD = json.loads((ROOT / "observability/grafana/provisioning/dashboards/json/seat.json").read_text())


def load(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def metric(summary, name, field):
    value = summary.get("metrics", {}).get(name, {}).get(field)
    return f"{value:.2f}" if isinstance(value, (int, float)) else "—"


def run_summary(tag, suffix=""):
    manifest = load(RESULTS / tag / "batch.json")
    if not manifest:
        return None, None
    location = RESULTS / tag / suffix / f"{manifest['batch']}-b1"
    return load(location / "summary.json"), location


def panel_peak(directory, title):
    panel = next((item for item in DASHBOARD["panels"] if item["title"] == title), None)
    if panel is None or directory is None:
        return "—"
    raw = load(directory / "prometheus" / f"{panel['id']:02d}.json") or {}
    values = []
    for series in raw.get("data", {}).get("result", []):
        for _, value in series.get("values", []):
            try:
                values.append(float(value))
            except ValueError:
                pass
    return f"{max(values):.2f}" if values else "—"


def pinning(tag, state):
    manifest = load(RESULTS / tag / "batch.json")
    if not manifest:
        return "—"
    path = ROOT / "docs/evidence" / tag / f"{manifest['batch']}-vt-{state}-pinning.txt"
    return str(path.read_text().count("jdk.VirtualThreadPinned {")) if path.exists() else "—"


def comparison(tag, hypothesis):
    on, on_path = run_summary(tag)
    off, off_path = run_summary(tag, "vt-off")
    if not on or not off:
        return f"| {tag} | {hypothesis} | — | — | — | — | — | — | — |"
    def peak_tps(summary):
        rates = [value.get("rate") for key, value in summary.get("metrics", {}).items()
                 if key.startswith("seat_holds_created{scenario:rate_") and isinstance(value.get("rate"), (int, float))]
        return f"{max(rates):.2f}" if rates else metric(summary, "seat_holds_created", "rate")
    def pair(title):
        return f"{panel_peak(on_path, title)} / {panel_peak(off_path, title)}"
    return (f"| [{tag}]({tag}/{on_path.name}/README.md) | {hypothesis} | {peak_tps(on)} | "
            f"{peak_tps(off)} | {metric(on, 'seat_hold_duration', 'p(95)')} / "
            f"{metric(off, 'seat_hold_duration', 'p(95)')} · "
            f"{metric(on, 'seat_hold_duration', 'p(99)')} / {metric(off, 'seat_hold_duration', 'p(99)')} | "
            f"{pair('Tomcat busy threads')} · {pair('JVM live threads')} | "
            f"{pair('Hikari active')} · {pair('Hikari pending')} | "
            f"{pinning(tag, 'on')} / {pinning(tag, 'off')} | {pair('Reservation app CPU')} |")


def stage_section(tag):
    directory = RESULTS / tag
    manifest = load(directory / "batch.json")
    if not manifest:
        return []
    profile = manifest["profile"]
    stage_env = ROOT / "infra/stages" / f"{profile}.env"
    settings = dict(line.split("=", 1) for line in stage_env.read_text().splitlines()
                    if line and "=" in line and not line.startswith("#"))
    rows = [f"### {tag}", "", f"- 실행 시작(UTC): {manifest['startedAt']}",
            f"- 실행 명령: `./load-test/run-stage.sh {profile}{' --smoke' if manifest['smoke'] else ''}`",
            f"- 성격: {'로컬 연결 검증. 성능 비교에서 제외.' if manifest['smoke'] else '정식 측정'}",
            "- 코드: `" + manifest["tag"] + "`", "",
            "| 설정 | 값 |", "| --- | --- |"]
    for key in ("REDIS_CONFIG", "REDIS_APPENDONLY", "REDIS_APPENDFSYNC", "HOLD_DURATION",
                "PAYMENT_MODE", "ACTIVE_CHECK_MODE", "SPRING_THREADS_VIRTUAL_ENABLED"):
        rows.append(f"| {key} | `{settings.get(key, '—')}` |")
    rows += ["", "| 실행 | 선점 성공 건수 | 선점 p95(ms) | 선점 p99(ms) | 확정 건수 |", 
             "| --- | ---: | ---: | ---: | ---: |"]
    for run in sorted(directory.iterdir()):
        if not run.is_dir() or not run.name.startswith(manifest["batch"] + "-"):
            continue
        summary = load(run / "summary.json")
        if summary is None:
            continue
        rows.append(f"| [{run.name}]({tag}/{run.name}/README.md) | "
                    f"{metric(summary, 'seat_holds_created', 'count')} | "
                    f"{metric(summary, 'seat_hold_duration', 'p(95)')} | "
                    f"{metric(summary, 'seat_hold_duration', 'p(99)')} | "
                    f"{metric(summary, 'reservations_confirmed', 'count')} |")
        rows.append(f"\n  [대시보드]({tag}/{run.name}/dashboard.png) · "
                    f"[패널 PNG]({tag}/{run.name}/panels/) · "
                    f"[Prometheus JSON]({tag}/{run.name}/prometheus/) · "
                    f"[k6 원본]({tag}/{run.name}/summary.json)")
    events = load(directory / "events.json") or []
    if events:
        rows += ["", "장애 주입과 상태 스냅샷(UTC, 시간순):", ""]
        for event in sorted(events, key=lambda item: item["at"]):
            snapshot = event.get("snapshot", "").replace("../../../docs/", "../../docs/", 1)
            suffix = f" · [스냅샷]({snapshot})" if snapshot else ""
            if snapshot and any(word in event["event"] for word in
                                ("app-closed", "completion", "worker", "stream", "mysql", "redis", "waiting", "balance")):
                state = load((RESULTS / snapshot).resolve())
                if state:
                    redis_count = len(state.get("redisReservedSeats") or [])
                    mysql_count = len(state.get("mysqlReservationSeats") or []) if state.get("mysqlAvailable") else "조회 불가"
                    missing = len(state.get("pgPaidWithoutReservation") or []) if state.get("mysqlAvailable") else "조회 불가"
                    suffix += f" · Redis 확정 {redis_count}석 / MySQL {mysql_count}석 / PG 승인·예매 없음 {missing}건"
            if event.get("run"):
                suffix += f" · 종료 {event['endedAt']} · 종료 코드 {event['exitCode']}"
            rows.append(f"- {event['at']} · {event['event']}{suffix}")
    rows.append("")
    return rows


def main():
    RESULTS.mkdir(exist_ok=True)
    rows = ["# 좌석 예매 측정 방법과 원본", "",
            f"문서 생성 시각(UTC): {datetime.now(timezone.utc).isoformat()}", "",
            "## 환경", "",
            "정식 측정은 AWS 서울 리전의 동일 AZ·서브넷에서 수행한다. 다음 표는 계획 사양이다. "
            "실행 사양과 다르면 측정 당시 인스턴스 정보를 별도로 기록해야 한다.", "",
            "| 호스트 | 인스턴스 | vCPU |", "| --- | --- | ---: |"]
    rows.extend(f"| {name} | {instance} | {cpu} |" for name, instance, cpu in HOSTS)
    rows += ["", "## 적용 튜닝", "", "아래 값은 배포 묶음에 포함한 설정 파일에서 읽었다.", "",
             "| 파일 | 설정 | 값 |", "| --- | --- | --- |"]
    for relative in ("infra/tuning/sysctl.conf", "infra/tuning/limits.conf",
                     "infra/tuning/mysql-stage.cnf", "infra/tuning/jvm.env"):
        for line in (ROOT / relative).read_text().splitlines():
            line = line.strip()
            if not line or line.startswith(("#", "[")):
                continue
            key, _, value = line.partition("=")
            if not value:
                parts = line.rsplit(" ", 1)
                if len(parts) != 2:
                    continue
                key, value = parts
            rows.append(f"| [{Path(relative).name}](../../{relative}) | `{key.strip()}` | `{value.strip()}` |")
    rows += ["", "## 절차", "",
             "각 단계의 git 태그에서 JAR를 빌드한다. 각 실행 전에 MySQL·Redis 데이터를 초기화하고 "
             "2분 워밍업을 거친다. A는 전체 흐름 3회, B는 선점 처리량 상한 1회 실행한다. "
             "로컬 `--smoke` 검증은 워밍업 1초, B 구간당 1초로 줄인다.", "",
             "- A: 상영 1개·1,000석·사용자 3만 명. 좌석 선택의 70%는 가운데 30%에 몰린다. "
             "선점 성공자의 85%가 결제하고, 결제자의 10%가 완료 API를 생략한다.",
             "- B: 입장 토큰을 미리 시드한 뒤 초당 100·200·400·800·1,600·3,200건을 각각 60초 보낸다. "
             "좌석 소진 뒤에는 충돌 응답 처리량이므로 성공 선점 TPS와 구분한다.",
             "- 모의 PG 승인 지연 500~2,000ms·웹훅 지연 200~800ms·첫 발송 실패 1%는 "
             "실측값이 아닌 가정이다.", "", "## 단계별 원본", ""]
    for tag in TAGS:
        rows.extend(stage_section(tag))
    rows += ["## 가상 스레드 켬 vs 끔", "",
             "값의 순서는 켬 / 끔이다. CPU는 reservation-api 프로세스 사용률이다.", "",
             "| 단계 | 가설 | 켬 선점 TPS | 끔 선점 TPS | p95·p99(ms) | Tomcat/JVM 스레드 | Hikari active/pending | pinning 건수 | CPU |",
             "| --- | --- | ---: | ---: | --- | --- | --- | --- | --- |",
             comparison("v2-db-fixed", "락 병목으로 차이 없음"),
             comparison("v4-streams", "HTTP 동기 호출 구간에서 켬이 유리"), "",
             "## DB 단독 기준과 V2 선점 상한", ""]
    evidence_links = []
    for tag in ("v2-db-fixed", "v4-streams"):
        manifest = load(RESULTS / tag / "batch.json")
        if not manifest:
            continue
        for state in ("on", "off"):
            name = f"{manifest['batch']}-vt-{state}"
            base = ROOT / "docs/evidence" / tag
            if (base / f"{name}.jfr").exists():
                evidence_links.append(f"- {tag} {state}: [JFR](../../docs/evidence/{tag}/{name}.jfr) · "
                                      f"[pinning 원본](../../docs/evidence/{tag}/{name}-pinning.txt)")
    if evidence_links:
        marker = rows.index("## DB 단독 기준과 V2 선점 상한")
        rows[marker:marker] = evidence_links + [""]
    baseline_root = RESULTS / "db-baseline"
    baseline_runs = sorted(baseline_root.glob("*/summary.json")) if baseline_root.exists() else []
    baseline = load(baseline_runs[-1]) if baseline_runs else None
    v2, _ = run_summary("v2-db-fixed")
    if baseline:
        peak = max((item["tps"] for item in baseline["hotRow"]), default=0)
        percentiles = baseline.get("fioSyncLatency", {}).get("lat_ns", {}).get("percentile", {})
        fio_text = (f"fsync p50 {int(percentiles['50.000000']) / 1_000_000:.3f}ms, "
                    f"p99 {int(percentiles['99.000000']) / 1_000_000:.3f}ms; ") if percentiles else ""
        rows += [f"- [DB 단독 원본](db-baseline/{baseline_runs[-1].parent.name}/README.md): "
                 f"{fio_text}hot row 최대 {peak:.2f} TPS. "
                 f"{'로컬 스크립트 검증이므로 정식 비교에서 제외한다.' if baseline['smoke'] else '정식 측정값이다.'}",
                 f"- V2 B 선점: {metric(v2 or {}, 'seat_holds_created', 'rate')} TPS. "
                 "두 값의 차이는 앱 처리·네트워크·대기열 조건을 포함한다.", ""]
    else:
        rows += ["- DB 단독 측정 원본이 아직 없다.", ""]
    every, every_path = run_summary("v6-aof-reconcile")
    always, always_path = run_summary("v6-aof-reconcile", "always")
    rows += ["## V6 AOF 설정 비교", "",
             "| 설정 | 선점 성공 TPS | p95(ms) | p99(ms) | 원본 |",
             "| --- | ---: | ---: | ---: | --- |",
             f"| everysec | {metric(every or {}, 'seat_holds_created', 'rate')} | "
             f"{metric(every or {}, 'seat_hold_duration', 'p(95)')} | "
             f"{metric(every or {}, 'seat_hold_duration', 'p(99)')} | "
             f"{'[k6](' + str(every_path.relative_to(RESULTS)) + '/summary.json)' if every_path else '—'} |",
             f"| always | {metric(always or {}, 'seat_holds_created', 'rate')} | "
             f"{metric(always or {}, 'seat_hold_duration', 'p(95)')} | "
             f"{metric(always or {}, 'seat_hold_duration', 'p(99)')} | "
             f"{'[k6](' + str(always_path.relative_to(RESULTS)) + '/summary.json)' if always_path else '—'} |", ""]
    (RESULTS / "README.md").write_text("\n".join(rows))
    print(RESULTS / "README.md")


if __name__ == "__main__":
    main()
