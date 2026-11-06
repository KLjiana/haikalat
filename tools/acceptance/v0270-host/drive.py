"""Drive the opt-in consumer inside an already running, isolated Minecraft client."""
import argparse
import csv
import json
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--runtime", required=True, type=Path)
parser.add_argument("--timeout", type=float, default=180)
args = parser.parse_args()
runtime = args.runtime.resolve()
deadline = time.monotonic() + args.timeout


def events():
    path = runtime / "events.csv"
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source))
    if any(row.get("event") == "FAIL" for row in rows):
        raise RuntimeError("Minecraft consumer reported FAIL; preserve events.csv and client log")
    return rows


while not any(row.get("event") == "captured" and row.get("command") == "1" for row in events()):
    if time.monotonic() >= deadline:
        raise TimeoutError("No first capture from the actual Minecraft callback")
    time.sleep(.2)

operations = ["ENABLE", "DENSITY", "QUALITY", "FAIL_PREPARE", "FAIL_RELOAD", "MC_RELOAD",
              "DISABLE", "ENABLE", "RESIZE", "SWITCH_WORLD", "ENABLE", "DENSITY",
              "FAIL_RELOAD", "REBUILD", "DISABLE", "RELEASE", "STOP"]
results = []
for sequence, operation in enumerate(operations, 2):
    before = events()
    temporary = runtime / "control.pending"
    temporary.write_text(f"{sequence} {operation}", encoding="ascii")
    temporary.replace(runtime / "control.txt")
    operation_deadline = min(deadline, time.monotonic() + 50)
    while time.monotonic() < operation_deadline:
        rows = events()
        captured = [row for row in rows if row.get("event") == "captured" and row.get("command") == str(sequence)]
        extra = True
        if operation == "MC_RELOAD":
            extra = sum(row.get("event") == "minecraft-resource-reload" for row in rows) > sum(
                row.get("event") == "minecraft-resource-reload" for row in before)
        elif operation == "SWITCH_WORLD":
            extra = any(row.get("event") == "world-first-frame" and int(row["world"]) > int(before[-1]["world"])
                        for row in rows)
        elif operation == "STOP":
            extra = any(row.get("event") == "minecraft-shutdown" for row in rows)
        if captured and extra:
            result = {"command": sequence, "operation": operation, "status": "PASS",
                      "frame": captured[-1]["frame"], "world": captured[-1]["world"]}
            results.append(result)
            print(json.dumps(result), flush=True)
            break
        time.sleep(.2)
    else:
        raise TimeoutError(f"Actual Minecraft operation did not finish: {operation}")
    time.sleep(.6)
(runtime / "operation-results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
print("HOST_SEQUENCE_PASSED", len(results), flush=True)
