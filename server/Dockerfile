FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DB_PATH=/data/stadtlaerm.sqlite \
    MAP_DIR=/data/map

# sqlite3 CLI for `.backup` from the host (docker compose exec app sqlite3 ...)
RUN apt-get update && apt-get install -y --no-install-recommends sqlite3 tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app app \
    && mkdir -p /data && chown app /data

WORKDIR /app
COPY pyproject.toml ./
COPY stadtlaerm_server ./stadtlaerm_server
RUN pip install --no-cache-dir .

USER app
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=4)"
CMD ["python", "-m", "stadtlaerm_server", "serve"]
