import io
import re
from datetime import date
from decimal import Decimal

from conftest import NOW, ZONE
from pypdf import PdfReader

from app import charts, reports

PERIOD = (date(2026, 10, 1), date(2026, 10, 22))


def read_pdf(data: bytes) -> tuple[int, str]:
    assert data.startswith(b"%PDF")
    reader = PdfReader(io.BytesIO(data))
    return len(reader.pages), "\n".join(page.extract_text() for page in reader.pages)


def flat(text: str) -> str:
    return " ".join(text.split())


def summary(fake_api) -> tuple[int, str]:
    return read_pdf(reports.summary_pdf(fake_api.client(), NOW, ZONE))


def full(fake_api, start=PERIOD[0], end=PERIOD[1]) -> tuple[int, str]:
    return read_pdf(reports.full_pdf(fake_api.client(), NOW, ZONE, start, end))


def many_products(fake_api, count, *, categories=1, name_length=0, quantity=0):
    """Produtos sem estoque (por padrão), espalhados por `categories` categorias de valores diferentes."""
    products = []
    for i in range(1, count + 1):
        name = f"Produto {i:02d} ".ljust(name_length, "x") if name_length else f"Produto {i:02d}"
        category_name = f"Categoria {(i - 1) % categories + 1}".ljust(name_length, "y")
        category = {"id": (i - 1) % categories + 1, "name": category_name}
        products.append(fake_api.make_product(i, name, f"SKU-{i:03d}", category, None,
                                              Decimal(1000 - i), quantity, 5))
    return products


def test_summary_is_one_page_with_seed_values(fake_api):
    pages, text = summary(fake_api)

    assert pages == 1
    assert "R$ 10.398,90" in text
    assert "Cabo flexível 2,5mm" in text
    assert "Disjuntor bipolar 32A" in text
    assert "gerado por Administrador" in text
    assert "22/10/2026 21:50" in text
    assert "Página 1 de 1" in text


def test_summary_groups_categories_beyond_five_into_outras(fake_api):
    fake_api.products = many_products(fake_api, 8, categories=8, quantity=10)

    pages, text = summary(fake_api)

    assert pages == 1
    assert "Outras" in text
    assert "Categoria 5" in text
    assert "Categoria 6" not in text


def test_summary_caps_alerts_at_fifteen(fake_api):
    fake_api.products = many_products(fake_api, 40)

    pages, text = summary(fake_api)

    assert pages == 1
    assert "e mais 25 produtos" in flat(text)
    assert "SKU-015" in text
    assert "SKU-016" not in text


def test_summary_empty_database_is_one_page(fake_api):
    fake_api.products = []
    fake_api.movements = []

    pages, text = summary(fake_api)

    assert pages == 1
    assert "Nenhum produto com estoque baixo ou zerado." in flat(text)
    assert "Sem dados" in text
    assert "R$ 0,00" in text


def test_summary_long_names_still_one_page(fake_api):
    # Pior caso junto: nomes de 120 caracteres, 8 categorias e 40 alertas
    fake_api.products = many_products(fake_api, 40, categories=8, name_length=120, quantity=3)

    pages, _ = summary(fake_api)

    assert pages == 1


def test_full_has_inventory_movements_and_period_totals(fake_api):
    fake_api.routes["/api/reports/movements-summary"] = {
        "from": "2026-10-01", "to": "2026-10-22",
        "entries": {"count": 7, "units": 9999}, "exits": {"count": 3, "units": 55},
    }

    pages, text = full(fake_api)

    assert pages > 1
    assert "01/10/2026 a 22/10/2026" in text
    assert "10.398,90" in text
    assert "9.999" in text  # vem do resumo do período, não da soma das linhas (288)
    assert "Elétrica Oeste Atacado" in flat(text)
    assert "TOO-001" in text
    assert "21/10/2026 21:50" in text
    assert "Obra Residencial" not in text  # observação não faz parte da tabela
    assert "Operador" in text
    assert "Página 1 de" in text


def test_full_truncation_notice(fake_api, monkeypatch):
    monkeypatch.setattr(reports, "FULL_MAX_MOVEMENTS", 3)

    _, text = full(fake_api)

    assert "Mostrando as 3 movimentações mais recentes do período." in flat(text)


def test_full_has_no_truncation_notice_when_everything_fits(fake_api):
    _, text = full(fake_api)

    assert "mais recentes do período" not in flat(text)


def test_full_empty_period_message(fake_api):
    _, text = full(fake_api, date(2026, 1, 1), date(2026, 1, 31))

    assert "Nenhuma movimentação no período." in flat(text)


def test_product_without_category_or_supplier(fake_api):
    fake_api.products.append(fake_api.make_product(99, "Produto avulso", "AVU-001", None, None, "10.00", 1, 0))

    _, text = full(fake_api)

    assert re.search(r"AVU-001\s+Produto avulso\s+Sem categoria\s+-\s", text)


def test_group_categories_sums_outras():
    rows = [{"category": f"C{i}", "quantity": i, "value": Decimal(100 - i), "percentage": Decimal("10.5")}
            for i in range(1, 9)]

    grouped = reports.group_categories(rows, 5)

    assert [r["category"] for r in grouped] == ["C1", "C2", "C3", "C4", "C5", "Outras"]
    assert grouped[-1]["quantity"] == 6 + 7 + 8
    assert grouped[-1]["value"] == Decimal(94 + 93 + 92)
    assert grouped[-1]["percentage"] == Decimal("31.5")
    assert reports.group_categories(rows, None) == rows
    assert reports.group_categories(rows[:5], 5) == rows[:5]


def test_html_in_product_name_is_escaped(fake_api):
    fake_api.products[3]["name"] = "<b>x</b>"  # Disjuntor: sem estoque, entra nos alertas

    _, text = summary(fake_api)

    assert "<b>x</b>" in text


def test_charts_return_svg_data_uri_or_none_when_empty():
    rows = [{"category": "A", "quantity": 1, "value": Decimal(10), "percentage": Decimal(100)}]
    top = [{"id": 1, "name": "A", "category": None, "quantity": 1, "totalValue": Decimal(10)}]

    assert charts.category_donut(rows).startswith("data:image/svg+xml;base64,")
    assert charts.top_products_bars(top).startswith("data:image/svg+xml;base64,")
    assert charts.category_donut([]) is None
    assert charts.top_products_bars([]) is None


def test_limits():
    assert (reports.SUMMARY_MAX_ALERTS, reports.SUMMARY_MAX_CATEGORIES, reports.FULL_MAX_MOVEMENTS) == (15, 5, 5000)


def test_full_category_swatches_match_their_own_slice(fake_api):
    fake_api.products = many_products(fake_api, 8, categories=8, quantity=10)

    context = reports.build_summary_context(fake_api.client(), NOW, ZONE, full=True)
    colors = [row["color"] for row in context["categories"]]

    assert len(context["categories"]) == 8
    assert "Outras" not in [row["category"] for row in context["categories"]]
    assert len(set(colors)) == 8
    assert charts.OTHER_COLOR not in colors


def test_summary_keeps_other_color_for_outras(fake_api):
    fake_api.products = many_products(fake_api, 8, categories=8, quantity=10)

    context = reports.build_summary_context(fake_api.client(), NOW, ZONE)

    assert context["categories"][-1]["category"] == "Outras"
    assert context["categories"][-1]["color"] == charts.OTHER_COLOR


def test_hidden_alerts_singular(fake_api):
    fake_api.products = many_products(fake_api, 16)

    _, text = summary(fake_api)

    assert "e mais 1 produto com alerta" in flat(text)


def test_unknown_enum_values_fall_back_to_raw_value(fake_api):
    fake_api.products[3]["stockStatus"] = "DISCONTINUED"
    fake_api.movements[0]["type"] = "TRANSFER"
    fake_api.routes["/api/products"] = lambda request: {
        "content": [fake_api.products[3]] if request.url.params["status"] == "OUT_OF_STOCK" else [],
        "page": {"size": 100, "number": 0, "totalElements": 1, "totalPages": 1},
    }

    _, text = full(fake_api)

    assert "DISCONTINUED" in text
    assert "TRANSFER" in text
