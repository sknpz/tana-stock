"""Monta os dados de cada relatório e renderiza o PDF (Jinja2 + WeasyPrint)."""

from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from jinja2 import Environment, FileSystemLoader
from weasyprint import HTML

from app import charts, formatting
from app.api_client import TanaApiClient

SUMMARY_MAX_ALERTS = 15
SUMMARY_MAX_CATEGORIES = 5
FULL_MAX_MOVEMENTS = 5000

TEMPLATES_DIR = Path(__file__).parent / "templates"

STATUS_LABELS = {"OUT_OF_STOCK": "Sem estoque", "LOW_STOCK": "Baixo"}
MOVEMENT_LABELS = {"ENTRY": "Entrada", "EXIT": "Saída"}


def _amount(value: Decimal) -> str:
    """Valor em reais sem o símbolo, para colunas cujo cabeçalho já diz "(R$)"."""
    return formatting.money(value).replace("R$ ", "")


_env = Environment(loader=FileSystemLoader(TEMPLATES_DIR), autoescape=True, trim_blocks=True, lstrip_blocks=True)
_env.filters.update(money=formatting.money, amount=_amount, integer=formatting.integer, percent=formatting.percent)


def group_categories(rows: list[dict], limit: int | None) -> list[dict]:
    """Mantém as `limit` primeiras categorias (a API já ordena por valor) e soma o resto em "Outras"."""
    if limit is None or len(rows) <= limit:
        return rows
    rest = rows[limit:]
    other = {
        "category": "Outras",
        "quantity": sum(row["quantity"] for row in rest),
        "value": sum((row["value"] for row in rest), Decimal(0)),
        "percentage": sum((row["percentage"] for row in rest), Decimal(0)),
    }
    return [*rows[:limit], other]


def _with_colors(rows: list[dict]) -> list[dict]:
    return [{**row, "color": charts.color_for(i)} for i, row in enumerate(rows)]


def _alert_row(product: dict) -> dict:
    status = product["stockStatus"]
    return {
        "name": product["name"], "sku": product["sku"], "quantity": product["quantity"],
        "min_stock": product["minStock"], "status": STATUS_LABELS[status], "out": status == "OUT_OF_STOCK",
    }


def build_summary_context(client: TanaApiClient, now: datetime, zone: ZoneInfo, *, full: bool = False) -> dict:
    """Dados da página de resumo. Com `full`, mostra todas as categorias e todos os alertas."""
    user = client.me()
    summary = client.summary()
    top = client.top_products(5)
    # Sem estoque primeiro, depois estoque baixo
    alerts = client.products_by_status("OUT_OF_STOCK") + client.products_by_status("LOW_STOCK")
    shown_alerts = alerts if full else alerts[:SUMMARY_MAX_ALERTS]

    by_category = summary["valueByCategory"]
    chart_rows = group_categories(by_category, SUMMARY_MAX_CATEGORIES)
    table_rows = by_category if full else chart_rows

    return {
        "full": full,
        "generated_at": formatting.datetime_br(now, zone),
        "user_name": user["name"],
        "period": None,
        "kpis": {
            "products": summary["totalProducts"],
            "units": summary["totalQuantity"],
            "value": summary["totalValue"],
            "alerts": summary["lowStockProducts"] + summary["outOfStockProducts"],
        },
        "categories": _with_colors(table_rows),
        "category_chart": charts.category_donut(chart_rows),
        "top_products": top,
        "top_chart": charts.top_products_bars(top),
        "alerts": [_alert_row(p) for p in shown_alerts],
        "hidden_alerts": len(alerts) - len(shown_alerts),
    }


def _inventory_row(product: dict) -> dict:
    return {
        "sku": product["sku"],
        "name": product["name"],
        "category": product["category"]["name"] if product["category"] else "Sem categoria",
        "supplier": product["supplier"]["name"] if product["supplier"] else "-",
        "quantity": product["quantity"],
        "unit_price": product["unitPrice"],
        "total": product["totalValue"],
    }


def _movement_row(movement: dict, zone: ZoneInfo) -> dict:
    return {
        "date": formatting.datetime_br(formatting.parse_instant(movement["createdAt"]), zone),
        "product": movement["product"]["name"],
        "type": MOVEMENT_LABELS[movement["type"]],
        "entry": movement["type"] == "ENTRY",
        "quantity": movement["quantity"],
        "balance": movement["newQuantity"],
        "user": movement["user"]["name"] if movement["user"] else "-",
    }


def build_full_context(client: TanaApiClient, now: datetime, zone: ZoneInfo, start: date, end: date) -> dict:
    """Resumo sem limites + inventário completo + movimentações do período."""
    context = build_summary_context(client, now, zone, full=True)
    inventory = client.inventory()
    totals = client.movements_summary(start, end)
    limit = FULL_MAX_MOVEMENTS
    movements, truncated = client.movements(start, end, limit)
    context.update(
        period=f"{formatting.date_br(start)} a {formatting.date_br(end)}",
        inventory=[_inventory_row(p) for p in inventory],
        inventory_units=sum(p["quantity"] for p in inventory),
        inventory_total=sum((p["totalValue"] for p in inventory), Decimal(0)),
        movement_totals=totals,
        movements=[_movement_row(m, zone) for m in movements],
        truncated=truncated,
        max_movements=limit,
    )
    return context


def render_pdf(template: str, context: dict) -> bytes:
    html = _env.get_template(template).render(report=context)
    return HTML(string=html, base_url=str(TEMPLATES_DIR)).write_pdf()


def summary_pdf(client: TanaApiClient, now: datetime, zone: ZoneInfo) -> bytes:
    return render_pdf("summary.html", build_summary_context(client, now, zone))


def full_pdf(client: TanaApiClient, now: datetime, zone: ZoneInfo, start: date, end: date) -> bytes:
    return render_pdf("full.html", build_full_context(client, now, zone, start, end))
