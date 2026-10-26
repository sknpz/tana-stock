"""Aplicação FastAPI do serviço de relatórios: repassa o JWT do usuário para a API Java e devolve PDFs."""

from collections.abc import Callable
from datetime import UTC, date, datetime
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from starlette.exceptions import HTTPException as StarletteHTTPException

from app import reports
from app.api_client import ApiError, TanaApiClient
from app.config import Settings, load_settings

MAX_PERIOD_DAYS = 366
PROBLEM_JSON = "application/problem+json"


class PeriodError(Exception):
    def __init__(self, field: str, message: str):
        super().__init__(message)
        self.field = field
        self.message = message


def resolve_period(start: date | None, end: date | None, today: date) -> tuple[date, date]:
    """Completa o período informado (`end` inclusivo) e valida a ordem e o tamanho máximo."""
    if end is None:
        end = today
    if start is None:
        start = end.replace(day=1)
    if start > end:
        raise PeriodError("to", "A data final não pode ser anterior à inicial.")
    if (end - start).days + 1 > MAX_PERIOD_DAYS:
        raise PeriodError("to", f"O período pode ter no máximo {MAX_PERIOD_DAYS} dias.")
    return start, end


def _problem(status: int, title: str, detail: str, errors: dict | None = None) -> JSONResponse:
    body: dict = {"title": title, "status": status, "detail": detail}
    if errors:
        body["errors"] = errors
    return JSONResponse(body, status_code=status, media_type=PROBLEM_JSON)


def _invalid(errors: dict) -> JSONResponse:
    return _problem(400, "Dados inválidos", "Um ou mais campos são inválidos.", errors)


def _bearer(authorization: str | None = Header(default=None)) -> str:
    """Exige `Authorization: Bearer <token>`; o valor é repassado como veio para a API Java."""
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise ApiError(401, "Não autenticado", "Faça login para continuar.")
    return authorization


def create_app(settings: Settings | None = None, transport: httpx.BaseTransport | None = None,
               clock: Callable[[], datetime] | None = None) -> FastAPI:
    settings = settings or load_settings()
    clock = clock or (lambda: datetime.now(UTC))
    app = FastAPI(title="Tana Stock - Relatórios", docs_url=None, redoc_url=None, openapi_url=None)

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_allowed_origins,
        allow_methods=["GET"],
        allow_headers=["Authorization"],
        expose_headers=["Content-Disposition"],
    )

    @app.exception_handler(ApiError)
    def api_error(request: Request, exc: ApiError):
        return _problem(exc.status, exc.title, exc.detail)

    @app.exception_handler(PeriodError)
    def period_error(request: Request, exc: PeriodError):
        return _invalid({exc.field: exc.message})

    @app.exception_handler(RequestValidationError)
    def validation_error(request: Request, exc: RequestValidationError):
        errors = {str(err["loc"][-1]): "Valor inválido." for err in exc.errors()}
        return _invalid(errors)

    @app.exception_handler(StarletteHTTPException)
    def http_error(request: Request, exc: StarletteHTTPException):
        return _problem(exc.status_code, "Erro na requisição", str(exc.detail))

    def new_client(authorization: str) -> TanaApiClient:
        return TanaApiClient(settings.api_base_url, authorization, settings.api_timeout_seconds, transport=transport)

    def pdf(content: bytes, filename: str) -> Response:
        return Response(content, media_type="application/pdf",
                        headers={"Content-Disposition": f'attachment; filename="{filename}"'})

    @app.get("/health")
    def health():
        return {"status": "UP"}

    @app.get("/reports/summary.pdf")
    def summary_report(authorization: Annotated[str, Depends(_bearer)]):
        now = clock()
        with new_client(authorization) as client:
            content = reports.summary_pdf(client, now, settings.zone)
        return pdf(content, f"relatorio-resumo-{now.astimezone(settings.zone).date().isoformat()}.pdf")

    @app.get("/reports/full.pdf")
    def full_report(authorization: Annotated[str, Depends(_bearer)],
                    start: Annotated[date | None, Query(alias="from")] = None,
                    end: Annotated[date | None, Query(alias="to")] = None):
        now = clock()
        start, end = resolve_period(start, end, now.astimezone(settings.zone).date())
        with new_client(authorization) as client:
            content = reports.full_pdf(client, now, settings.zone, start, end)
        return pdf(content, f"relatorio-completo-{start.isoformat()}-a-{end.isoformat()}.pdf")

    return app


app = create_app()
