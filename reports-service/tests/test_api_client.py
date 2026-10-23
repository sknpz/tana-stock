from datetime import date
from decimal import Decimal

import httpx
import pytest

from app.api_client import ApiError, TanaApiClient


def make_client(handler, authorization="Bearer abc"):
    return TanaApiClient("http://api", authorization, 5.0, transport=httpx.MockTransport(handler))


def page(content, number, total_pages):
    return {"content": content, "page": {"size": 100, "number": number, "totalElements": 0, "totalPages": total_pages}}


def test_forwards_authorization_header():
    seen = {}

    def handler(request):
        seen["auth"] = request.headers["Authorization"]
        return httpx.Response(200, json={"id": 1})

    assert make_client(handler).me() == {"id": 1}
    assert seen["auth"] == "Bearer abc"


def test_decimal_values_are_parsed_as_decimal():
    def handler(request):
        return httpx.Response(200, text='[{"unitPrice": 10.10, "quantity": 3}]')

    items = make_client(handler).inventory()
    assert items[0]["unitPrice"] == Decimal("10.10")
    assert isinstance(items[0]["unitPrice"], Decimal)
    assert items[0]["quantity"] == 3


def test_products_by_status_walks_all_pages():
    def handler(request):
        assert request.url.params["status"] == "LOW"
        n = int(request.url.params["page"])
        return httpx.Response(200, json=page([{"id": n}], n, 3))

    assert make_client(handler).products_by_status("LOW") == [{"id": 0}, {"id": 1}, {"id": 2}]


def test_movements_stops_at_limit_and_flags_truncated():
    def handler(request):
        assert request.url.params["from"] == "2026-10-01"
        assert request.url.params["to"] == "2026-10-22"
        assert request.url.params["sort"] == "createdAt,desc"
        n = int(request.url.params["page"])
        return httpx.Response(200, json=page([{"id": n * 2}, {"id": n * 2 + 1}], n, 5))

    items, truncated = make_client(handler).movements(date(2026, 10, 1), date(2026, 10, 22), 3)
    assert [i["id"] for i in items] == [0, 1, 2]
    assert truncated is True


def test_movements_not_truncated_when_all_fit():
    def handler(request):
        return httpx.Response(200, json=page([{"id": 1}, {"id": 2}], 0, 1))

    items, truncated = make_client(handler).movements(date(2026, 10, 1), date(2026, 10, 22), 2)
    assert len(items) == 2
    assert truncated is False


@pytest.mark.parametrize("status", [401, 403])
def test_401_and_403_keep_status_and_detail(status):
    def handler(request):
        return httpx.Response(status, json={"title": "Sem acesso", "status": status, "detail": "Token inválido."})

    with pytest.raises(ApiError) as exc:
        make_client(handler).summary()
    assert exc.value.status == status
    assert exc.value.title == "Sem acesso"
    assert exc.value.detail == "Token inválido."


def test_500_becomes_502():
    with pytest.raises(ApiError) as exc:
        make_client(lambda request: httpx.Response(500, text="boom")).summary()
    assert exc.value.status == 502
    assert exc.value.title == "API indisponível"
    assert exc.value.detail == "Não foi possível consultar a API do Tana Stock."


def test_timeout_becomes_504():
    def handler(request):
        raise httpx.ReadTimeout("lento", request=request)

    with pytest.raises(ApiError) as exc:
        make_client(handler).summary()
    assert exc.value.status == 504
    assert exc.value.title == "API demorou a responder"


def test_connection_error_becomes_502():
    def handler(request):
        raise httpx.ConnectError("recusada", request=request)

    with pytest.raises(ApiError) as exc:
        make_client(handler).summary()
    assert exc.value.status == 502


def test_context_manager_closes_client():
    client = make_client(lambda request: httpx.Response(200, json={}))
    with client as same:
        assert same is client
    assert client._client.is_closed


@pytest.mark.parametrize("response", [
    httpx.Response(200, text="<html>nao e json</html>"),
    httpx.Response(200, json={"content": []}),
    httpx.Response(200, json={"page": {"totalPages": 1}}),
])
def test_malformed_success_body_becomes_502(response):
    client = make_client(lambda request: response)

    with pytest.raises(ApiError) as error:
        client.me() if response.text.startswith("<") else client.products_by_status("LOW_STOCK")

    assert error.value.status == 502
    assert error.value.title == "API indisponível"
