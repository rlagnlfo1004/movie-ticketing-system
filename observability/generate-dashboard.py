#!/usr/bin/env python3
"""Generate the provisioned Grafana dashboard from the query list below."""
import json
from pathlib import Path

queries = [
    ("Host CPU user/system/iowait", 'sum by(mode)(rate(node_cpu_seconds_total{mode=~"user|system|iowait"}[1m]))', "%"),
    ("Host memory used", 'node_memory_MemTotal_bytes-node_memory_MemAvailable_bytes', "bytes"),
    ("Host network receive", 'rate(node_network_receive_bytes_total{device!="lo"}[1m])', "Bps"),
    ("Host network transmit", 'rate(node_network_transmit_bytes_total{device!="lo"}[1m])', "Bps"),
    ("Disk read/write IOPS", 'sum by(device)(rate(node_disk_reads_completed_total[1m])+rate(node_disk_writes_completed_total[1m]))', "ops"),
    ("Disk write latency", 'rate(node_disk_write_time_seconds_total[1m])/clamp_min(rate(node_disk_writes_completed_total[1m]),1)', "s"),
    ("InnoDB row lock waits", 'rate(mysql_global_status_innodb_row_lock_waits[1m])', "ops"),
    ("InnoDB row lock wait time", 'rate(mysql_global_status_innodb_row_lock_time[1m])', "ms"),
    ("InnoDB deadlocks", 'increase(mysql_info_schema_innodb_metrics_lock_lock_deadlocks_total[1m])', "ops"),
    ("MySQL Threads_running", 'mysql_global_status_threads_running', "short"),
    ("MySQL commands", 'sum by(command)(rate(mysql_global_status_commands_total{command=~"set_option|commit"}[1m]))', "ops"),
    ("Redis CPU", 'rate(redis_cpu_user_seconds_total[1m])+rate(redis_cpu_sys_seconds_total[1m])', "percentunit"),
    ("Redis operations", 'rate(redis_commands_processed_total[1m])', "ops"),
    ("Redis memory", 'redis_memory_used_bytes', "bytes"),
    ("Redis connections", 'redis_connected_clients', "short"),
    ("Redis AOF delayed fsync", 'increase(redis_aof_delayed_fsync[1m])', "ops"),
    ("Redis stream pending", 'redis_stream_group_messages_pending', "short"),
    ("Redis stream lag", 'redis_stream_group_lag', "short"),
    ("Hikari active", 'hikaricp_connections_active', "short"),
    ("Hikari pending", 'hikaricp_connections_pending', "short"),
    ("Tomcat busy threads", 'tomcat_threads_busy_threads', "short"),
    ("JVM live threads", 'jvm_threads_live_threads', "short"),
    ("Reservation app CPU", 'process_cpu_usage{job="reservation-api"}', "percentunit"),
    ("JVM GC pause", 'rate(jvm_gc_pause_seconds_sum[1m])/clamp_min(rate(jvm_gc_pause_seconds_count[1m]),1)', "s"),
    ("Nginx connections", 'nginx_connections_active', "short"),
    ("k6 requests/s", 'sum(rate(k6_http_reqs_total[1m]))', "ops"),
    ("k6 request p95", 'k6_http_req_duration_p95', "ms"),
    ("k6 error rate", 'sum(rate(k6_http_reqs_total{expected_response="false"}[1m]))/clamp_min(sum(rate(k6_http_reqs_total[1m])),1)', "percentunit"),
    ("Webhook response p95", 'histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket{job="reservation-api",uri="/api/v1/webhooks/portone"}[1m])))', "s"),
]

panels = []
for index, (title, expression, unit) in enumerate(queries, start=1):
    panels.append({
        "id": index, "title": title, "type": "timeseries", "datasource": {"type": "prometheus", "uid": "prometheus"},
        "gridPos": {"h": 6, "w": 12, "x": 12 * ((index - 1) % 2), "y": 6 * ((index - 1) // 2)},
        "targets": [{"refId": "A", "expr": expression, "legendFormat": "{{instance}} {{mode}} {{command}}"}],
        "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}},
    })

dashboard = {
    "uid": "seat-redesign", "title": "Seat Redesign Measurement", "schemaVersion": 41,
    "version": 1, "refresh": "5s", "timezone": "browser", "panels": panels,
    "time": {"from": "now-15m", "to": "now"},
}
destination = Path(__file__).parent / "grafana/provisioning/dashboards/json/seat.json"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n")
