FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MYSTOCK_UI_PORT=8443

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY mystock_ui ./mystock_ui
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN useradd --create-home --uid 10001 mystock && chmod 0755 /usr/local/bin/docker-entrypoint.sh
USER mystock

EXPOSE 8443
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
