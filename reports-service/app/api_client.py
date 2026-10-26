"""Cliente síncrono da API Java do Tana Stock, repassando o JWT do usuário."""

import json
from datetime import date
from decimal import Decimal
from typing import Self

import httpx

PAGE_SIZE = 100


def _unavailable() -> "ApiError":
    return ApiError(502, "API indisponível", "Não foi possível consultar a API do Tana Stock.")


class ApiError(Exception):
    def __init__(self, status: int, title: str, detail: str):
        super().__init__(f"{status} {title}: {detail}")
        self.status = status
        self.title = title
        self.detail = detail


class TanaApiClient:
    def __init__(self, base_url: str, authorization: str, timeout: float,
                 transport: httpx.BaseTransport | None = None):
        self._client = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": authorization},
            timeout=timeout,
            transport=transport,
        )

    def _get(self, path: str, params: dict | None = None):
        try:
            response = self._client.get(path, params=params)
        except httpx.TimeoutException as exc:
            raise ApiError(504, "API demorou a responder",
                           "A API do Tana Stock demorou demais para responder. Tente novamente em instantes.") from exc
        except httpx.TransportError as exc:
            raise _unavailable() from exc

        if response.status_code in (401, 403):
            title, detail = "Acesso negado", "Sem permissão para acessar este recurso."
            try:
                body = response.json()
                title = body.get("title") or title
                detail = body.get("detail") or detail
            except (ValueError, AttributeError):
                pass
            raise ApiError(response.status_code, title, detail)
        if response.status_code >= 400:
            raise _unavailable()
        # Decimal evita perder precisão em valores monetários
        try:
            return json.loads(response.text, parse_float=Decimal)
        except ValueError as exc:
            raise _unavailable() from exc

    def _page(self, path: str, params: dict) -> tuple[list, int]:
        """Uma página de resultados: o conteúdo e o total de páginas."""
        body = self._get(path, params)
        try:
            return body["content"], body["page"]["totalPages"]
        except (KeyError, TypeError) as exc:
            raise _unavailable() from exc

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()

    def me(self) -> dict:
        return self._get("/api/auth/me")

    def summary(self) -> dict:
        return self._get("/api/reports/summary")

    def top_products(self, limit: int) -> list[dict]:
        return self._get("/api/reports/top-products", {"limit": limit})

    def products_by_status(self, status: str) -> list[dict]:
        items: list[dict] = []
        number = 0
        while True:
            content, total_pages = self._page("/api/products", {"status": status, "size": PAGE_SIZE, "page": number})
            items.extend(content)
            number += 1
            if number >= total_pages:
                return items

    def inventory(self) -> list[dict]:
        return self._get("/api/reports/inventory")

    def movements_summary(self, start: date, end: date) -> dict:
        return self._get("/api/reports/movements-summary", {"from": start.isoformat(), "to": end.isoformat()})

    def movements(self, start: date, end: date, limit: int) -> tuple[list[dict], bool]:
        items: list[dict] = []
        number = 0
        while True:
            content, total_pages = self._page("/api/movements", {
                "from": start.isoformat(), "to": end.isoformat(),
                "size": PAGE_SIZE, "sort": "createdAt,desc", "page": number,
            })
            for item in content:
                if len(items) >= limit:
                    return items, True
                items.append(item)
            number += 1
            if number >= total_pages:
                return items, False
