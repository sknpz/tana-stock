"""Formatação pt-BR sem depender do locale do sistema."""

from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal
from zoneinfo import ZoneInfo


def _group(digits: str) -> str:
    """Separa milhares com ponto: '12345' -> '12.345'."""
    return f"{int(digits):,}".replace(",", ".")


def money(value: Decimal) -> str:
    value = Decimal(value).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    sign = "-" if value < 0 else ""
    whole, cents = f"{abs(value):.2f}".split(".")
    return f"{sign}R$ {_group(whole)},{cents}"


def integer(value: int) -> str:
    return ("-" if value < 0 else "") + _group(str(abs(value)))


def percent(value: Decimal) -> str:
    rounded = Decimal(value).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return f"{rounded:.1f}".replace(".", ",") + "%"


def date_br(d: date) -> str:
    return d.strftime("%d/%m/%Y")


def datetime_br(value: datetime, zone: ZoneInfo) -> str:
    return value.astimezone(zone).strftime("%d/%m/%Y %H:%M")


def parse_instant(text: str) -> datetime:
    """Lê um instante ISO-8601, aceitando o sufixo Z (Python 3.11+)."""
    return datetime.fromisoformat(text)
