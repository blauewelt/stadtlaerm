PY ?= python3.12
VENV ?= .venv
BIN := $(VENV)/bin

.PHONY: venv test run publish fixture docker

venv:
	$(PY) -m venv $(VENV)
	$(BIN)/pip install -e '.[dev]'

test:
	$(BIN)/python -m pytest

run:
	DB_PATH=data/stadtlaerm.sqlite MAP_DIR=data/map CORS_ORIGIN=http://localhost:8080 \
		$(BIN)/python -m stadtlaerm_server serve

publish:
	$(BIN)/python -m stadtlaerm_server publish

fixture:
	$(BIN)/python scripts/make_fixture.py

docker:
	docker compose build
