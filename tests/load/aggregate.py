"""Сводка результатов нагрузочных профилей: raw CSV k6 + мониторинг → load_results.json.

  python3 aggregate.py <results_dir> [profile ...]
"""
import csv
import gzip
import json
import math
import os
import sys
from collections import defaultdict

BUCKET = 5  # секунд в точке временного ряда

# Плато ступенчатых профилей: (начало, конец, целевая интенсивность) в секундах от старта сценария.
STEPS = {
    "stress": [(30, 90, 50), (120, 180, 100), (210, 270, 200), (300, 360, 300), (390, 450, 450), (480, 540, 600)],
    "breakpoint": [(30, 90, 1000), (120, 180, 1500), (210, 270, 2000), (300, 360, 3000), (390, 450, 4000)],
    "login": [(30, 60, 10), (90, 120, 25), (150, 180, 50), (210, 240, 100)],
    "spike": [(10, 60, "10 VU (до всплеска)"), (80, 130, "400 VU (всплеск)"), (150, 200, "10 VU (после)")],
}


def pct(sorted_vals, p):
    if not sorted_vals:
        return None
    k = (len(sorted_vals) - 1) * p / 100
    f, c = math.floor(k), math.ceil(k)
    return sorted_vals[f] if f == c else sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def stats(vals):
    v = sorted(vals)
    if not v:
        return dict(count=0)
    return dict(count=len(v), avg=round(sum(v) / len(v), 2), min=round(v[0], 2), med=round(pct(v, 50), 2),
                p90=round(pct(v, 90), 2), p95=round(pct(v, 95), 2), p99=round(pct(v, 99), 2), max=round(v[-1], 2))


def load_profile(d, prof):
    raw = os.path.join(d, f"raw-{prof}.csv.gz")
    if not os.path.exists(raw):
        return None
    meta = json.load(open(os.path.join(d, f"meta-{prof}.json")))
    summary = json.load(open(os.path.join(d, f"summary-{prof}.json")))

    durations, by_name, by_status = [], defaultdict(list), defaultdict(int)
    err_by_name, failed = defaultdict(int), 0
    per_sec = defaultdict(lambda: dict(n=0, err=0, d=[], vus=0))
    ttr, data_rx, t_first, t_last = [], 0.0, None, None
    for row in csv.DictReader(gzip.open(raw, "rt")):
        m, ts = row["metric_name"], int(row["timestamp"]) / 1000
        if row["group"] == "::setup" or "kind=setup" in row["extra_tags"]:
            continue
        sec = int(ts)
        if m == "http_req_duration":
            v = float(row["metric_value"])
            name = row["name"]
            durations.append(v)
            by_name[name].append(v)
            by_status[row["status"]] += 1
            b = per_sec[sec]
            b["n"] += 1
            b["d"].append(v)
            t_first = ts if t_first is None else min(t_first, ts)
            t_last = ts if t_last is None else max(t_last, ts)
        elif m == "http_req_failed" and row["metric_value"].startswith("1"):
            failed += 1
            err_by_name[row["name"]] += 1
            per_sec[sec]["err"] += 1
        elif m == "vus":
            per_sec[sec]["vus"] = max(per_sec[sec]["vus"], int(float(row["metric_value"])))
        elif m == "data_received":
            data_rx += float(row["metric_value"])
        elif m == "build_time_to_ready":
            ttr.append(float(row["metric_value"]) / 1000)

    span = max(1e-9, (t_last or 0) - (t_first or 0))
    secs = sorted(per_sec)
    t0 = t_first or 0

    # ресурсы
    mon = []
    mpath = os.path.join(d, f"monitor-{prof}.csv")
    if os.path.exists(mpath):
        for r in csv.DictReader(open(mpath)):
            mon.append({k: (float(v) if v not in ("", None) else None) for k, v in r.items()})
    mon = [r for r in mon if t0 - 1 <= r["t"] <= (t_last or 0) + 1] or mon

    def col(name):
        return [r[name] for r in mon if r.get(name) is not None]

    def mm(name, nd=1):
        v = col(name)
        return dict(avg=round(sum(v) / len(v), nd), max=round(max(v), nd)) if v else {}

    q = col("db_questions")
    gcn, gct = col("gc_count"), col("gc_time_s")
    resources = dict(
        cpu_total=mm("cpu_total_pct"), java_cpu=mm("java_cpu_pct"), mysqld_cpu=mm("mysqld_cpu_pct"),
        java_rss=mm("java_rss_mb"), heap=mm("heap_used_mb"), java_threads=mm("java_threads", 0),
        db_conn=mm("db_threads_connected", 0),
        gc_count=int(gcn[-1] - gcn[0]) if len(gcn) > 1 else None,
        gc_time_s=round(gct[-1] - gct[0], 2) if len(gct) > 1 else None,
        sql_per_sec=round((q[-1] - q[0]) / max(1, mon[-1]["t"] - mon[0]["t"]), 1) if len(q) > 1 else None,
        java_rss_start=col("java_rss_mb")[0] if col("java_rss_mb") else None,
        java_rss_end=col("java_rss_mb")[-1] if col("java_rss_mb") else None,
    )

    # временной ряд с шагом BUCKET
    timeline = []
    mon_by_bucket = defaultdict(list)
    for r in mon:
        mon_by_bucket[int((r["t"] - t0) // BUCKET)].append(r)
    for bi in range(int(span // BUCKET) + 1):
        ss = [s for s in secs if bi * BUCKET <= s - int(t0) < (bi + 1) * BUCKET]
        n = sum(per_sec[s]["n"] for s in ss)
        d_ = sorted(v for s in ss for v in per_sec[s]["d"])
        e = sum(per_sec[s]["err"] for s in ss)
        mb = mon_by_bucket.get(bi, [])

        def avg(k):
            v = [r[k] for r in mb if r.get(k) is not None]
            return round(sum(v) / len(v), 1) if v else None
        timeline.append(dict(t=bi * BUCKET, vus=max([per_sec[s]["vus"] for s in ss] or [0]),
                             rps=round(n / BUCKET, 1), p95=round(pct(d_, 95), 1) if d_ else None,
                             avg=round(sum(d_) / len(d_), 1) if d_ else None,
                             err_pct=round(100 * e / n, 2) if n else 0,
                             java_cpu=avg("java_cpu_pct"), mysqld_cpu=avg("mysqld_cpu_pct"),
                             cpu_total=avg("cpu_total_pct"), heap=avg("heap_used_mb"),
                             db_conn=avg("db_threads_connected")))

    steps = []
    for a, b, target in STEPS.get(meta.get("profile", prof), []):
        ss = [s for s in secs if a <= s - int(t0) < b]
        n = sum(per_sec[s]["n"] for s in ss)
        e = sum(per_sec[s]["err"] for s in ss)
        d_ = sorted(v for s in ss for v in per_sec[s]["d"])
        mb = [r for r in mon if a <= r["t"] - t0 < b]
        jc = [r["java_cpu_pct"] for r in mb if r.get("java_cpu_pct") is not None]
        steps.append(dict(target=target, achieved_rps=round(n / max(1, len(ss)), 1),
                          med=round(pct(d_, 50), 1) if d_ else None, p95=round(pct(d_, 95), 1) if d_ else None,
                          p99=round(pct(d_, 99), 1) if d_ else None, err_pct=round(100 * e / n, 2) if n else None,
                          java_cpu=round(sum(jc) / len(jc), 0) if jc else None))

    thresholds = []
    for mname, mv in summary["metrics"].items():
        for expr, res in (mv.get("thresholds") or {}).items():
            thresholds.append(dict(metric=mname, expr=expr, ok=res["ok"] if isinstance(res, dict) else bool(res)))

    checks = []

    def walk(g):
        for c in g.get("checks", []):
            checks.append(dict(name=c["name"], passes=c["passes"], fails=c["fails"]))
        for s in g.get("groups", []):
            walk(s)
    walk(summary["root_group"])

    vus_max = summary["metrics"].get("vus_max", {}).get("values", {}).get("max")
    return dict(
        profile=prof, meta=meta,
        overall=dict(requests=len(durations), duration_s=round(span, 1), rps_avg=round(len(durations) / span, 1),
                     rps_peak=max([per_sec[s]["n"] for s in secs] or [0]), failed=failed,
                     error_pct=round(100 * failed / max(1, len(durations)), 3),
                     status=dict(sorted(by_status.items())), vus_max=vus_max,
                     data_received_mb=round(data_rx / 1048576, 1),
                     throughput_mbps=round(data_rx / 1048576 / span, 1),
                     iterations=summary["metrics"].get("iterations", {}).get("values", {}).get("count"),
                     dropped_iterations=summary["metrics"].get("dropped_iterations", {}).get("values", {}).get("count", 0),
                     **{f"lat_{k}": v for k, v in stats(durations).items() if k != "count"}),
        endpoints=[dict(name=n, errors=err_by_name.get(n, 0), **stats(v)) for n, v in
                   sorted(by_name.items(), key=lambda kv: -len(kv[1]))],
        builds=dict(time_to_ready_s=stats(ttr),
                    ready=summary["metrics"].get("builds_ready", {}).get("values", {}).get("count", 0),
                    failed=summary["metrics"].get("builds_failed", {}).get("values", {}).get("count", 0)) if ttr or prof in ("burst", "mixed", "soak") else None,
        resources=resources, timeline=timeline, steps=steps, thresholds=thresholds, checks=checks,
    )


def main():
    d = sys.argv[1]
    profiles = sys.argv[2:] or ["smoke", "browse", "mixed", "stress", "breakpoint", "spike", "login", "burst", "download",
                                "soak", "volume_base", "volume"]
    out_path = os.path.join(d, "load_results.json")
    res = json.load(open(out_path)) if os.path.exists(out_path) else {}
    for p in profiles:
        r = load_profile(d, p)
        if r:
            res[p] = r
            o = r["overall"]
            print(f"{p:9} req={o['requests']:7} rps={o['rps_avg']:7} err={o['error_pct']:6}% "
                  f"p95={o.get('lat_p95')} p99={o.get('lat_p99')} max={o.get('lat_max')}")
    json.dump(res, open(out_path, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
