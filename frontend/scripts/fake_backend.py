#!/usr/bin/env python
"""Run the fake protocol backend for local UI development.

    python scripts/fake_backend.py 9090 my-api-key

Then start the UI with MYSTOCK_API_HOST=127.0.0.1 MYSTOCK_API_PORT=9090 MYSTOCK_API_KEY=my-api-key.
Login: demo / demo.
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from mystock_ui.devtools.fake_server import FakeBackend  # noqa: E402


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9090
    key = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("MYSTOCK_API_KEY", "test-key")
    fb = FakeBackend(key=key, port=port).start()
    print(f"fake backend listening on 127.0.0.1:{fb.port}", flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        fb.stop()


if __name__ == "__main__":
    main()
