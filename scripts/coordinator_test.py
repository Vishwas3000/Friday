"""Run Room/fake-provider and research selector checks on Android, without models or external app actions.

Forward Friday first: adb -s DEVICE forward tcp:8765 localabstract:layatest_agent
Then: python3 scripts/coordinator_test.py [--port 8765]
Reports are private, ignored files under captures/coordinator-tests/.
Real Gemini interaction and process-death acceptance are separate device tests.
"""
import argparse
import json
from pathlib import Path
import socket
import sys
from datetime import datetime, timezone


def call(port, command):
    with socket.create_connection(("127.0.0.1", port), timeout=45) as connection:
        with connection.makefile("rw", encoding="utf-8") as stream:
            stream.write(json.dumps({"cmd": command}) + "\n")
            stream.flush()
            line = stream.readline()
    if not line:
        raise RuntimeError("Friday closed the bridge before returning a result")
    return json.loads(line)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    try:
        coordinator = call(args.port, "coordinator_selfcheck")
        selectors = call(args.port, "research_selfcheck")
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Checks could not finish: {error}", file=sys.stderr)
        return 1
    report = {"coordinator": coordinator, "research_selectors": selectors,
              "scope": "Android Room and fake provider; no real Gemini, native models or process kill"}
    folder = Path(__file__).resolve().parent.parent / "captures/coordinator-tests"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ") + ".json")
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    passed = (coordinator.get("status") == "ok" and coordinator.get("failed") == 0 and
              coordinator.get("passed", 0) > 0 and all(selectors.get(key) == "ok"
              for key in ("gemini_chat", "gemini_research", "gemini_copy", "hotel")))
    print(f"Coordinator: {coordinator.get('passed', 0)} passed, {coordinator.get('failed', '?')} failed")
    print(f"Research selectors: {selectors}")
    print(f"Report: {path.relative_to(Path(__file__).resolve().parent.parent)}")
    if not passed:
        print(json.dumps(report, indent=2), file=sys.stderr)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
