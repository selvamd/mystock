"""mystock Dash frontend (port of the legacy Streamlit app ``mystk/python/sapp.py``).

Rule: this package never opens a database and never calls a third-party data API.
Every byte of market/user data comes from the Java backend through ``mystock_ui.client``.
"""

__version__ = "1.0.0"
