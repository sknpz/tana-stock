from datetime import date

import httpx
import pytest
from conftest import NOW, ZONE
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import PeriodError, create_app, resolve_period

AUTH = {"Authorization": "Bearer token-de-teste"}
FRONT = "http://localhost:5500"


@pytest.fixture
def client(fake_api):
    settings = Settings(api_base_url="http://api.test", cors_allowed_origins=[FRONT], zone=ZONE,
                        api_timeout_seconds=5.0)
    app = create_app(settings, transport=fake_api.transport, clock=lambda: NOW)
    return TestClient(app)


def test_health_is_public(client):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_summary_returns_pdf_with_filename(client):
    response = client.get("/reports/summary.pdf", headers=AUTH)

    assert response.status_code == 200
    assert response.headers["content-type"] == "application/pdf"
    assert 'filename="relatorio-resumo-2026-10-22.pdf"' in response.headers["content-disposition"]
    assert response.content.startswith(b"%PDF")


def test_full_default_period_is_current_month(client):
    response = client.get("/reports/full.pdf", headers=AUTH)

    assert response.status_code == 200
    assert ('filename="relatorio-completo-2026-10-01-a-2026-10-22.pdf"'
            in response.headers["content-disposition"])
    assert response.content.startswith(b"%PDF")


def test_full_with_explicit_period(client):
    response = client.get("/reports/full.pdf?from=2026-09-01&to=2026-09-30", headers=AUTH)

    assert response.status_code == 200
    assert "relatorio-completo-2026-09-01-a-2026-09-30.pdf" in response.headers["content-disposition"]


@pytest.mark.parametrize("header", [None, "Basic abc", "Bearer ", "Bearer"])
def test_missing_token_returns_401_without_calling_api(client, fake_api, header):
    headers = {"Authorization": header} if header is not None else {}

    response = client.get("/reports/summary.pdf", headers=headers)

    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["title"] == "Não autenticado"
    assert response.json()["detail"] == "Faça login para continuar."
    assert fake_api.requests == []


def test_api_401_is_passed_through(client, fake_api):
    fake_api.routes["/api/auth/me"] = httpx.Response(401, json={"title": "Token expirado", "detail": "Entre de novo."})

    response = client.get("/reports/summary.pdf", headers=AUTH)

    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json() == {"title": "Token expirado", "status": 401, "detail": "Entre de novo."}


def test_api_403_is_passed_through(client, fake_api):
    fake_api.routes["/api/reports/summary"] = httpx.Response(403, json={})

    response = client.get("/reports/summary.pdf", headers=AUTH)

    assert response.status_code == 403
    assert response.headers["content-type"].startswith("application/problem+json")


def test_api_down_returns_502(client, fake_api):
    def refuse(request):
        raise httpx.ConnectError("recusou")

    fake_api.routes["/api/auth/me"] = refuse

    response = client.get("/reports/summary.pdf", headers=AUTH)

    assert response.status_code == 502
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["title"] == "API indisponível"


def test_from_after_to_returns_400_with_field_error(client, fake_api):
    response = client.get("/reports/full.pdf?from=2026-10-10&to=2026-10-01", headers=AUTH)

    assert response.status_code == 400
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body["title"] == "Dados inválidos"
    assert body["detail"] == "Um ou mais campos são inválidos."
    assert body["errors"] == {"to": "A data final não pode ser anterior à inicial."}
    assert fake_api.requests == []


def test_period_longer_than_366_days_returns_400(client):
    response = client.get("/reports/full.pdf?from=2025-01-01&to=2026-01-02", headers=AUTH)

    assert response.status_code == 400
    assert response.json()["errors"] == {"to": "O período pode ter no máximo 366 dias."}


def test_malformed_date_returns_400(client):
    response = client.get("/reports/full.pdf?from=ontem", headers=AUTH)

    assert response.status_code == 400
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body["title"] == "Dados inválidos"
    assert "from" in body["errors"]


def test_cors_allows_front_origin_and_exposes_content_disposition(client):
    preflight = client.options("/reports/summary.pdf", headers={
        "Origin": FRONT, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization"})
    response = client.get("/reports/summary.pdf", headers={**AUTH, "Origin": FRONT})

    assert preflight.headers["access-control-allow-origin"] == FRONT
    assert "authorization" in preflight.headers["access-control-allow-headers"].lower()
    assert response.headers["access-control-allow-origin"] == FRONT
    assert "Content-Disposition" in response.headers["access-control-expose-headers"]


def test_cors_rejects_other_origin(client):
    response = client.get("/health", headers={"Origin": "https://evil.example"})

    assert "access-control-allow-origin" not in response.headers


TODAY = date(2026, 10, 22)


def test_resolve_period_defaults_to_current_month():
    assert resolve_period(None, None, TODAY) == (date(2026, 10, 1), TODAY)


def test_resolve_period_only_start_goes_to_today():
    assert resolve_period(date(2026, 10, 5), None, TODAY) == (date(2026, 10, 5), TODAY)


def test_resolve_period_only_end_starts_at_month_first():
    assert resolve_period(None, date(2026, 8, 17), TODAY) == (date(2026, 8, 1), date(2026, 8, 17))


def test_resolve_period_start_after_end_fails():
    with pytest.raises(PeriodError) as error:
        resolve_period(date(2026, 10, 10), date(2026, 10, 9), TODAY)

    assert error.value.field == "to"
    assert error.value.message == "A data final não pode ser anterior à inicial."


def test_resolve_period_limit_is_366_days_inclusive():
    assert resolve_period(date(2025, 10, 22), date(2026, 10, 22), TODAY)  # 366 dias
    with pytest.raises(PeriodError) as error:
        resolve_period(date(2025, 10, 21), date(2026, 10, 22), TODAY)  # 367 dias

    assert error.value.message == "O período pode ter no máximo 366 dias."
