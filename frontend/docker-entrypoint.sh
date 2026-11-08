#!/bin/sh
set -e
PORT="${MYSTOCK_UI_PORT:-8443}"
set -- gunicorn mystock_ui.app:server \
    --bind "0.0.0.0:${PORT}" \
    --workers "${MYSTOCK_UI_WORKERS:-2}" --threads "${MYSTOCK_UI_THREADS:-4}" \
    --timeout "${MYSTOCK_UI_TIMEOUT:-180}" --access-logfile -
if [ -n "${MYSTOCK_TLS_CERT}" ] && [ -n "${MYSTOCK_TLS_KEY}" ]; then
    set -- "$@" --certfile "${MYSTOCK_TLS_CERT}" --keyfile "${MYSTOCK_TLS_KEY}"
fi
exec "$@"
