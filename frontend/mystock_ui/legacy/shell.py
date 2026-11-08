"""IPython startup equivalent of legacy ``mystk/python/setup.py`` + ``myglobal.py``.

Usage (backend reachable, env as in .env)::

    MYSTOCK_API_HOST=localhost MYSTOCK_API_KEY=... ipython
    >>> from mystock_ui.legacy.shell import *
    >>> ss('movers', -5.0, 'weekpnl', 20.0, None)
    >>> ss.zviz.tsstock('AAPL', 365).show()

Builds ``ctx = globalcontext()``, ``ss = stock_screener(ctx)``, ``mleg.screener = ss``,
the autofill helpers ``q``, ``feat``, ``ssf``, ``ssq`` and ``portfolio(name, sess)``.
Set MYSTOCK_SHELL_LOGIN / MYSTOCK_SHELL_PASSWORD to also log in and get ``session``/``sel``.
"""
from .option import *
from .myutils import *
from .plots import *
from .utils import *
from .myglobal import *
from . import myutils as _myutils
_myutils.OPEN_BROWSER = True   # legacy IPython behaviour: web helpers open the browser
