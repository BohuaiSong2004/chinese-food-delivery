#!/usr/bin/env python3
"""Writes observability/grafana/dashboards/wokflow.json. Edit here, not in the JSON."""
import json, pathlib

J = 'job="wokflow-api"'
panels, y = [], 0

def ds():
    return {"type": "prometheus", "uid": "prometheus"}

def stat(title, expr, unit, x, w=4, thresholds=None):
    panels.append({"type": "stat", "title": title, "datasource": ds(), "gridPos": {"x": x, "y": y, "w": w, "h": 4},
                   "targets": [{"refId": "A", "expr": expr, "datasource": ds()}],
                   "fieldConfig": {"defaults": {"unit": unit, "thresholds": {"mode": "absolute", "steps": thresholds or [{"color": "green", "value": None}]}},
                                   "overrides": []},
                   "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "area"}})

def series(title, targets, unit, x, w=12, h=8, desc=None):
    p = {"type": "timeseries", "title": title, "datasource": ds(), "gridPos": {"x": x, "y": y, "w": w, "h": h},
         "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": l, "datasource": ds()} for i, (e, l) in enumerate(targets)],
         "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 1, "fillOpacity": 8}}, "overrides": []},
         "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}}
    if desc:
        p["description"] = desc
    panels.append(p)

def row(title):
    global y
    panels.append({"type": "row", "title": title, "collapsed": False, "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []})
    y += 1

REQ = f'http_server_requests_seconds_count{{{J},uri!~"/actuator.*"}}'
# latency leaves out SSE streams: an /events request stays open by design until the client goes away
BUCKET = f'http_server_requests_seconds_bucket{{{J},uri!~"/actuator.*|.*/events"}}'

row("Traffic")
stat("Requests / s", f"sum(rate({REQ}[1m]))", "reqps", 0)
stat("5xx share", f'(sum(rate(http_server_requests_seconds_count{{{J},status=~"5.."}}[5m])) or vector(0)) / sum(rate({REQ}[5m]))', "percentunit", 4,
     thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.001}, {"color": "red", "value": 0.01}])
stat("p95, all routes", f"histogram_quantile(0.95, sum by (le) (rate({BUCKET}[5m])))", "s", 8,
     thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.25}, {"color": "red", "value": 0.5}])
stat("Replicas up", f"sum(up{{{J}}})", "none", 12, thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 2}])
stat("Live streams open", f"sum(wokflow_live_streams{{{J}}})", "none", 16)
stat("Outbox lag", f"max(wokflow_outbox_lag_seconds{{{J}}})", "s", 20,
     thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 5}, {"color": "red", "value": 30}])
y += 4
series("Requests / s by route (top 8)", [(f"topk(8, sum by (method, uri) (rate({REQ}[1m])))", "{{method}} {{uri}}")], "reqps", 0)
series("p95 latency by route (top 8, live streams excluded)", [(f"topk(8, histogram_quantile(0.95, sum by (le, method, uri) (rate({BUCKET}[5m]))))", "{{method}} {{uri}}")], "s", 12)
y += 8
series("Placing an order: POST /api/orders", [
    (f'histogram_quantile({q}, sum by (le) (rate(http_server_requests_seconds_bucket{{{J},method="POST",uri="/api/orders"}}[5m])))', f"p{int(q*100)}")
    for q in (0.5, 0.95, 0.99)], "s", 0, desc="Alert SlowOrderPlacement fires when p95 stays above 500 ms for 10 minutes.")
series("Errors by route and status", [(f'sum by (uri, status) (rate(http_server_requests_seconds_count{{{J},status=~"[45].."}}[5m]))', "{{status}} {{uri}}")], "reqps", 12)
y += 8

row("Orders, payments, dispatch")
series("Payment webhooks", [(f"sum by (type, result) (rate(wokflow_payments_webhooks_total{{{J}}}[5m]))", "{{type}} {{result}}"),
                            (f"sum(rate(wokflow_payments_intent_failures_total{{{J}}}[5m])) or vector(0)", "intent creation failed")], "ops", 0, 8)
series("Dispatch offers", [(f"sum by (result) (rate(wokflow_dispatch_offers_total{{{J}}}[5m]))", "{{result}}")], "ops", 8, 8,
       desc="offered: a nearby rider got an offer; expired: nobody answered in 30 s; nobody: no rider on shift nearby.")
series("Transaction retries", [(f"sum by (reason) (rate(wokflow_tx_retries_total{{{J}}}[5m]))", "{{reason}}")], "ops", 16, 8)
y += 8

row("Events and live updates")
series("Outbox", [(f"max(wokflow_outbox_lag_seconds{{{J}}})", "lag (s)"),
                  (f"sum(rate(wokflow_outbox_published_total{{{J}}}[1m]))", "published / s"),
                  (f"sum(rate(wokflow_outbox_failures_total{{{J}}}[5m]))", "failures / s")], "short", 0, 8)
series("Live streams per replica", [(f"wokflow_live_streams{{{J}}}", "{{instance}}")], "none", 8, 8)
series("Reconnects", [(f"sum by (result) (rate(wokflow_live_reconnects_total{{{J}}}[5m]))", "{{result}}")], "ops", 16, 8,
       desc="replayed: missed events re-sent from the Redis Stream; snapshot/resync: the client was too far behind.")
y += 8

row("Caches, search, resources")
series("Menu cache hit ratio", [(f'sum(rate(wokflow_menu_cache_total{{{J},result="hit"}}[5m])) / sum(rate(wokflow_menu_cache_total{{{J}}}[5m]))', "hit ratio")], "percentunit", 0, 6)
series("Search answered by Postgres", [(f"sum(rate(wokflow_search_fallback_total{{{J}}}[5m]))", "fallbacks / s")], "ops", 6, 6)
series("DB connections", [(f"sum by (state) (hikaricp_connections_{s}{{{J}}})".replace("by (state) ", ""), s) for s in ("active", "idle", "pending")], "none", 12, 6)
series("Heap used", [(f'sum by (instance) (jvm_memory_used_bytes{{{J},area="heap"}})', "{{instance}}")], "bytes", 18, 6)
y += 8

dash = {"uid": "wokflow", "title": "Wokflow API", "tags": ["wokflow"], "timezone": "browser", "schemaVersion": 41,
        "refresh": "10s", "time": {"from": "now-30m", "to": "now"}, "panels": panels, "editable": False}
for i, p in enumerate(panels, 1):
    p["id"] = i
out = pathlib.Path(__file__).parent / "grafana" / "dashboards" / "wokflow.json"
out.write_text(json.dumps(dash, indent=2) + "\n")
print(f"wrote {out} ({len(panels)} panels)")
