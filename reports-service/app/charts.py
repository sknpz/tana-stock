"""Gráficos dos PDFs, gerados com matplotlib como SVG embutido em data URI."""

import base64
import io

import matplotlib
from matplotlib.figure import Figure

matplotlib.use("Agg")

# Usa Figure diretamente, sem pyplot: o pyplot guarda estado global e não é seguro entre as threads do
# servidor. Uma Figure solta não fica registrada em lugar nenhum, então não há o que fechar depois de salvar.

# Tons derivados da cor primária (#4338ca), do mais forte ao mais claro; cinza para "Outras"
PALETTE = ("#4338ca", "#6366f1", "#818cf8", "#a5b4fc", "#c7d2fe")
# Cores extras para o Completo, que lista todas as categorias sem agrupar
EXTRA_PALETTE = ("#0ea5e9", "#14b8a6", "#f59e0b", "#ec4899", "#64748b")
OTHER_COLOR = "#94a3b8"
TRACK_COLOR = "#eef2ff"
SURFACE = "#ffffff"


def color_for(index: int, *, grouped: bool = True) -> str:
    """Cor da categoria pela posição.

    Agrupado (Resumo): as 5 primeiras usam a paleta e o resto entra em "Outras". Sem agrupar (Completo): cada
    categoria ganha a sua cor, reiniciando a sequência só depois de esgotar as 10 cores.
    """
    if not grouped:
        colors = PALETTE + EXTRA_PALETTE
        return colors[index % len(colors)]
    return PALETTE[index] if index < len(PALETTE) else OTHER_COLOR


def _to_data_uri(fig: Figure, *, stretch: bool = False) -> str:
    buffer = io.BytesIO()
    # Sem data nos metadados: o mesmo dado gera sempre o mesmo SVG
    fig.savefig(buffer, format="svg", transparent=True, metadata={"Date": None})
    svg = buffer.getvalue().decode()
    if stretch:
        # Deixa o SVG ocupar exatamente a caixa definida pelo CSS, sem manter a proporção
        svg = svg.replace("<svg ", '<svg preserveAspectRatio="none" ', 1)
    return "data:image/svg+xml;base64," + base64.b64encode(svg.encode()).decode()


def category_donut(rows: list[dict]) -> str | None:
    """Rosca do valor por categoria, na ordem das linhas, com a cor de cada uma (`color`) ou a da posição."""
    values = [float(row["value"]) for row in rows]
    if not values or sum(values) <= 0:
        return None
    fig = Figure(figsize=(1.6, 1.6))
    ax = fig.add_axes((0, 0, 1, 1))
    ax.pie(values, colors=[row.get("color") or color_for(i) for i, row in enumerate(rows)], startangle=90, counterclock=False,
           wedgeprops={"width": 0.36, "edgecolor": SURFACE, "linewidth": 1.5})
    ax.set_aspect("equal")
    return _to_data_uri(fig)


def top_products_bars(rows: list[dict]) -> str | None:
    """Barras horizontais proporcionais ao valor, uma por linha da tabela, sem eixos nem rótulos.

    O SVG é esticado até a altura das linhas da tabela, então cada barra fica alinhada à sua linha.
    """
    if not rows:
        return None
    values = [float(row["totalValue"]) for row in rows]
    top = max(values) or 1.0
    positions = range(len(values))
    fig = Figure(figsize=(3, 0.3 * len(values)))
    ax = fig.add_axes((0, 0, 1, 1))
    ax.barh(positions, [top] * len(values), height=0.38, color=TRACK_COLOR)
    ax.barh(positions, values, height=0.38, color=PALETTE[1])
    ax.set_xlim(0, top)
    ax.set_ylim(len(values) - 0.5, -0.5)
    ax.axis("off")
    return _to_data_uri(fig, stretch=True)
