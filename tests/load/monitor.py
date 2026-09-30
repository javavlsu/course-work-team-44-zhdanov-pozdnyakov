"""Посекундный мониторинг ресурсов стенда во время нагрузочного теста (Linux, /proc).

  python3 monitor.py <out.csv>        — пишет строки, пока не получит SIGTERM/SIGINT

Колонки: t (unix), cpu_total_% (вся машина), java_cpu_% (100% = 1 ядро), java_rss_mb, java_threads,
heap_used_mb, gc_count, gc_time_s (jstat), mysqld_cpu_%, mysqld_rss_mb, db_threads_connected,
db_questions (накопительно), load1.
Переменная MYSQL_CLI — команда mysql-клиента (для Threads_connected/Questions).
"""
import csv
import os
import shlex
import signal
import subprocess
import sys
import threading
import time

HZ = os.sysconf("SC_CLK_TCK")
RUN = True


def stop(*_):
    global RUN
    RUN = False


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)


def pid_of(pattern):
    out = subprocess.run(["pgrep", "-f", pattern], capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else None


def proc_times(pid):
    try:
        with open(f"/proc/{pid}/stat") as f:
            parts = f.read().rsplit(")", 1)[1].split()
        with open(f"/proc/{pid}/status") as f:
            st = dict(line.split(":", 1) for line in f if ":" in line)
        return (int(parts[11]) + int(parts[12]), int(st["VmRSS"].split()[0]) / 1024, int(st["Threads"]))
    except (FileNotFoundError, ProcessLookupError, KeyError, IndexError):
        return None


def cpu_total():
    with open("/proc/stat") as f:
        v = list(map(int, f.readline().split()[1:]))
    idle = v[3] + v[4]
    return sum(v), idle


class Jstat(threading.Thread):
    """Фоновый `jstat -gc <pid> 1000`: последняя строка — занятый heap и GC."""
    def __init__(self, pid):
        super().__init__(daemon=True)
        self.pid, self.last = pid, ("", "", "")

    def run(self):
        java_home = os.environ.get("JAVA_HOME", "")
        exe = os.path.join(java_home, "bin", "jstat") if java_home else "jstat"
        p = subprocess.Popen([exe, "-gc", str(self.pid), "1000"], stdout=subprocess.PIPE, text=True)
        header = None
        for line in p.stdout:
            cols = line.split()
            if cols and cols[0] == "S0C":
                header = cols
                continue
            if header and len(cols) == len(header):
                d = dict(zip(header, cols))
                try:
                    used = sum(float(d[k]) for k in ("S0U", "S1U", "EU", "OU")) / 1024
                    gcc = int(d["YGC"]) + int(d["FGC"]) + int(d.get("CGC", "0").replace("-", "0") or 0)
                    self.last = (round(used, 1), gcc, float(d["GCT"]))
                except (KeyError, ValueError):
                    pass
            if not RUN:
                p.terminate()
                return


def db_status(cli):
    if not cli:
        return "", ""
    out = subprocess.run(shlex.split(cli) + ["-N", "-B", "-e",
                         "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Questions')"],
                         capture_output=True, text=True).stdout
    d = dict(line.split("\t") for line in out.strip().splitlines() if "\t" in line)
    return d.get("Threads_connected", ""), d.get("Questions", "")


def main():
    out = sys.argv[1]
    cli = os.environ.get("MYSQL_CLI")
    java, mysql = pid_of("image-builder-0.0.1-SNAPSHO[T]"), pid_of("mysqlroot/usr/sbin/mysql[d]")
    js = Jstat(java) if java else None
    if js:
        js.start()
    prev = {"java": proc_times(java) if java else None, "mysql": proc_times(mysql) if mysql else None,
            "tot": cpu_total(), "t": time.time()}
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["t", "cpu_total_pct", "java_cpu_pct", "java_rss_mb", "java_threads", "heap_used_mb",
                    "gc_count", "gc_time_s", "mysqld_cpu_pct", "mysqld_rss_mb", "db_threads_connected",
                    "db_questions", "load1"])
        while RUN:
            time.sleep(1)
            now = time.time()
            dt = now - prev["t"]
            tot = cpu_total()
            cpu_pct = 100 * (1 - (tot[1] - prev["tot"][1]) / max(1, tot[0] - prev["tot"][0]))
            row = [round(now, 1), round(cpu_pct, 1)]
            for key, pid in (("java", java), ("mysql", mysql)):
                cur = proc_times(pid) if pid else None
                if cur and prev[key]:
                    row_cpu = round(100 * (cur[0] - prev[key][0]) / HZ / dt, 1)
                    vals = [row_cpu, round(cur[1], 1)] + ([cur[2]] if key == "java" else [])
                else:
                    vals = ["", ""] + ([""] if key == "java" else [])
                if key == "java":
                    row += vals + (list(js.last) if js else ["", "", ""])
                else:
                    row += vals
                prev[key] = cur
            row += list(db_status(cli))
            row.append(os.getloadavg()[0])
            w.writerow(row)
            f.flush()
            prev["tot"], prev["t"] = tot, now


if __name__ == "__main__":
    main()
