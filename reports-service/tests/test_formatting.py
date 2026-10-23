from datetime import UTC, date, datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

from app import formatting


def test_money():
    assert formatting.money(Decimal("10398.9")) == "R$ 10.398,90"
    assert formatting.money(Decimal(0)) == "R$ 0,00"
    assert formatting.money(Decimal("1234567.89")) == "R$ 1.234.567,89"


def test_integer():
    assert formatting.integer(12345) == "12.345"


def test_percent():
    assert formatting.percent(Decimal("38.9")) == "38,9%"


def test_date_br():
    assert formatting.date_br(date(2026, 10, 22)) == "22/10/2026"


def test_datetime_br_converts_to_zone():
    value = datetime(2026, 10, 23, 0, 50, tzinfo=UTC)
    assert formatting.datetime_br(value, ZoneInfo("America/Sao_Paulo")) == "22/10/2026 21:50"


def test_parse_instant_reads_z_suffix():
    parsed = formatting.parse_instant("2026-10-23T00:50:00.123456Z")
    assert parsed.utcoffset().total_seconds() == 0
    assert parsed.hour == 0
