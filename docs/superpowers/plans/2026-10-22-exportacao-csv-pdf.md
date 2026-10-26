# Exportação CSV e relatórios em PDF — plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exportar CSV de Produtos, Movimentações e Fornecedores pela API Java e gerar PDFs (Resumo e Completo) por um serviço Python novo, com botões no front.

**Architecture:** O CSV é gerado em fluxo por endpoints novos da API Spring Boot, que reaproveitam os filtros das listagens. O PDF vem de um serviço FastAPI (`reports-service/`) que repassa o token do usuário para a API Java, busca os dados e renderiza HTML/CSS (Jinja2) com WeasyPrint e gráficos do matplotlib. O front vanilla ganha uma função de download autenticado, um filtro de período e um modal de PDF.

**Tech Stack:** Java 21 · Spring Boot 4 · Jackson 3 CSV (`tools.jackson.dataformat:jackson-dataformat-csv`, versão gerenciada) · JUnit/MockMvc/Testcontainers · Python 3.12 · FastAPI · httpx · Jinja2 · WeasyPrint 70 · matplotlib · pytest · pypdf · ruff · JS vanilla.

**Spec:** `docs/superpowers/specs/2026-10-22-exportacao-csv-pdf-design.md`. Leia a spec antes de cada tarefa: as colunas, os textos e os limites estão lá.

## Global Constraints

- Idioma: código em inglês, comentários, mensagens, commits e textos de interface em português (padrão do repositório). Commits no formato `tipo(escopo): descrição`, por exemplo `feat(api): ...`, `feat(relatorios): ...`, `feat(front): ...`, terminando com a linha `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Antes de cada commit, rode `git branch --show-current` e confira que está na branch da sua faixa de trabalho (ver "Execução em paralelo").
- Fuso da empresa: `America/Sao_Paulo` (`app.zone-id` no Java, `APP_ZONE_ID` no Python).
- Período: `from`/`to` em `yyyy-MM-dd`, `to` **inclusivo**, interpretado no fuso da empresa; `from > to` → 400 com `errors.to = "A data final não pode ser anterior à inicial."`.
- Erros sempre em Problem Details (`title`, `status`, `detail` e, em validação, `errors`).
- CSV: BOM UTF-8, separador `;`, linha `\r\n`, decimal com vírgula e sem milhar (`1234,50`), datas `dd/MM/yyyy HH:mm`, prefixo `'` em texto livre que comece com `=`, `+`, `-`, `@`, tab ou `\r`.
- PDF: moeda `R$ 10.398,90`, cor primária `#4338ca`, logo `logo.svg`, fonte DejaVu Sans.
- Limites: Resumo com até 15 alertas e 5 categorias + "Outras"; Completo com até 5.000 movimentações; período de até 366 dias.
- Cobertura do JaCoCo continua ≥ 85% de linhas e ≥ 70% de ramos (`./mvnw verify`).
- Porta 8080 pode estar ocupada na máquina local (servidor PHP): para rodar a API manualmente, use `PORT=8081`.

## Review Focus

1. Texto com acento, `;`, aspas ou quebra de linha num nome ou observação: o CSV continua com as colunas certas no Excel em português. Coberto pelo teste `csvEscapesSeparatorsQuotesAndLineBreaks` na Tarefa 2.
2. Movimentação às 23:30 de São Paulo no dia `to` (que já é o dia seguinte em UTC): entra no período; a de 00:00 do dia seguinte não entra. Coberto pelo teste `periodBoundariesFollowCompanyZone` na Tarefa 1.
3. Produto sem categoria e sem fornecedor: o CSV mostra as células vazias e o PDF mostra "Sem categoria" ou "-", sem quebrar. Coberto pelos testes `productWithoutCategoryOrSupplierExportsEmptyCells` (Tarefa 2) e `test_product_without_category_or_supplier` (Tarefa 6).
4. Banco vazio, ou nomes de produto muito longos (120 caracteres): o Resumo continua com exatamente 1 página. Coberto pelos testes `test_summary_empty_database_is_one_page` e `test_summary_long_names_still_one_page` (Tarefa 6).
5. Token expirado no meio do uso: o PDF devolve 401 e o front volta para a tela de login, como já faz na API. Coberto pelo teste `test_api_401_is_passed_through` (Tarefa 7) e pela validação da Tarefa 10.

## Execução em paralelo

Há três faixas de trabalho que não mexem nos mesmos arquivos. Cada uma roda numa worktree e numa branch própria, criadas a partir de `feat/exportacao-csv-pdf`:

| Faixa | Worktree | Branch | Tarefas |
| --- | --- | --- | --- |
| Java | `../tana-wt-java` | `wip/csv-api` | 1 → 2 → 3 → 4 |
| Python | `../tana-wt-python` | `wip/reports-service` | 5 → 6 → 7 |
| Front | `../tana-wt-front` | `wip/front-export` | 8 |

As três faixas se encontram nos contratos HTTP abaixo, não no código. Depois que as três terminam e passam na revisão, o controlador faz o merge das branches em `feat/exportacao-csv-pdf` e segue com as Tarefas 9 e 10 na worktree principal.

### Contratos HTTP compartilhados

**API Java (novos):**

- `GET /api/movements?type=&productId=&from=&to=&page=&size=&sort=` responde a mesma `Page<StockMovementResponse>` de hoje.
- `GET /api/products/export.csv?search=&status=&categoryId=&supplierId=` responde `text/csv;charset=UTF-8` com `Content-Disposition: attachment; filename="produtos-YYYY-MM-DD.csv"`.
- `GET /api/movements/export.csv?type=&productId=&from=&to=` gera `movimentacoes-YYYY-MM-DD.csv`.
- `GET /api/suppliers/export.csv?search=` gera `fornecedores-YYYY-MM-DD.csv`.
- `GET /api/reports/inventory` responde `ProductResponse[]`: id, name, sku, `category{id,name}|null`, `supplier{id,name}|null`, unitPrice, quantity, minStock, totalValue, stockStatus, createdAt, updatedAt. Só produtos ativos, ordenados por nome.
- `GET /api/reports/movements-summary?from=&to=` (os dois obrigatórios) responde `{"from":"2026-10-01","to":"2026-10-22","entries":{"count":3,"units":374},"exits":{"count":3,"units":55}}`.
- O CORS expõe `Content-Disposition`.

**Serviço Python:**

- `GET /health` responde `{"status":"UP"}`.
- `GET /reports/summary.pdf` gera `relatorio-resumo-YYYY-MM-DD.pdf`.
- `GET /reports/full.pdf?from=&to=` gera `relatorio-completo-YYYY-MM-DD-a-YYYY-MM-DD.pdf`.
- Os dois relatórios exigem `Authorization: Bearer <token>`. Erros: 400, 401, 403, 502 ou 504, em `application/problem+json`.

---

### Tarefa 1: Filtro de período nas movimentações (Java)

**Files:**
- Create: `backend/src/main/java/io/github/sknpz/tana/shared/DateRange.java`
- Create: `backend/src/main/java/io/github/sknpz/tana/shared/exception/InvalidFieldException.java`
- Create: `backend/src/main/java/io/github/sknpz/tana/movement/StockMovementSpecifications.java`
- Modify: `backend/src/main/java/io/github/sknpz/tana/shared/exception/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/io/github/sknpz/tana/movement/StockMovementController.java`, `StockMovementService.java`
- Test: `backend/src/test/java/io/github/sknpz/tana/shared/DateRangeTest.java`, `backend/src/test/java/io/github/sknpz/tana/movement/StockMovementApiTests.java`

**Interfaces:**
- Produces:
  - `public record DateRange(LocalDate from, LocalDate to)`, cujo construtor compacto lança `InvalidFieldException("to", "A data final não pode ser anterior à inicial.")` quando os dois existem e `from > to`.
  - `Instant startInclusive(ZoneId zone)`: retorna `null` se `from` for nulo.
  - `Instant endExclusive(ZoneId zone)`: início do dia seguinte a `to`, ou `null`.
  - `public class InvalidFieldException extends RuntimeException` com `String field()`. No handler, vira 400 com título "Dados inválidos", detalhe "Um ou mais campos são inválidos." e `errors: {field: message}`.
  - `static Specification<StockMovement> StockMovementSpecifications.matching(MovementType type, Long productId, Instant start, Instant end)`: cada argumento nulo é ignorado.
  - `StockMovementService.list(MovementType type, Long productId, DateRange period, Pageable pageable)`. O `ZoneId` é injetado com `@Value("${app.zone-id}")`, como no `ReportService`.

- [ ] **Step 1: Write the failing tests**
  - `DateRangeTest` (JUnit puro):
    - `startAndEndUseCompanyZone`: o período `2026-10-01..2026-10-22` em `America/Sao_Paulo` vai de `2026-10-01T03:00:00Z` até `2026-10-23T03:00:00Z`.
    - `nullBoundsStayOpen`: limites nulos devolvem `null`.
    - `fromAfterToIsRejected`: lança `InvalidFieldException` com `field() == "to"`.
  - `StockMovementApiTests`:
    - `periodBoundariesFollowCompanyZone`: cria movimentações com `created_at` ajustado por `JdbcTemplate` para `2026-03-10T02:30:00Z` (09/03 23:30 em São Paulo) e `2026-03-10T03:00:00Z` (10/03 00:00). Filtra `from=2026-03-01&to=2026-03-09&productId=<id>` e espera só a primeira.
    - `periodCombinesWithType`.
    - `fromAfterToReturns400WithFieldError`: espera `$.errors.to == "A data final não pode ser anterior à inicial."`.
    - `invalidDateFormatReturns400` (`from=2026-13-01`).

- [ ] **Step 2: Run tests to verify they fail**
  Run: `cd backend && ./mvnw -q test -Dtest='DateRangeTest,StockMovementApiTests'`
  Expected: erro de compilação ou FAIL nos testes novos.

- [ ] **Step 3: Implement**
  - Extraia a `Specification` de `StockMovementService.list` para `StockMovementSpecifications.matching`.
  - O controller recebe `@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from, to` e monta `new DateRange(from, to)`.
  - Adicione o handler de `InvalidFieldException` no `GlobalExceptionHandler`, reaproveitando `problem(...)`. Não use JPQL com parâmetro nulo: só a `Specification`.

- [ ] **Step 4: Run tests to verify they pass**
  Run: `cd backend && ./mvnw -q test -Dtest='DateRangeTest,StockMovementApiTests'`
  Expected: PASS.

- [ ] **Step 5: Commit**: `feat(api): filtro de período nas movimentações`

---

### Tarefa 2: Infraestrutura de CSV e exportação de produtos (Java)

**Files:**
- Modify: `backend/pom.xml` (dependência `tools.jackson.dataformat:jackson-dataformat-csv`, sem versão)
- Create: `backend/src/main/java/io/github/sknpz/tana/shared/csv/CsvWriter.java`
- Create: `backend/src/main/java/io/github/sknpz/tana/shared/csv/CsvResponses.java`
- Modify: `backend/src/main/java/io/github/sknpz/tana/product/ProductController.java`, `ProductService.java`, `ProductRepository.java` (se precisar de `@EntityGraph` na consulta da exportação)
- Modify: `backend/src/main/java/io/github/sknpz/tana/config/SecurityConfig.java` (`setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION))`)
- Test: `backend/src/test/java/io/github/sknpz/tana/shared/csv/CsvWriterTest.java`, `backend/src/test/java/io/github/sknpz/tana/product/ProductCsvExportTests.java`

**Interfaces:**
- Produces:
  - `public final class CsvWriter implements Closeable, Flushable`:
    - `static CsvWriter open(OutputStream out)` escreve o BOM e configura o Jackson `CsvMapper`/`CsvSchema` com separador `;` e linha `\r\n`.
    - `void row(String... values)` grava uma linha, tratando `null` como célula vazia.
    - `static String text(String value)` aplica a proteção contra fórmula e trata `null` como `""`.
    - `static String decimal(BigDecimal value)` gera `1234,50` (escala 2, sem milhar).
    - `static String dateTime(Instant value, ZoneId zone)` gera `dd/MM/yyyy HH:mm`.
  - `public final class CsvResponses`:
    - `static void prepare(HttpServletResponse response, String baseName, ZoneId zone)` define `Content-Type: text/csv;charset=UTF-8` e `Content-Disposition: attachment; filename="<baseName>-<yyyy-MM-dd>.csv"`, com a data no fuso.
  - `ProductService.exportCsv(String search, StockStatus status, Long categoryId, Long supplierId, OutputStream out)`:
    - `@Transactional(readOnly = true)`, ordenado por nome, usando `ProductSpecifications.matching`.
    - Escreve linha a linha na saída da resposta. Prefira `Stream`; nunca monte o arquivo inteiro numa `String`.
  - `GET /api/products/export.csv` com os mesmos `@RequestParam` da listagem e `HttpServletResponse`.

- [ ] **Step 1: Write the failing tests**
  - `CsvWriterTest`:
    - `startsWithBomAndUsesSemicolonAndCrlf`.
    - `csvEscapesSeparatorsQuotesAndLineBreaks`: os valores `Parafuso; "1/4"`, com quebra de linha, saem entre aspas e com aspas dobradas.
    - `guardsFormulaPrefixes`, parametrizado para `=`, `+`, `-`, `@`, `\t` e `\r`: `text("=1+1")` resulta em `'=1+1`.
    - `decimalUsesCommaWithoutThousands`: `1234.5` vira `1234,50`.
    - `dateTimeUsesZone`: `2026-10-23T00:50:00Z` vira `22/10/2026 21:50`.
  - `ProductCsvExportTests`:
    - `exportsHeaderAndFormattedRow`: confere o cabeçalho exato `SKU;Produto;Categoria;Fornecedor;Preço unitário;Quantidade;Estoque mínimo;Valor em estoque;Situação`, uma linha conhecida com `Estoque baixo`, `Content-Type`, `Content-Disposition` com `produtos-` e a data, e o header `Access-Control-Expose-Headers` numa requisição com `Origin: http://localhost:5500`.
    - `respectsSearchAndStatusFilters`.
    - `productWithoutCategoryOrSupplierExportsEmptyCells`.
    - `formulaInProductNameIsNeutralized`: o nome `=HYPERLINK("x")` sai começando por `'=`.
    - `requiresAuthentication`: espera 401.

- [ ] **Step 2: Run to verify they fail**
  Run: `cd backend && ./mvnw -q test -Dtest='CsvWriterTest,ProductCsvExportTests'`
  Expected: FAIL.

- [ ] **Step 3: Implement** as interfaces acima. Situação: `OK`, `Estoque baixo` ou `Sem estoque`. Categoria e fornecedor nulos viram célula vazia.

- [ ] **Step 4: Run to verify they pass**
  Run: o mesmo comando do Step 2.
  Expected: PASS.

- [ ] **Step 5: Commit**: `feat(api): exportação de produtos em CSV`

---

### Tarefa 3: Exportação de movimentações e fornecedores (Java)

**Files:**
- Modify: `movement/StockMovementController.java`, `movement/StockMovementService.java`, `supplier/SupplierController.java`, `supplier/SupplierService.java`, `shared/validation/Cnpj.java`
- Test: `backend/src/test/java/io/github/sknpz/tana/movement/MovementCsvExportTests.java`, `backend/src/test/java/io/github/sknpz/tana/supplier/SupplierCsvExportTests.java`, e o teste existente de `Cnpj` para o `format`

**Interfaces:**
- Consumes: `CsvWriter`, `CsvResponses` (Tarefa 2); `DateRange`, `StockMovementSpecifications.matching` (Tarefa 1).
- Produces:
  - `StockMovementService.exportCsv(MovementType type, Long productId, DateRange period, OutputStream out)`, ordenado por `createdAt` desc, com o produto e o usuário carregados sem N+1.
  - `SupplierService.exportCsv(String search, OutputStream out)`, que reaproveita `list(search)`.
  - `static String Cnpj.format(String normalized)`: `11222333000181` vira `11.222.333/0001-81`. Valor nulo ou com tamanho diferente de 14 volta como está.

- [ ] **Step 1: Write the failing tests**
  - `MovementCsvExportTests`:
    - `exportsHeaderAndRow`: cabeçalho exato `Data/hora;Tipo;Produto;SKU;Quantidade;Saldo anterior;Saldo novo;Observações;Usuário`; tipo `Entrada`/`Saída`; nome do usuário.
    - `respectsTypeProductAndPeriod`.
    - `fromAfterToReturns400`.
    - `requiresAuthentication`.
  - `SupplierCsvExportTests`:
    - `exportsHeaderAndFormattedCnpj`: cabeçalho exato `Nome;CNPJ;Telefone;E-mail;Endereço;Produtos vinculados`; CNPJ formatado; contagem de produtos ativos.
    - `respectsSearch`.
    - `requiresAuthentication`.
  - Teste de `Cnpj.format`: valor numérico, valor alfanumérico, nulo e tamanho errado.

- [ ] **Step 2: Run to verify they fail**
  Run: `cd backend && ./mvnw -q test -Dtest='MovementCsvExportTests,SupplierCsvExportTests,Cnpj*'`
  Expected: FAIL.

- [ ] **Step 3: Implement**: `GET /api/movements/export.csv` (arquivo `movimentacoes`) e `GET /api/suppliers/export.csv` (arquivo `fornecedores`).

- [ ] **Step 4: Run to verify they pass**
  Run: o mesmo comando do Step 2.
  Expected: PASS.

- [ ] **Step 5: Commit**: `feat(api): exportação de movimentações e fornecedores em CSV`

---

### Tarefa 4: Inventário e totais do período para o PDF (Java)

**Files:**
- Modify: `report/ReportController.java`, `report/ReportService.java`, `report/ReportRepository.java`, `product/ProductService.java`
- Create: `report/MovementPeriodSummaryResponse.java`
- Test: `backend/src/test/java/io/github/sknpz/tana/report/ReportApiTests.java`

**Interfaces:**
- Produces:
  - `ProductService.inventory(): List<ProductResponse>`: ativos, ordenados por nome.
  - `public record MovementPeriodSummaryResponse(LocalDate from, LocalDate to, MovementTotals entries, MovementTotals exits)`, com `public record MovementTotals(long count, long units)` aninhado.
  - `ReportService.movementsSummary(DateRange period)`.
  - Consulta nativa com `count(*) FILTER (WHERE type = 'ENTRY')` e `coalesce(sum(quantity) FILTER (...), 0)`, entre `:start` (inclusivo) e `:end` (exclusivo). Os dois parâmetros são sempre não nulos, porque `from` e `to` são obrigatórios neste endpoint.
  - Rotas: `GET /api/reports/inventory` e `GET /api/reports/movements-summary`.

- [ ] **Step 1: Write the failing tests** (em `ReportApiTests`, comparando antes e depois, como a classe já faz):
  - `inventoryListsOnlyActiveProductsSortedByName`: um produto excluído não aparece.
  - `movementsSummaryCountsEntriesAndExitsInPeriod`: período antigo e isolado, ajustando `created_at` com `JdbcTemplate` para não colidir com outros testes.
  - `movementsSummaryEmptyPeriodReturnsZeros`.
  - `movementsSummaryRequiresFromAndTo`: 400.
  - `movementsSummaryFromAfterToReturns400`.
  - `newReportEndpointsRequireAuthentication`.

- [ ] **Step 2: Run to verify they fail**
  Run: `cd backend && ./mvnw -q test -Dtest=ReportApiTests`
  Expected: FAIL.

- [ ] **Step 3: Implement** as interfaces acima, com `@Operation(summary = ...)` em português, como no restante do controller.

- [ ] **Step 4: Run the whole suite with the coverage gate**
  Run: `cd backend && ./mvnw -q verify`
  Expected: BUILD SUCCESS. A cobertura fica acima de 85%/70%. Se cair, adicione testes. Não baixe o limite.

- [ ] **Step 5: Commit**: `feat(api): inventário completo e totais de movimentações por período`

---

### Tarefa 5: Estrutura do serviço, formatação e cliente da API (Python)

**Files:**
- Create: `reports-service/pyproject.toml` (configuração do pytest com `pythonpath = ["."]` e do ruff com `line-length = 120`), `requirements.txt` e `requirements-dev.txt` (versões fixas com `==`)
- Create: `reports-service/app/__init__.py`, `app/config.py`, `app/formatting.py`, `app/api_client.py`
- Test: `reports-service/tests/test_formatting.py`, `tests/test_api_client.py`

**Ambiente:**
- `cd reports-service && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt`.
- Versões testadas nesta máquina: `fastapi==0.161.1`, `httpx==0.28.1`, `weasyprint==70.0`, `matplotlib==3.11.2`, `pypdf==6.21.2`. Inclua também `uvicorn`, `jinja2`, `tzdata` e, só no dev, `pytest` e `ruff`.

**Interfaces:**
- Produces:
  - `@dataclass(frozen=True) class Settings`, com `api_base_url: str`, `cors_allowed_origins: list[str]`, `zone: ZoneInfo` e `api_timeout_seconds: float`.
  - `load_settings() -> Settings`. Variáveis:
    - `API_BASE_URL`, padrão `http://localhost:8080`.
    - `CORS_ALLOWED_ORIGINS`, padrão `http://localhost:5500,http://127.0.0.1:5500,https://sknpz.github.io`.
    - `APP_ZONE_ID`, padrão `America/Sao_Paulo`.
    - `API_TIMEOUT_SECONDS`, padrão `90`.
  - `formatting.py`, sem depender de locale do sistema:
    - `money(value: Decimal) -> str`: `Decimal("10398.9")` vira `"R$ 10.398,90"`.
    - `integer(value: int) -> str`: `12345` vira `"12.345"`.
    - `percent(value: Decimal) -> str`: `38.9` vira `"38,9%"`.
    - `date_br(d: date) -> str` gera `"22/10/2026"`.
    - `datetime_br(value: datetime, zone: ZoneInfo) -> str` gera `"22/10/2026 21:50"`.
    - `parse_instant(text: str) -> datetime` lê ISO-8601 com `Z`.
  - `api_client.py`:
    - `class ApiError(Exception)`, com `status: int`, `title: str` e `detail: str`.
    - `class TanaApiClient(base_url: str, authorization: str, timeout: float, transport: httpx.BaseTransport | None = None)`. É síncrono: as rotas do FastAPI serão `def`. Repassa o header `Authorization` recebido e lê o JSON com `parse_float=Decimal`.
    - `me() -> dict`
    - `summary() -> dict`
    - `top_products(limit: int) -> list[dict]`
    - `products_by_status(status: str) -> list[dict]`: percorre `/api/products?status=&size=100&page=N` até a última página.
    - `inventory() -> list[dict]`
    - `movements_summary(start: date, end: date) -> dict`
    - `movements(start: date, end: date, limit: int) -> tuple[list[dict], bool]`: percorre `/api/movements?from&to&size=100&sort=createdAt,desc`, para em `limit` e devolve `truncated=True` quando havia mais registros.
  - Mapeamento de erros:
    - 401/403 da API viram `ApiError` com o mesmo status, e `title`/`detail` vindos do Problem Details.
    - Outros status de erro viram 502, "API indisponível", "Não foi possível consultar a API do Tana Stock.".
    - `httpx.TimeoutException` vira 504, "API demorou a responder", "A API do Tana Stock demorou demais para responder. Tente novamente em instantes.".
    - `httpx.TransportError` vira 502.

- [ ] **Step 1: Write the failing tests**
  - `test_formatting.py`: cada exemplo acima, mais `money(Decimal("0"))` resultando em `"R$ 0,00"` e milhões resultando em `"R$ 1.234.567,89"`.
  - `test_api_client.py`, com `httpx.MockTransport`:
    - `test_forwards_authorization_header`.
    - `test_decimal_values_are_parsed_as_decimal`.
    - `test_products_by_status_walks_all_pages`.
    - `test_movements_stops_at_limit_and_flags_truncated`.
    - `test_401_and_403_keep_status_and_detail`.
    - `test_500_becomes_502`.
    - `test_timeout_becomes_504`.
    - `test_connection_error_becomes_502`.

- [ ] **Step 2: Run to verify they fail**
  Run: `cd reports-service && .venv/bin/pytest -q`
  Expected: FAIL (ImportError).

- [ ] **Step 3: Implement** as interfaces acima.

- [ ] **Step 4: Run to verify they pass**
  Run: `cd reports-service && .venv/bin/pytest -q && .venv/bin/ruff check .`
  Expected: PASS, e o ruff sem erros.

- [ ] **Step 5: Commit**: `feat(relatorios): estrutura do serviço Python e cliente da API`. Inclua um `.gitignore` em `reports-service/` com `.venv/`, `__pycache__/`, `.pytest_cache/` e `.ruff_cache/`.

---

### Tarefa 6: Gráficos, templates e renderização dos PDFs (Python)

**Files:**
- Create: `reports-service/app/charts.py`, `app/reports.py`
- Create: `reports-service/app/templates/base.html`, `summary.html`, `full.html`, `_sections.html` (macros compartilhadas), `report.css`, `logo.svg` (cópia de `/logo.svg`)
- Test: `reports-service/tests/test_reports.py`, `tests/conftest.py` (dados de exemplo iguais aos de `backend/src/main/resources/db/seed/dev_seed.sql`, servidos por um `httpx.MockTransport`)

**Interfaces:**
- Consumes: `TanaApiClient` e `formatting.*` (Tarefa 5).
- Produces:
  - Constantes `SUMMARY_MAX_ALERTS = 15`, `SUMMARY_MAX_CATEGORIES = 5` e `FULL_MAX_MOVEMENTS = 5000`.
  - `group_categories(rows: list[dict], limit: int | None) -> list[dict]`: mantém as `limit` primeiras na ordem que vem da API (por valor, decrescente) e soma o resto numa linha `"Outras"` (quantidade, valor e %). Com `None`, devolve todas.
  - `charts.category_donut(rows: list[dict]) -> str` e `charts.top_products_bars(rows: list[dict]) -> str`:
    - Devolvem um data URI `data:image/svg+xml;base64,...`.
    - Usam matplotlib com `matplotlib.use("Agg")` e fecham cada figura.
    - Paleta derivada de `#4338ca`: `#4338ca`, `#6366f1`, `#818cf8`, `#a5b4fc`, `#c7d2fe` e `#94a3b8` para "Outras".
    - Lista vazia devolve `None`, e o template mostra "Sem dados".
  - `build_summary_context(client: TanaApiClient, now: datetime, zone: ZoneInfo, *, full: bool = False) -> dict`.
  - `build_full_context(client: TanaApiClient, now: datetime, zone: ZoneInfo, start: date, end: date) -> dict`.
  - `render_pdf(template: str, context: dict) -> bytes`: Jinja2 com `autoescape=True` e WeasyPrint com `base_url` na pasta de templates.
  - `summary_pdf(client, now, zone) -> bytes` e `full_pdf(client, now, zone, start, end) -> bytes`.
- Conteúdo e textos: seção 2.3 da spec. O cabeçalho traz "Relatório de estoque", a data/hora e "gerado por <nome do /api/auth/me>". O Completo mostra o período `01/10/2026 a 22/10/2026`. O rodapé usa CSS `@page` com `counter(page)` e `counter(pages)`: "Página X de Y". A tabela do inventário repete o cabeçalho (`thead { display: table-header-group }`). Os totais de entradas e saídas vêm de `movements_summary`. Quando `truncated`, aparece o aviso "Mostrando as 5.000 movimentações mais recentes do período.". Categoria nula aparece como "Sem categoria", e fornecedor nulo como "-".

- [ ] **Step 1: Write the failing tests** (gere o PDF de verdade e leia com `pypdf.PdfReader`; o texto extraído é a fonte das asserções):
  - `test_summary_is_one_page_with_seed_values`: 1 página; o texto contém `R$ 10.398,90`, `Cabo flexível 2,5mm`, `Disjuntor bipolar 32A` e `gerado por Administrador`.
  - `test_summary_groups_categories_beyond_five_into_outras`: 8 categorias resultam em `Outras` no texto e continuam em 1 página.
  - `test_summary_caps_alerts_at_fifteen`: com 40 alertas, aparece `e mais 25 produtos` e o PDF tem 1 página.
  - `test_summary_empty_database_is_one_page`: aparecem "Nenhum produto com estoque baixo ou zerado." e "Sem dados".
  - `test_summary_long_names_still_one_page`: nomes com 120 caracteres.
  - `test_full_has_inventory_movements_and_period_totals`: mais de 1 página; contém `01/10/2026 a 22/10/2026` e o total geral `10.398,90`; as unidades de entrada vêm de `movements_summary`, não da soma das linhas.
  - `test_full_truncation_notice`.
  - `test_full_empty_period_message`: "Nenhuma movimentação no período.".
  - `test_product_without_category_or_supplier`.
  - `test_group_categories_sums_outras`: teste unitário puro.
  - `test_html_in_product_name_is_escaped`: o nome `<b>x</b>` aparece literalmente no texto.

- [ ] **Step 2: Run to verify they fail**
  Run: `cd reports-service && .venv/bin/pytest -q tests/test_reports.py`
  Expected: FAIL.

- [ ] **Step 3: Implement**: layout dos modelos aprovados na spec, seção 2.3. A pré-visualização HTML do brainstorming está em `.superpowers/brainstorm/*/content/pdf-layout.html`, só na worktree principal; use-a como referência visual se estiver acessível.

- [ ] **Step 4: Run to verify they pass**
  Run: `cd reports-service && .venv/bin/pytest -q && .venv/bin/ruff check .`
  Expected: PASS. Abra um PDF gerado (salve em `/tmp/` ou no scratchpad) e confira visualmente se está legível e alinhado.

- [ ] **Step 5: Commit**: `feat(relatorios): PDFs de resumo e completo com gráficos`

---

### Tarefa 7: Aplicação FastAPI, erros, CORS e imagem Docker (Python)

**Files:**
- Create: `reports-service/app/main.py`, `reports-service/Dockerfile`, `reports-service/.dockerignore`
- Test: `reports-service/tests/test_main.py`

**Interfaces:**
- Consumes: `load_settings`, `TanaApiClient`, `ApiError` (Tarefa 5); `summary_pdf` e `full_pdf` (Tarefa 6).
- Produces:
  - `create_app(settings: Settings | None = None, transport: httpx.BaseTransport | None = None, clock: Callable[[], datetime] | None = None) -> FastAPI`, mais `app = create_app()` no nível do módulo. O `clock` devolve a hora atual com fuso e existe para os testes.
  - `resolve_period(start: date | None, end: date | None, today: date) -> tuple[date, date]`:
    - Sem datas: do dia 1º do mês de `today` até `today`.
    - Só `start`: até `today`.
    - Só `end`: do dia 1º do mês de `end` até `end`.
    - `start > end` lança `PeriodError("to", "A data final não pode ser anterior à inicial.")`.
    - Mais de 366 dias (contando os dois extremos) lança `PeriodError("to", "O período pode ter no máximo 366 dias.")`.
  - Rotas `def` (síncronas) com os nomes de arquivo do contrato. Os parâmetros de query `from`/`to` usam `Query(alias="from")`.
  - Erros sempre em `application/problem+json`:
    - Sem `Authorization: Bearer ...`: 401 "Não autenticado", "Faça login para continuar.", sem chamar a API.
    - `ApiError`: o status e o texto dele.
    - `PeriodError` e `RequestValidationError` (data malformada): 400 "Dados inválidos", "Um ou mais campos são inválidos.", com `errors`.
  - CORS via `CORSMiddleware`: origens das settings, método `GET`, header `Authorization`, `expose_headers=["Content-Disposition"]`.
  - Dockerfile:
    - Base `python:3.12-slim`.
    - `apt-get install --no-install-recommends libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz-subset0 fonts-dejavu-core`.
    - `pip install --no-cache-dir -r requirements.txt`, rodando com usuário sem root.
    - `CMD` com `uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}` em forma shell, para expandir `PORT`.
    - Comentários em português no mesmo estilo de `backend/Dockerfile`.

- [ ] **Step 1: Write the failing tests** (`fastapi.testclient.TestClient`, com a API Java simulada pela fixture da Tarefa 6):
  - `test_health_is_public`.
  - `test_summary_returns_pdf_with_filename`: confere `content-type`, o `content-disposition` `relatorio-resumo-2026-10-22.pdf` (com `clock` fixo) e que o corpo começa com `%PDF`.
  - `test_full_default_period_is_current_month`: o nome do arquivo é `relatorio-completo-2026-10-01-a-2026-10-22.pdf`.
  - `test_missing_token_returns_401_without_calling_api`.
  - `test_api_401_is_passed_through`.
  - `test_api_403_is_passed_through`.
  - `test_api_down_returns_502`.
  - `test_from_after_to_returns_400_with_field_error`.
  - `test_period_longer_than_366_days_returns_400`.
  - `test_malformed_date_returns_400`.
  - `test_cors_allows_front_origin_and_exposes_content_disposition`.
  - Testes unitários de `resolve_period` para cada regra acima.

- [ ] **Step 2: Run to verify they fail**
  Run: `cd reports-service && .venv/bin/pytest -q tests/test_main.py`
  Expected: FAIL.

- [ ] **Step 3: Implement** as interfaces acima.

- [ ] **Step 4: Verify**
  - Run: `cd reports-service && .venv/bin/pytest -q && .venv/bin/ruff check .`
    Expected: PASS.
  - Run: `docker build -t tana-reports reports-service && docker run --rm -d -p 8009:8000 --name tana-reports-check tana-reports && sleep 5 && curl -s localhost:8009/health; docker rm -f tana-reports-check`
    Expected: `{"status":"UP"}`.

- [ ] **Step 5: Commit**: `feat(relatorios): API FastAPI e imagem Docker do serviço de relatórios`

---

### Tarefa 8: Front-end — downloads, filtro de período e modal de PDF

**Files:**
- Modify: `api.js`, `script.js`, `index.html` (modal `#pdfModal`), `style.css`

**Interfaces:**
- Consumes: os contratos HTTP acima. Esta tarefa não depende do código das outras faixas.
- Produces, em `api.js`:
  - `REPORTS_BASE_URL`: `window.TANA_REPORTS_URL`, ou `http://localhost:8000` em `LOCAL_HOSTS`, ou `https://tana-stock-reports.onrender.com`.
  - Função interna `download(baseUrl, path, fallbackName, unreachableMessage)`:
    - Usa `fetch` com o token, passando por `trackRequest` (aviso de servidor acordando).
    - Erro de rede vira `ApiError(unreachableMessage, 0)`.
    - Resposta de erro segue o mesmo tratamento de `request()`, inclusive o 401 que encerra a sessão. Extraia a parte comum em vez de duplicar.
    - Sucesso: `Blob`, nome tirado de `Content-Disposition` (`filename="..."`) ou `fallbackName`, link `<a download>` temporário e `URL.revokeObjectURL`.
  - Métodos públicos:
    - `api.products.exportCsv(params)`, `api.movements.exportCsv(params)` e `api.suppliers.exportCsv(search)`.
    - `api.reports.downloadPdf(kind, { from, to })`, com `kind` `'summary'` ou `'full'` e a mensagem de indisponível "O serviço de relatórios está indisponível no momento. Tente novamente em alguns minutos.".
    - `api.reports.wakeUp()`: `fetch` de `/health`, fora do `trackRequest`, ignorando erros.

**Comportamento em `script.js`:**
- `movementFilters` passa a ser `{ type: '', from: '', to: '', page: 0 }`, e `resetFilters` é atualizado.
- Movimentações:
  - Ganha `.top-actions` com o botão "⬇️ Exportar CSV" (`#exportMovementsBtn`).
  - Os filtros ganham dois `<input type="date">`, `#movementsFrom` e `#movementsTo`, com rótulos "De" e "Até". O `change` volta para a página 0 e recarrega a tabela.
  - O CSV usa `{ type, from, to }`.
- Produtos e Fornecedores: botão `btn btn-secondary`, "⬇️ Exportar CSV" (`#exportProductsBtn` e `#exportSuppliersBtn`), antes do botão primário. Usam `{ search, status }` e a busca, respectivamente.
- Helper `runDownload(button, action)`: desabilita o botão, troca o texto por "Gerando..." e restaura no `finally`. Os erros vão para `reportError(error)`. Quando houver `error.fieldErrors`, a mensagem do primeiro campo tem prioridade sobre `error.message`. Aplique a mesma regra no estado de erro de `refreshTable`.
- Relatórios:
  - Ganha `.top-actions` com "📄 Gerar PDF" (`#openPdfModalBtn`) e chama `api.reports.wakeUp()` ao renderizar.
  - O `#pdfModal` segue o padrão de `#passwordModal`:
    - Rádios `kind` "Resumo (1 página)" (marcado) e "Completo (inventário e movimentações do período)".
    - Grupo de datas `from`/`to`, visível só no Completo. O padrão é o dia 1º do mês atual até hoje, na data **local** (não use `toISOString`).
    - Botões "Cancelar" e "Gerar PDF".
    - O botão de enviar fica desabilitado e mostra "Gerando..." durante o download. Erros vão para `showFormError(form, error)`. O modal fecha no sucesso.
- Estilos: os inputs de data seguem o visual dos filtros e funcionam nos temas claro e escuro e na largura de celular (`@media (max-width: 1024px)` existente).

- [ ] **Step 1: Implement** o que está descrito acima.
- [ ] **Step 2: Verify syntax**
  Run: `node --check api.js && node --check script.js`
  Expected: nenhuma saída.
- [ ] **Step 3: Verify in a browser with fake backends**
  - Suba um servidor estático na porta 5500.
  - Use o agent-browser (instale com `npm i -g agent-browser && agent-browser install` se `which agent-browser` falhar). Intercepte a API ou faça uma verificação visual mínima: os botões aparecem, o modal abre e fecha, as datas aparecem só no Completo, e o tema escuro e a largura de 390 px ficam corretos.
  - A validação ponta a ponta de verdade é a Tarefa 10.
- [ ] **Step 4: Commit**: `feat(front): exportação CSV, filtro de período e modal de PDF`

---

### Tarefa 9: CI, Render, README e .gitignore (worktree principal, depois do merge das faixas)

**Files:**
- Modify: `.github/workflows/ci.yml`, `render.yaml`, `README.md`
- Create: `.gitignore` (raiz): `.superpowers/`, `__pycache__/`, `.venv/`, `.pytest_cache/`, `.ruff_cache/`

**Conteúdo:**
- CI: job `reports-service`, nome "Serviço de relatórios (testes e lint)", `timeout-minutes: 10`, `defaults.run.working-directory: reports-service`.
  - Passos: `actions/checkout@v7`; `actions/setup-python@v6` com `python-version: '3.12'` e `cache: pip`; `sudo apt-get install -y libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz-subset0 fonts-dejavu-core`; `pip install -r requirements-dev.txt`; `ruff check .`; `pytest -q`.
  - Comentários em português, no estilo do arquivo.
- `render.yaml`: segundo serviço `tana-stock-reports`.
  - `runtime: docker`, `plan: free`, `region: virginia`, `branch: main`.
  - `dockerContext: ./reports-service`, `dockerfilePath: ./reports-service/Dockerfile`, `healthCheckPath: /health`, `autoDeployTrigger: checksPass`, `buildFilter.paths: [reports-service/**]`.
  - `envVars`: `API_BASE_URL=https://tana-stock-api.onrender.com` e `CORS_ALLOWED_ORIGINS=https://sknpz.github.io`.
- README, em inglês como o arquivo atual:
  - Features: seção "Exports and PDF reports".
  - Tech Stack: Python 3.12, FastAPI, WeasyPrint, matplotlib, pytest.
  - Árvore do projeto: `reports-service/`.
  - Getting Started: passo para rodar o serviço com `venv` + `uvicorn app.main:app --port 8000`, com `API_BASE_URL` e `window.TANA_REPORTS_URL`.
  - Tabela de endpoints: os CSVs, `from`/`to`, inventory, movements-summary e as rotas do serviço Python.
  - Business rules: CSV para o Excel em português e injeção de fórmula.
  - Tests: pytest.
  - CI: o job novo.
  - Deployment: o segundo serviço no Blueprint, a URL e como trocá-la se o nome estiver ocupado.
  - Roadmap: `[x] CSV export and PDF reports`.
  - Diagrama ou frase explicando por que o PDF é um serviço separado e o CSV fica na API (seção Approaches da spec).

- [ ] **Step 1: Implement** o que está descrito acima.
- [ ] **Step 2: Verify**
  Run: `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in ('.github/workflows/ci.yml','render.yaml')]" && git check-ignore .superpowers`
  Expected: nenhum erro, e o `git check-ignore` imprime `.superpowers`.
- [ ] **Step 3: Commit**: `docs: documenta a exportação CSV e o serviço de relatórios em PDF` (e `ci: ...` separado para o workflow e o render.yaml)

---

### Tarefa 10: Validação ponta a ponta (controlador)

- [ ] **Step 1: Run the test suites**: `cd backend && ./mvnw verify` e `cd reports-service && .venv/bin/pytest -q && .venv/bin/ruff check .`. Os dois têm que passar.
- [ ] **Step 2: Start the local environment**
  - PostgreSQL: `docker compose up -d` em `backend/`, com o seed carregado num banco recém-criado.
  - API: `PORT=8081 ./mvnw spring-boot:run`, ou 8080 se estiver livre.
  - Serviço Python: `API_BASE_URL=http://localhost:<porta> .venv/bin/uvicorn app.main:app --port 8000`.
  - Front: estático na porta 5500. Se a API não estiver na 8080, defina `window.TANA_API_URL` por um `index` local temporário, sem commit.
- [ ] **Step 3: Exercise the app with agent-browser**
  - Login como `admin@tana.local` / `estoque123`.
  - Os três CSVs, com e sem filtro.
  - O filtro de período nas Movimentações, incluindo `De > Até` (mensagem de erro).
  - O PDF Resumo e o PDF Completo.
  - O serviço Python desligado, que deve mostrar a mensagem de indisponível.
  - O tema escuro e a largura de 390 px.
- [ ] **Step 4: Inspect the downloaded files**
  - CSV: BOM, `;` e valores do seed.
  - PDF, com `pypdf`: número de páginas e textos esperados.
  - Anexe as capturas de tela e o resumo ao relatório final.
- [ ] **Step 5: Final review**: rode a revisão de branch inteira (`superpowers:requesting-code-review`), corrija o que ela encontrar e repita os passos 1 a 4 no que foi afetado.
