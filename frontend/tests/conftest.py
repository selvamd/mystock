import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from mystock_ui import client, data  # noqa: E402
from mystock_ui.devtools.fake_server import FakeBackend  # noqa: E402

KEY = "test-key"


@pytest.fixture(scope="session")
def backend():
    fb = FakeBackend(key=KEY).start()
    yield fb
    fb.stop()


@pytest.fixture
def live(backend):
    """Point the process-wide client at the fake backend with a fresh market context."""
    backend.fail_ops.clear()
    c = client.Client("127.0.0.1", backend.port, KEY, connect_timeout=2, read_timeout=20)
    client.set_client(c)
    data.invalidate()
    yield backend
    backend.fail_ops.clear()
    c.close()
    client.set_client(None)
    data.invalidate()


@pytest.fixture
def session(live):
    tok = live.fx.login("demo", "demo")
    return {"token": tok, "login": "demo"}


@pytest.fixture
def pc(session):
    return {"token": session["token"], "login": "demo", "stock": "AAPL"}
