"""Legacy quant/screener modules copied from ``mystk/python`` (kept verbatim).

Only the data boundary changed: ``svcreader.py`` is a drop-in replacement that talks to the
backend protocol, and the third-party fetchers in ``utils.py`` are routed through backend ops.
Every changed line inside a copied module is marked ``# CHANGED:``.

``myglobal.py``/``shell.py`` reproduce the legacy IPython startup; the Dash app never imports them.
"""
