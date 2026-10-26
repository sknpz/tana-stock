# Tana Stock

<p align="center">
  <img src="logo.svg" alt="Tana Stock logo" width="120">
</p>

<p align="center">
  An inventory management application with a vanilla JavaScript front-end and a Java + Spring Boot REST API backed by PostgreSQL.
</p>

<p align="center">
  <a href="https://sknpz.github.io/tana-stock/"><strong>Live demo</strong></a> ·
  <a href="https://tana-stock-api.onrender.com/swagger-ui.html">API documentation</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/HTML5-E34F26?style=flat-square&logo=html5&logoColor=white" alt="HTML5">
  <img src="https://img.shields.io/badge/CSS3-1572B6?style=flat-square&logo=css3&logoColor=white" alt="CSS3">
  <img src="https://img.shields.io/badge/JavaScript-F7DF1E?style=flat-square&logo=javascript&logoColor=black" alt="JavaScript">
  <img src="https://img.shields.io/badge/Java_21-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring_Boot_4-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 4">
  <img src="https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="MIT License">
  <a href="https://github.com/sknpz/tana-stock/actions/workflows/ci.yml"><img src="https://github.com/sknpz/tana-stock/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
</p>

## 📦 About the Project

**Tana Stock** (*tana*, 棚, is Japanese for "shelf") is an inventory management system designed to make product, supplier, and stock control simple and organized.

The application provides a complete dashboard with inventory statistics, product and supplier management, stock movement history, reports, low-stock alerts, and theme customization.

The project has three parts:

* **Front-end** (`index.html`, `style.css`, `script.js`, `api.js`): vanilla JavaScript that runs in the browser and reads and writes all data through the API.
* **REST API** (`backend/`): Java 21 + Spring Boot, persisting data in PostgreSQL and enforcing the business rules on the server. It also exports the CSV files.
* **PDF report service** (`reports-service/`): Python 3.12 + FastAPI. It renders the PDF reports and has no database of its own: it forwards the user's token to the API and formats what the API returns.

### 🧭 Why two back-ends?

The choice was made piece by piece, on merit:

* **CSV stays in the Java API.** The data, the filters (`ProductSpecifications`), the security and the Testcontainers tests are already there. The file is streamed straight from the database, so a Python service would only add a second wake-up delay and paginate through the API for nothing.
* **PDF goes to a separate Python service.** Layout with charts is where the Python ecosystem is strongest (HTML/CSS templates rendered by WeasyPrint, charts by matplotlib), and moving the rendering out keeps heavy work away from the 512 MB JVM that serves the API.
* **The API remains the only owner of data and rules.** The Python service never touches the database and doesn't know the JWT secret: it forwards the `Authorization` header, and the API decides about invalid tokens, deactivated users and permissions.

```text
Browser (GitHub Pages)
 ├── CSV ──► Java API      GET /api/{products|movements|suppliers}/export.csv   (Bearer token)
 └── PDF ──► Python service GET /reports/{summary|full}.pdf                      (Bearer token)
                └── forwards the same token ──► Java API (data + authorization)
```

## ✨ Features

### 📊 Dashboard

* Total number of registered products
* Total units currently in stock
* Total inventory value
* Low-stock product count
* Most valuable products
* Recent stock movements
* Quick product search

### 📦 Product Management

* Create products
* Edit existing products
* Delete products
* SKU identification
* Product categories
* Unit price management
* Quantity control
* Minimum stock level configuration
* Supplier association
* Product search
* Low-stock filtering
* Out-of-stock filtering

### 🏢 Supplier Management

* Create suppliers
* Edit supplier information
* Delete suppliers
* Store CNPJ
* Phone number
* Email address
* Physical address
* View the number of products associated with each supplier
* Prevent deletion of suppliers that still have linked products

### 🔄 Stock Movements

* Register stock entries
* Register stock exits
* Prevent negative stock quantities
* Automatically record stock changes
* Store movement date and time
* Track previous and new quantities
* Filter movements by entry or exit
* Filter movements by period (from / to dates, in the company time zone)

### 📈 Reports

* Average inventory value per product
* Number of registered categories
* Number of stock movements
* Inventory value grouped by category
* Percentage distribution of inventory value

### 📤 Exports and PDF reports

* **Export CSV** button on Products, Movements and Suppliers, using exactly the filters currently on screen
* CSV files open correctly in Excel in Portuguese (see [Business rules](#business-rules))
* **Gerar PDF** button on Reports, in two formats:
  * **Resumo:** one page with the indicators, value by category (donut chart), the most valuable products and the stock alerts
  * **Completo:** the Resumo plus the full inventory and the stock movements of a chosen period (up to 366 days), with entry and exit totals
* A notice appears while the free-plan servers wake up, and the PDF button is disabled while the file is generated

### ⚠️ Inventory Alerts

* Low-stock warnings
* Out-of-stock warnings
* Configurable minimum stock levels
* Quick stock replenishment actions

### 🔐 Users and Access

* Login with e-mail and password (JWT)
* Two profiles: **Administrador** manages users; **Usuário** operates the inventory
* Create users, change profiles, deactivate/reactivate and reset passwords (administrators)
* Change your own password
* Every stock movement records who registered it
* Deactivating a user blocks access immediately

### 🌙 Interface

* Light theme
* Dark theme
* Persistent theme preference
* Dashboard-style interface
* Clean and organized navigation
* Responsive layout: on phones and tablets the menu opens from a ☰ button and wide tables scroll sideways

---

## 🛠️ Tech Stack

| Technology   | Purpose                                |
| ------------ | -------------------------------------- |
| HTML5        | Application structure                  |
| CSS3         | Layout and visual styling              |
| JavaScript   | Application logic and DOM manipulation |
| Fetch API    | Front-end calls to the REST API        |
| LocalStorage | Theme preference                       |
| Google Fonts | Inter font family                      |
| Java 21 + Spring Boot 4 | REST API (`backend/`) |
| Jackson CSV | Streaming CSV export |
| Python 3.12 + FastAPI | PDF report service (`reports-service/`) |
| WeasyPrint + Jinja2 | HTML/CSS templates rendered to PDF |
| matplotlib | Charts inside the PDF |
| pytest + ruff | Tests and lint of the Python service |
| Spring Data JPA / Hibernate | Data access |
| PostgreSQL   | Relational database (see [Data Model](#-data-model)) |
| Flyway       | Versioned database migrations |
| Spring Security (OAuth2 Resource Server) | JWT authentication and authorization |
| springdoc-openapi | Interactive API documentation (Swagger UI) |
| JUnit 5 + AssertJ + MockMvc | Unit and integration tests |
| Testcontainers | Disposable PostgreSQL for automated tests |
| JaCoCo | Test coverage report and minimum coverage check |
| GitHub Actions | Continuous integration on every push and pull request |

---

## 📁 Project Structure

```text
tana-stock/
├── index.html            # Main application page
├── style.css             # Application styles
├── script.js             # Pages, modals and rendering
├── api.js                # HTTP client for the REST API
├── logo.svg              # Logo and favicon
├── render.yaml           # Render Blueprint (API and report service deployment)
├── .github/workflows/    # CI (GitHub Actions)
├── backend/              # REST API (Java + Spring Boot)
│   ├── Dockerfile        # API image used in production
│   ├── compose.yaml      # Local PostgreSQL (and, optionally, the API in Docker)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/io/github/sknpz/tana/
│       │   │   ├── category/ # One package per feature:
│       │   │   ├── supplier/ #   entity, repository, service,
│       │   │   ├── product/  #   controller and DTOs together
│       │   │   ├── movement/
│       │   │   ├── report/
│       │   │   ├── user/     # Users, profiles, first-admin bootstrap
│       │   │   ├── auth/     # Login, JWT issuing and validation
│       │   │   ├── config/   # Security (routes, CORS) and OpenAPI
│       │   │   └── shared/   # Error handling, CNPJ validation
│       │   └── resources/db/
│       │       ├── migration/ # Flyway migrations (database schema)
│       │       └── seed/      # Sample data for development
│       └── test/          # Unit and integration tests (see Tests below)
├── reports-service/      # PDF report service (Python + FastAPI)
│   ├── Dockerfile        # Service image used in production
│   ├── app/              # Routes, API client, charts, pt-BR formatting, Jinja2 templates
│   ├── tests/            # pytest (mocked API, real PDF rendering)
│   └── requirements*.txt
├── LICENSE               # MIT License
└── README.md             # Project documentation
```

---

## 🚀 Getting Started

The front-end has no dependencies to install, but it needs the API running to load and save data.

### 1. Clone the repository

```bash
git clone https://github.com/sknpz/tana-stock.git
```

### 2. Open the project directory

```bash
cd tana-stock
```

### 3. Run the API

Requirements: **Java 21** and **Docker**. Maven is not required, because the project includes the Maven Wrapper (`mvnw`).

```bash
cd backend
docker compose up -d     # starts PostgreSQL on port 5433
./mvnw spring-boot:run   # starts the API on http://localhost:8080
```

On startup, Flyway creates the tables automatically. To load the sample data:

```bash
docker compose exec -T postgres psql -U tana -d tana < src/main/resources/db/seed/dev_seed.sql
```

The sample data includes two users, both with the password `estoque123`:

| E-mail | Profile |
| --- | --- |
| `admin@tana.local` | Administrador |
| `operador@tana.local` | Usuário |

Interactive documentation: **http://localhost:8080/swagger-ui.html**. Call `POST /api/auth/login`, copy the `accessToken` and paste it into **Authorize** to try the protected endpoints.

| Environment variable | Default | Description |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5433/tana` | JDBC connection URL |
| `DB_USERNAME` / `DB_PASSWORD` | `tana` / `tana` | Database credentials (local only) |
| `DB_POOL_SIZE` | `10` | Maximum database connections |
| `PORT` | `8080` | HTTP port |
| `CORS_ALLOWED_ORIGINS` | Live Server + GitHub Pages | Front-end origins allowed to call the API |
| `JWT_SECRET` | random key per start | Key that signs the tokens (at least 32 characters). Without it, every restart logs everyone out, so always set it in production. |
| `JWT_EXPIRATION` | `8h` | How long a login lasts |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` / `ADMIN_NAME` | empty / empty / `Administrador` | Creates the first administrator on startup, **only if the database has no users yet** |

In production (without the sample data), set `ADMIN_EMAIL` and `ADMIN_PASSWORD` for the first start, log in as that administrator, and create the other users from the **Usuários** page.

Run the tests with `./mvnw verify` (see [Tests](#-tests)).

To run the API in Docker instead, with the same image used in production:

```bash
docker compose --profile api up -d --build   # PostgreSQL + API on http://localhost:8080
```

### 4. Run the PDF report service (optional)

Only needed to generate PDFs; the rest of the application, including the CSV export, works without it. Requirements: **Python 3.12** and the native libraries WeasyPrint uses (Pango and HarfBuzz; on Debian/Ubuntu: `libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz-subset0 fonts-dejavu-core`).

```bash
cd reports-service
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
API_BASE_URL=http://localhost:8080 uvicorn app.main:app --port 8000
```

| Environment variable | Default | Description |
| --- | --- | --- |
| `API_BASE_URL` | `http://localhost:8080` | Address of the Java API (use `http://localhost:8081` if you ran it with `PORT=8081`) |
| `CORS_ALLOWED_ORIGINS` | Live Server + GitHub Pages | Front-end origins allowed to call the service |
| `APP_ZONE_ID` | `America/Sao_Paulo` | Company time zone (dates and times in the PDF) |
| `API_TIMEOUT_SECONDS` | `90` | How long to wait for the API, long enough for it to wake up on the free plan |
| `PORT` | `8000` | HTTP port (used by the Docker image) |

When opened from `localhost`, the front-end calls the service at `http://localhost:8000`; anywhere else, at `https://tana-stock-reports.onrender.com`. To point it somewhere else, define `window.TANA_REPORTS_URL` before `api.js` is loaded, the same way as `window.TANA_API_URL` below.

### 5. Run the front-end

Serve the project folder on port **5500**, for example with the **Live Server** extension for Visual Studio Code, or with:

```bash
python -m http.server 5500
```

Then open **http://localhost:5500** and log in (see the sample users above).

> Opening `index.html` directly from the file system (`file://`) does not work: the browser sends the request with a `null` origin, which the API's CORS configuration rejects. To use another port, add its origin to `CORS_ALLOWED_ORIGINS`.

When opened from `localhost`, the front-end calls the API at `http://localhost:8080`; anywhere else (GitHub Pages), it calls the deployed API at `https://tana-stock-api.onrender.com`. To point it somewhere else, define `window.TANA_API_URL` before `api.js` is loaded:

```html
<script>window.TANA_API_URL = 'https://my-api.example.com';</script>
<script src="api.js"></script>
```

If the API is unreachable, each page shows an error message with a **Tentar novamente** (try again) button instead of empty data.

---

## 🔌 API

Every endpoint except `POST /api/auth/login` requires the header `Authorization: Bearer <token>`.

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/auth/login` | Log in with e-mail and password; returns the token, its expiration and the user |
| `GET` | `/api/auth/me` | Logged-in user |
| `PUT` | `/api/auth/password` | Change your own password (requires the current one) |
| `GET` / `POST` | `/api/users` | List and create users (**ADMIN only**) |
| `GET` / `PUT` | `/api/users/{id}` | View and update name, profile, status, or reset the password (**ADMIN only**) |
| `GET` | `/api/categories` | List categories |
| `POST` / `PUT` / `DELETE` | `/api/categories[/{id}]` | Create, rename, delete |
| `GET` | `/api/suppliers?search=` | List suppliers (search by name, CNPJ or e-mail) |
| `GET` / `POST` / `PUT` / `DELETE` | `/api/suppliers[/{id}]` | Supplier CRUD |
| `GET` | `/api/products?search=&status=&categoryId=&supplierId=&page=&size=&sort=` | Paginated product list with filters |
| `GET` / `POST` / `PUT` / `DELETE` | `/api/products[/{id}]` | Product CRUD (delete is a soft delete) |
| `POST` | `/api/products/{id}/movements` | Register a stock entry (`ENTRY`) or exit (`EXIT`) |
| `GET` | `/api/movements?type=&productId=&from=&to=&page=&size=&sort=` | Paginated movement history; `from`/`to` (`yyyy-MM-dd`, `to` inclusive, company time zone) filter by period |
| `GET` | `/api/products/export.csv?search=&status=&categoryId=&supplierId=` | Products as CSV (`produtos-YYYY-MM-DD.csv`), same filters as the list |
| `GET` | `/api/movements/export.csv?type=&productId=&from=&to=` | Movements as CSV (`movimentacoes-YYYY-MM-DD.csv`), newest first |
| `GET` | `/api/suppliers/export.csv?search=` | Suppliers as CSV (`fornecedores-YYYY-MM-DD.csv`) |
| `GET` | `/api/reports/summary` | Dashboard indicators and value by category |
| `GET` | `/api/reports/top-products?limit=` | Products with the highest stock value (quantity × unit price), 1 to 50 |
| `GET` | `/api/reports/inventory` | All active products, not paginated, ordered by name (used by the PDF service) |
| `GET` | `/api/reports/movements-summary?from=&to=` | Count and units of entries and exits in the period (both dates required) |
| `GET` | `/actuator/health` | Health check used by the hosting platform (public, no details) |

The PDF service (`reports-service/`, port `8000`) has its own, much smaller API. The two reports require the same `Authorization: Bearer <token>`; errors use Problem Details with `application/problem+json`.

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/health` | Public health check (also used by the front-end to wake the service up) |
| `GET` | `/reports/summary.pdf` | One-page summary (`relatorio-resumo-YYYY-MM-DD.pdf`) |
| `GET` | `/reports/full.pdf?from=&to=` | Full report (`relatorio-completo-YYYY-MM-DD-a-YYYY-MM-DD.pdf`). Without dates, uses the current month until today; `from` after `to` or more than 366 days → `400` |

### Business rules

* **Stock only changes through movements.** `PUT /api/products/{id}` ignores quantity. The initial quantity given on creation is recorded as an `ENTRY` movement ("Estoque inicial"), so every unit in stock has a history.
* **Exits can't exceed the current balance.** The API responds with `422 Unprocessable Content`.
* **Concurrent movements are safe.** The product row is locked (`SELECT ... FOR UPDATE`) while a movement is registered, so two simultaneous exits can't read the same balance. [`StockMovementConcurrencyTests`](backend/src/test/java/io/github/sknpz/tana/movement/StockMovementConcurrencyTests.java) fires 20 simultaneous exits of 1 unit at a product with 7 in stock and checks that exactly 7 succeed and the final balance is 0.
* **CNPJ is validated by its check digits**, with or without punctuation, including the alphanumeric format.
* **Missing or expired token → `401`; not enough permission → `403`.** Both use the same error format as the rest of the API.
* **Deactivation and profile changes take effect immediately.** The token carries only the user id; on every request the API checks the user's current status and profile in the database.
* **Passwords are stored with BCrypt.** The login answers "E-mail ou senha inválidos." for both an unknown e-mail and a wrong password, and takes the same time in both cases, so it doesn't reveal which e-mails are registered.
* **There is always an active administrator.** Administrators can't deactivate themselves or remove their own ADMIN profile.
* **Users are deactivated, not deleted**, so the movement history keeps pointing to who registered each movement.
* **CSV files are made for Excel in Portuguese.** They start with a UTF-8 BOM (so accents are read correctly), use `;` as separator and `\r\n` line endings, write numbers with a decimal comma and no thousands separator (`1234,50`, which Excel recognizes as a number) and dates as `dd/MM/yyyy HH:mm` in the company time zone.
* **CSV injection is prevented.** Free-text fields that start with `=`, `+`, `-`, `@`, a tab or `\r` get a `'` in front, so a product named `=HYPERLINK(...)` can't run as a formula when the file is opened ([OWASP CSV Injection](https://owasp.org/www-community/attacks/CSV_Injection)). The files are streamed from the database, with no row limit.
* **Period filters are inclusive and use the company time zone** (`America/Sao_Paulo`): `to` covers the whole last day. `from` after `to` → `400` with the error on the `to` field.
* **The PDF service doesn't repeat the API's rules.** Invalid tokens, deactivated users and permissions are decided by the Java API, and the service returns its `401`/`403`; if the API is down or too slow, the service answers `502`/`504`. The full report shows at most 2,000 movements (the most recent) and says so; the entry and exit totals still cover the whole period.
* **Errors follow [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) (Problem Details)**, with per-field messages on validation errors:

```json
{
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos são inválidos.",
  "instance": "/api/products",
  "errors": { "unitPrice": "O preço unitário não pode ser negativo." }
}
```

---

## 🧪 Tests

```bash
cd backend
./mvnw verify
```

And for the PDF service:

```bash
cd reports-service
pip install -r requirements-dev.txt
pytest -q          # tests
ruff check .       # lint
```

Docker must be running: the integration tests start a temporary PostgreSQL with Testcontainers, so they run against the same database engine as production, including the Flyway migrations and the `CHECK` constraints.

| Kind | What it covers |
| --- | --- |
| **Unit** (plain JUnit) | CNPJ validation (numeric, alphanumeric, punctuation, wrong check digits), stock status, the product balance rule and the first-admin bootstrap |
| **Integration** (MockMvc + Testcontainers) | Every endpoint through the full HTTP stack, including Spring Security: validation errors, conflicts, soft delete, filters and pagination, reports, users and profiles, 401/403, CORS |
| **Concurrency** | 20 simultaneous exits against a product with 7 units: exactly 7 succeed |
| **CSV and period** (MockMvc + Testcontainers) | The three CSV files (BOM, header, formatted values, filters, 401), formula injection, quote and `;` escaping, inclusive period limits, the inventory and movements-summary reports |
| **Python service** (pytest) | pt-BR formatting, token forwarding, 401/403/502 handling, period validation, movement pagination and the 2,000 limit, and PDFs rendered for real (starts with `%PDF`, page count, extracted text) with the Java API mocked |

All integration tests extend [`IntegrationTest`](backend/src/test/java/io/github/sknpz/tana/IntegrationTest.java) and share one Spring context and one database container. Each test creates its own data with unique names instead of relying on an empty database.

`./mvnw verify` also writes the coverage report to `backend/target/site/jacoco/index.html` and **fails if coverage drops below 85% of lines or 70% of branches**.

### Continuous integration

[GitHub Actions](.github/workflows/ci.yml) runs on every push to `main` and on every pull request:

* **API:** `./mvnw verify` on Java 21 (tests + coverage check), with a coverage summary on the run page and the test and coverage reports available as artifacts.
* **Front-end:** syntax check of `api.js` and `script.js`.
* **Report service:** `ruff check` and `pytest -q` on Python 3.12, with the WeasyPrint system libraries installed on the runner.

---

## ☁️ Deployment

| Part | Where | How it is published |
| --- | --- | --- |
| Front-end | [GitHub Pages](https://sknpz.github.io/tana-stock/) | Automatically, on every push to `main` |
| API | [Render](https://tana-stock-api.onrender.com/swagger-ui.html) (free plan, Docker) | [`render.yaml`](render.yaml) Blueprint: builds [`backend/Dockerfile`](backend/Dockerfile) after the CI passes, only when something under `backend/` changes |
| PDF service | Render (free plan, Docker), `https://tana-stock-reports.onrender.com` | Second service in the same [`render.yaml`](render.yaml) Blueprint: builds [`reports-service/Dockerfile`](reports-service/Dockerfile) after the CI passes, only when something under `reports-service/` changes |
| Database | [Supabase](https://supabase.com) (PostgreSQL) | Flyway creates and updates the tables when the API starts |

### First deployment

1. **Supabase:** create a project in the **East US (North Virginia)** region, the same region as the API, so each query doesn't cross the continent. When creating it, turn off **Enable Data API** (the application doesn't use it, and it would be another way into the data). Then, under **Connect → Direct**, choose type **JDBC** and the **Session pooler** method, and copy the host, port `5432` and user `postgres.<project-id>`. The direct connection only works over IPv6, which Render doesn't support, and the transaction pooler (port `6543`) doesn't work with Flyway.
2. **Render:** **New → Blueprint**, pick this repository, and fill in the values the Blueprint asks for:
   * `DB_URL`: `jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require`
   * `DB_USERNAME` / `DB_PASSWORD`: the Supabase pooler user and the database password
   * `ADMIN_EMAIL` / `ADMIN_PASSWORD`: the first administrator (only used while the database has no users)

   `JWT_SECRET` is generated by Render, and the CORS origin is already set to GitHub Pages. The Blueprint also sets a Flyway baseline at version 0, because a new Supabase project's `public` schema already contains Supabase's own objects; Flyway then runs every migration from V1.
3. The same Blueprint also creates **`tana-stock-reports`**, the PDF service, with `API_BASE_URL` and `CORS_ALLOWED_ORIGINS` already set and nothing to fill in. The front-end expects it at `https://tana-stock-reports.onrender.com`. If Render says the name is taken, rename the service in `render.yaml` and change the URL in `api.js` (or define `window.TANA_REPORTS_URL`). Until the service exists, only the PDF button shows "O serviço de relatórios está indisponível no momento"; everything else, including CSV, keeps working.
4. Open the front-end on GitHub Pages, log in as the administrator, change the password and create the other users. `ADMIN_PASSWORD` stays readable in Render's dashboard, so changing the password (or deleting the `ADMIN_*` variables, which are only used while there are no users) is recommended.

### Free plan behavior

* Both services (API and PDF) **sleep after 15 minutes without requests**. The next request wakes them up, which takes about a minute (about 70 seconds measured on the first deploy for the API); meanwhile the page shows a notice instead of looking frozen. The front-end wakes the PDF service when the Reports page opens, and the PDF service waits for the API to wake up (up to `API_TIMEOUT_SECONDS`).
* The full PDF report shows at most the 2,000 most recent movements of the period because of the 512 MB memory limit; the totals still cover the whole period.
* The image is tuned for 512 MB of memory (heap limited to the container, Serial GC, faster JIT startup). Locally, with the same limits, it uses about 300 MB.
* Every table has Row Level Security enabled without policies ([`V3`](backend/src/main/resources/db/migration/V3__enable_row_level_security.sql)), which blocks Supabase's automatic Data API. The API connects as the tables' owner and is not affected.

---

## 💾 Data Storage

| Where | What |
| --- | --- |
| PostgreSQL (through the API) | Categories, suppliers, products and stock movements. See [Data Model](#-data-model). |
| Browser `localStorage` | The theme preference (`tana_config`) and the login session (`tana_session`: token, expiration and user). Logging out removes the session. |

---

## 🗄️ Data Model

Data is stored in a **PostgreSQL** database (hosted on Supabase in production), accessed through the REST API. The schema is versioned with Flyway in [`db/migration`](backend/src/main/resources/db/migration): [`V1`](backend/src/main/resources/db/migration/V1__create_schema.sql) creates the inventory tables, [`V2`](backend/src/main/resources/db/migration/V2__create_app_user.sql) the users, and [`V3`](backend/src/main/resources/db/migration/V3__enable_row_level_security.sql) enables Row Level Security.

```mermaid
erDiagram
    CATEGORY ||--o{ PRODUCT : classifies
    SUPPLIER ||--o{ PRODUCT : supplies
    PRODUCT  ||--o{ STOCK_MOVEMENT : "has history"
    APP_USER ||--o{ STOCK_MOVEMENT : registers

    CATEGORY {
        bigint id PK
        varchar name UK "case-insensitive"
        timestamptz created_at
    }
    SUPPLIER {
        bigint id PK
        varchar name
        char cnpj UK "14 chars, alphanumeric-ready"
        varchar phone
        varchar email
        varchar address
        timestamptz created_at
        timestamptz updated_at
    }
    PRODUCT {
        bigint id PK
        varchar name
        varchar sku UK
        bigint category_id FK
        bigint supplier_id FK
        numeric unit_price ">= 0"
        int quantity ">= 0"
        int min_stock ">= 0"
        boolean active "soft delete"
        timestamptz created_at
        timestamptz updated_at
    }
    STOCK_MOVEMENT {
        bigint id PK
        bigint product_id FK
        varchar type "ENTRY | EXIT"
        int quantity "> 0"
        int previous_quantity
        int new_quantity
        varchar notes
        bigint user_id FK "null before Phase 4"
        timestamptz created_at
    }
    APP_USER {
        bigint id PK
        varchar name
        varchar email UK "case-insensitive"
        varchar password_hash "BCrypt"
        varchar role "ADMIN | USER"
        boolean active
        timestamptz created_at
        timestamptz updated_at
    }
```

### Design decisions

| Decision | Reason |
| --- | --- |
| **Categories in their own table** | Prevents the same category from being typed in different ways ("Tools", "tools", "Tool") and keeps reports consistent. Uniqueness is case-insensitive. |
| **`ON DELETE RESTRICT` on supplier** | A supplier with linked products cannot be deleted. The database enforces this rule, so the UI isn't the only thing protecting it. |
| **Products use soft delete (`active`)** | Stock movement history must keep pointing to the product it belongs to, so products are deactivated instead of deleted. |
| **`CHECK` constraints on stock** | Negative stock, negative prices and movements whose balance doesn't add up (`new = previous ± quantity`) are rejected by the database itself. |
| **`NUMERIC(12,2)` for prices** | Exact decimal arithmetic. Floating-point types cause rounding errors with money. |
| **CNPJ stored without punctuation** | Avoids duplicates such as `11.222.333/0001-81` vs `11222333000181`. The format also accepts the alphanumeric CNPJ issued since July 2026. |
| **Table named `app_user`** | `user` is a reserved word in PostgreSQL. |
| **Indexes on foreign keys** | PostgreSQL does not index foreign keys automatically, and these columns are used in joins and filters. |

Sample data for development is available in [`dev_seed.sql`](backend/src/main/resources/db/seed/dev_seed.sql).

---

## ⚠️ Current Limitations

The current version does not yet include:

* Login rate limiting (repeated wrong passwords aren't throttled yet)
* Refresh tokens: when the session expires (`JWT_EXPIRATION`), the user logs in again
* Password recovery by e-mail (an administrator resets it instead)
* Multi-company support
* Automatic backups
* An always-on server: on the free plan, the first request after 15 idle minutes takes about a minute (see Deployment above)

---

## 🗺️ Roadmap

The project is evolving from a browser-only app into a full-stack application:

* [x] **Phase 0:** Front-end fixes (XSS protection, real dashboard metrics, modal bugs)
* [x] **Phase 1:** Relational data model (PostgreSQL + Flyway migrations)
* [x] **Phase 2:** REST API with Java + Spring Boot
* [x] **Phase 3:** Front-end consuming the API instead of `localStorage`
* [x] **Phase 4:** User authentication with JWT
* [x] **Phase 5:** Automated tests (JUnit) and CI with GitHub Actions
* [x] **Phase 6:** Docker and cloud deployment (Render + Supabase + GitHub Pages)

Ideas for later:

* [x] CSV export and PDF reports
* [ ] Interactive dashboard charts
* [x] Responsive menu and tables for phones and tablets
* [ ] Login rate limiting (throttle repeated wrong passwords)
* [ ] Refresh tokens, so sessions renew without logging in again
* [ ] Password recovery by e-mail

---

## 🎯 Project Goals

Tana Stock was created as a practical inventory management solution while also serving as a project for developing and applying skills involving:

* Front-end development
* JavaScript
* CRUD operations
* Data persistence
* UI/UX design
* Inventory management logic
* Business rules
* Software organization

---

## 📄 License

This project is licensed under the **MIT License**.

See the [LICENSE](LICENSE) file for more information.

---

## 👨‍💻 Author

Developed by **Matheus Sakuno**.

GitHub: [@sknpz](https://github.com/sknpz)

---

<p align="center">
  Made with ☕ and code.
</p>
