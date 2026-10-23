"""API Java simulada em memória, com os dados de backend/src/main/resources/db/seed/dev_seed.sql.

As respostas padrão são calculadas a partir de `fake_api.products` e `fake_api.movements`, então trocar a lista
de produtos mantém resumo, alertas, ranking e inventário coerentes entre si. Para forçar uma resposta específica
(inclusive erros), use `fake_api.routes[caminho] = json | httpx.Response | função(request)`.
"""

import json
import math
from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from zoneinfo import ZoneInfo

import httpx
import pytest

from app.api_client import TanaApiClient

# 22/10/2026 21:50 no fuso da empresa
NOW = datetime(2026, 10, 23, 0, 50, tzinfo=UTC)
ZONE = ZoneInfo("America/Sao_Paulo")
CREATED_AT = "2026-09-01T12:00:00Z"

ADMIN = {"id": 1, "name": "Administrador"}
OPERATOR = {"id": 2, "name": "Operador"}

CATEGORIES = {1: "Ferragens", 2: "Elétrica", 3: "Hidráulica", 4: "Ferramentas"}
SUPPLIERS = {1: "Distribuidora Paraná Ferragens", 2: "Elétrica Oeste Atacado", 3: "Hidro Sul Materiais"}


def stock_status(quantity: int, min_stock: int) -> str:
    if quantity == 0:
        return "OUT_OF_STOCK"
    return "LOW_STOCK" if quantity <= min_stock else "OK"


def make_product(id: int, name: str, sku: str, category: dict | None, supplier: dict | None,
                 unit_price: str | Decimal, quantity: int, min_stock: int) -> dict:
    """Monta um ProductResponse como a API Java devolve."""
    price = Decimal(unit_price)
    return {
        "id": id, "name": name, "sku": sku, "category": category, "supplier": supplier,
        "unitPrice": price, "quantity": quantity, "minStock": min_stock, "totalValue": price * quantity,
        "stockStatus": stock_status(quantity, min_stock), "createdAt": CREATED_AT, "updatedAt": CREATED_AT,
    }


def seed_products() -> list[dict]:
    def ref(table: dict, key: int) -> dict:
        return {"id": key, "name": table[key]}

    rows = [
        (1, 'Parafuso sextavado 1/4" (cento)', "FER-001", 1, 1, "18.90", 120, 30),
        (2, 'Dobradiça 3" zincada', "FER-002", 1, 1, "7.50", 25, 30),
        (3, "Cabo flexível 2,5mm (rolo 100m)", "ELE-001", 2, 2, "289.00", 14, 10),
        (4, "Disjuntor bipolar 32A", "ELE-002", 2, 2, "42.90", 0, 5),
        (5, "Tubo PVC soldável 25mm (6m)", "HID-001", 3, 3, "24.50", 60, 20),
        (6, 'Registro de gaveta 3/4"', "HID-002", 3, 3, "56.00", 8, 10),
        (7, "Furadeira de impacto 650W", "TOO-001", 4, 1, "329.90", 6, 3),
    ]
    return [make_product(id, name, sku, ref(CATEGORIES, cat), ref(SUPPLIERS, sup), price, qty, min_stock)
            for id, name, sku, cat, sup, price, qty, min_stock in rows]


def make_movement(id: int, product: dict, type: str, quantity: int, previous: int, user: dict | None,
                  created_at: datetime, notes: str | None = None) -> dict:
    """Monta um StockMovementResponse como a API Java devolve."""
    new = previous + quantity if type == "ENTRY" else previous - quantity
    return {
        "id": id, "product": {"id": product["id"], "name": product["name"]}, "type": type, "quantity": quantity,
        "previousQuantity": previous, "newQuantity": new, "notes": notes, "user": user,
        "createdAt": created_at.isoformat().replace("+00:00", "Z"),
    }


def seed_movements(products: list[dict]) -> list[dict]:
    by_id = {p["id"]: p for p in products}
    rows = [
        (1, "ENTRY", 150, 0, ADMIN, 20, "Estoque inicial"),
        (1, "EXIT", 30, 150, OPERATOR, 3, "Venda balcão"),
        (2, "ENTRY", 40, 0, ADMIN, 20, "Estoque inicial"),
        (2, "EXIT", 15, 40, OPERATOR, 2, "Pedido #1042"),
        (3, "ENTRY", 14, 0, ADMIN, 18, "Estoque inicial"),
        (4, "ENTRY", 10, 0, ADMIN, 18, "Estoque inicial"),
        (4, "EXIT", 10, 10, OPERATOR, 1, "Obra Residencial Jardim"),
        (5, "ENTRY", 60, 0, ADMIN, 15, "Estoque inicial"),
        (6, "ENTRY", 8, 0, ADMIN, 15, "Estoque inicial"),
        (7, "ENTRY", 6, 0, ADMIN, 10, "Estoque inicial"),
    ]
    return [make_movement(i, by_id[pid], type, qty, previous, user, NOW - timedelta(days=days), notes)
            for i, (pid, type, qty, previous, user, days, notes) in enumerate(rows, start=1)]


def _page(items: list, request: httpx.Request) -> dict:
    size = int(request.url.params.get("size", 20))
    number = int(request.url.params.get("page", 0))
    return {
        "content": items[number * size:(number + 1) * size],
        "page": {"size": size, "number": number, "totalElements": len(items),
                 "totalPages": math.ceil(len(items) / size)},
    }


def _encode(value):
    if isinstance(value, Decimal):
        # Os valores de teste têm no máximo duas casas: float os representa sem perda ao voltar para Decimal
        return float(value)
    raise TypeError(f"{type(value).__name__} não é serializável")


class FakeApi:
    make_product = staticmethod(make_product)
    make_movement = staticmethod(make_movement)

    def __init__(self):
        self.user = {"id": 1, "name": "Administrador", "email": "admin@tana.local", "role": "ADMIN",
                     "active": True}
        self.products = seed_products()
        self.movements = seed_movements(self.products)
        self.routes: dict[str, object] = {}
        self.requests: list[httpx.Request] = []

    @property
    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handler)

    def client(self, authorization: str = "Bearer token-de-teste") -> TanaApiClient:
        return TanaApiClient("http://api.test", authorization, 5.0, transport=self.transport)

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path in self.routes:
            value = self.routes[path]
        elif path in self._defaults:
            value = self._defaults[path]
        else:
            return httpx.Response(404, json={"title": "Não encontrado", "status": 404})
        if callable(value):
            value = value(request)
        if isinstance(value, httpx.Response):
            return value
        return httpx.Response(200, text=json.dumps(value, default=_encode),
                              headers={"Content-Type": "application/json"})

    @property
    def _defaults(self) -> dict[str, Callable[[httpx.Request], object]]:
        return {
            "/api/auth/me": lambda request: self.user,
            "/api/reports/summary": lambda request: self._summary(),
            "/api/reports/top-products": self._top_products,
            "/api/reports/inventory": lambda request: sorted(self.products, key=lambda p: p["name"]),
            "/api/products": self._products,
            "/api/reports/movements-summary": self._movements_summary,
            "/api/movements": self._movements,
        }

    def _summary(self) -> dict:
        total = sum((p["totalValue"] for p in self.products), Decimal(0))
        groups: dict[str, dict] = {}
        for p in self.products:
            name = p["category"]["name"] if p["category"] else "Sem categoria"
            group = groups.setdefault(name, {"category": name, "quantity": 0, "value": Decimal(0)})
            group["quantity"] += p["quantity"]
            group["value"] += p["totalValue"]
        by_category = sorted(groups.values(), key=lambda g: g["value"], reverse=True)
        for g in by_category:
            g["percentage"] = (Decimal(0) if not total
                               else (g["value"] * 100 / total).quantize(Decimal("0.1"), ROUND_HALF_UP))
        count = len(self.products)
        return {
            "totalProducts": count,
            "totalQuantity": sum(p["quantity"] for p in self.products),
            "totalValue": total,
            "averageValuePerProduct": (total / count).quantize(Decimal("0.01")) if count else Decimal(0),
            "lowStockProducts": sum(p["stockStatus"] == "LOW_STOCK" for p in self.products),
            "outOfStockProducts": sum(p["stockStatus"] == "OUT_OF_STOCK" for p in self.products),
            "categories": len(groups),
            "movementsThisMonth": len(self.movements),
            "valueByCategory": by_category,
        }

    def _top_products(self, request: httpx.Request) -> list[dict]:
        limit = int(request.url.params.get("limit", 5))
        ranked = sorted(self.products, key=lambda p: (-p["totalValue"], p["name"]))[:limit]
        return [{"id": p["id"], "name": p["name"], "category": p["category"]["name"] if p["category"] else None,
                 "quantity": p["quantity"], "totalValue": p["totalValue"]} for p in ranked]

    def _products(self, request: httpx.Request) -> dict:
        status = request.url.params.get("status")
        items = [p for p in sorted(self.products, key=lambda p: p["name"])
                 if status is None or p["stockStatus"] == status]
        return _page(items, request)

    def _in_period(self, request: httpx.Request) -> list[dict]:
        start = date.fromisoformat(request.url.params["from"])
        end = date.fromisoformat(request.url.params["to"])
        return [m for m in self.movements
                if start <= datetime.fromisoformat(m["createdAt"]).astimezone(ZONE).date() <= end]

    def _movements_summary(self, request: httpx.Request) -> dict:
        items = self._in_period(request)
        entries = [m for m in items if m["type"] == "ENTRY"]
        exits = [m for m in items if m["type"] == "EXIT"]
        return {
            "from": request.url.params["from"], "to": request.url.params["to"],
            "entries": {"count": len(entries), "units": sum(m["quantity"] for m in entries)},
            "exits": {"count": len(exits), "units": sum(m["quantity"] for m in exits)},
        }

    def _movements(self, request: httpx.Request) -> dict:
        items = sorted(self._in_period(request), key=lambda m: m["createdAt"], reverse=True)
        return _page(items, request)


@pytest.fixture
def fake_api() -> FakeApi:
    return FakeApi()


@pytest.fixture
def api_client(fake_api: FakeApi) -> TanaApiClient:
    return fake_api.client()
