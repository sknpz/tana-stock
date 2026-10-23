"""Configuração do serviço, lida das variáveis de ambiente."""

import os
from dataclasses import dataclass
from zoneinfo import ZoneInfo

DEFAULT_CORS = "http://localhost:5500,http://127.0.0.1:5500,https://sknpz.github.io"


@dataclass(frozen=True)
class Settings:
    api_base_url: str
    cors_allowed_origins: list[str]
    zone: ZoneInfo
    api_timeout_seconds: float


def load_settings() -> Settings:
    origins = os.environ.get("CORS_ALLOWED_ORIGINS", DEFAULT_CORS)
    return Settings(
        api_base_url=os.environ.get("API_BASE_URL", "http://localhost:8080").rstrip("/"),
        cors_allowed_origins=[o.strip() for o in origins.split(",") if o.strip()],
        zone=ZoneInfo(os.environ.get("APP_ZONE_ID", "America/Sao_Paulo")),
        api_timeout_seconds=float(os.environ.get("API_TIMEOUT_SECONDS", "90")),
    )
