# Exportação CSV e relatórios em PDF — design

Data: 2026-10-22 · Branch: `feat/exportacao-csv-pdf`

## Intenção

Tirar do roadmap o primeiro item de "Ideas for later": **CSV export and PDF reports**.

O projeto é a peça de portfólio do autor para vagas de estágio. O pedido original era "em Python", e o motivo
declarado é só o portfólio. Por isso as linguagens foram escolhidas por mérito, peça a peça: o CSV fica na API
Java, onde já estão os dados, os filtros e a segurança. O PDF vai para um serviço Python novo, porque relatório
com gráficos e layout é o ponto forte do ecossistema Python, e isolar a renderização tira um trabalho pesado da
JVM de 512 MB que atende a API. Numa entrevista, as duas escolhas precisam ter resposta para "por que assim?".

## Objetivos

1. Qualquer usuário logado (ADMIN ou USER) baixa **CSV** de Produtos, Movimentações e Fornecedores,
   respeitando exatamente o filtro que está na tela.
2. Qualquer usuário logado gera um **PDF** pela página Relatórios, em dois formatos:
   - **Resumo** (1 página): foto do estoque agora.
   - **Completo** (várias páginas): o Resumo + inventário completo + movimentações de um período escolhido.
3. Tudo funciona no sistema publicado (GitHub Pages + Render), com testes no CI.

## Não objetivos

- Exportar usuários (lista administrativa, com e-mails).
- XLSX, agendamento de relatórios, envio por e-mail.
- Gráficos interativos no dashboard (é outro item do roadmap).
- Mudar o formato da listagem paginada existente além do filtro de período.

## Anti-objetivos (falharia mesmo "funcionando")

- Python sem papel real, só para constar no perfil.
- Duplicar no Python regras que são da API Java (validação do JWT, perfis, usuário ativo, cálculo de status).
- CSV que abre quebrado no Excel em português (acentos errados, tudo numa coluna).
- CSV vulnerável a injeção de fórmula.
- Botão que parece travado enquanto o serviço do Render acorda.

## Abordagens consideradas

### CSV

| Abordagem | Avaliação |
| --- | --- |
| **API Java (escolhida)** | Reaproveita `ProductSpecifications`, a segurança e os testes com Testcontainers; gera em fluxo, sem paginar; não cria deploy novo. |
| JavaScript no navegador | Sem backend, mas teria que percorrer todas as páginas da listagem e ficaria sem testes no CI. |
| Serviço Python | Paginaria a API Java e somaria uma segunda espera de servidor acordando; Python não acrescenta nada aqui. |
| `COPY ... TO STDOUT CSV` no PostgreSQL | O mais rápido, mas ignora a formatação da aplicação; exagero para o volume do projeto. |

### PDF

| Abordagem | Avaliação |
| --- | --- |
| **Serviço Python: FastAPI + WeasyPrint + matplotlib (escolhida)** | Layout em HTML/CSS (Jinja2) com o visual do Tana Stock e gráficos bons; isola a renderização da API. Custo: segundo serviço no Render e bibliotecas de sistema (Pango) na imagem. |
| API Java (OpenPDF/OpenHTMLtoPDF + JFreeChart) | Seria a escolha sem o critério de portfólio: uma peça a menos. Mas a renderização divide a JVM de 512 MB com a API. |
| jsPDF no navegador | Layout por coordenadas e sem testes no CI. |
| `@media print` + "Salvar como PDF" | Custo quase zero, mas o resultado depende do navegador. |
| Chrome headless | Não cabe nos 512 MB do plano gratuito. |

O usuário escolheu CSV na API Java e PDF no serviço Python, e pediu os **dois** formatos de PDF.

## Arquitetura

```
Navegador (GitHub Pages)
 ├── CSV ──► API Java  GET /api/{products|movements|suppliers}/export.csv   (Bearer token)
 └── PDF ──► Serviço Python  GET /reports/{summary|full}.pdf                 (Bearer token)
                └── repassa o mesmo token ──► API Java (dados + autorização)
```

O serviço Python **não acessa o banco nem conhece o segredo do JWT**. Ele repassa o header `Authorization`
recebido para a API Java. Assim, a API continua sendo a única dona dos dados e das regras: token inválido,
usuário desativado e falta de permissão são decididos lá. O serviço devolve o mesmo status quando a API
responde 401 ou 403.

## Parte 1 — API Java

### 1.1 Filtro de período nas movimentações

`GET /api/movements` ganha `from` e `to` (opcionais, `LocalDate`, formato `yyyy-MM-dd`):

- São interpretados no fuso da empresa (`app.zone-id`, America/Sao_Paulo): `from` = início do dia,
  `to` = **inclusivo** (internamente, `< início do dia seguinte`).
- `from > to` → `400` no formato Problem Details, com o erro no campo `to`.
- Implementado como `Specification` (vide a armadilha conhecida: parâmetro nulo em JPQL vira `bytea` no
  PostgreSQL), combinando com os filtros `type` e `productId` que já existem.

### 1.2 Endpoints CSV

| Endpoint | Filtros (os mesmos da listagem) | Ordenação |
| --- | --- | --- |
| `GET /api/products/export.csv` | `search`, `status`, `categoryId`, `supplierId` | nome |
| `GET /api/movements/export.csv` | `type`, `productId`, `from`, `to` | data, mais recente primeiro |
| `GET /api/suppliers/export.csv` | `search` | igual à listagem |

Colunas (cabeçalho em português):

- **Produtos:** SKU; Produto; Categoria; Fornecedor; Preço unitário; Quantidade; Estoque mínimo; Valor em estoque; Situação (`OK` / `Estoque baixo` / `Sem estoque`).
- **Movimentações:** Data/hora; Tipo (`Entrada` / `Saída`); Produto; SKU; Quantidade; Saldo anterior; Saldo novo; Observações; Usuário.
- **Fornecedores:** Nome; CNPJ (formatado `11.222.333/0001-81`); Telefone; E-mail; Endereço; Produtos vinculados.

Formato:

- `text/csv; charset=UTF-8`, **BOM UTF-8** no início, separador `;`, fim de linha `\r\n`, aspas quando necessário.
- Números no padrão brasileiro **sem separador de milhar**: decimal com vírgula (`1234,50`), para o Excel
  em português reconhecer como número. Datas em `dd/MM/yyyy HH:mm`, no fuso da empresa.
- **Proteção contra injeção de fórmula** (OWASP CSV Injection): campos de texto que começam com `=`, `+`, `-`,
  `@`, tab ou `\r` recebem um `'` na frente. Vale só para campos de texto livre, não para os números que a
  própria API formata.
- `Content-Disposition: attachment; filename="produtos-2026-10-22.csv"` (data no fuso da empresa).
  O CORS passa a **expor** `Content-Disposition`, mas o front monta o nome sozinho se o header não vier.
- Geração **em fluxo**: o arquivo é escrito enquanto os dados são lidos, sem carregar tudo para depois montar,
  dentro de uma transação somente leitura. Sem paginação e sem limite de linhas.
- Biblioteca: Jackson CSV (`jackson-dataformat-csv`, com a versão gerenciada pelo Spring Boot 4 / Jackson 3).
  Se a versão gerenciada não existir, o builder pode usar Apache Commons CSV, desde que registre a escolha.
- Os endpoints aparecem no Swagger, na tag de cada recurso.

### 1.3 Inventário completo para o PDF

`GET /api/reports/inventory`: lista JSON **não paginada** de todos os produtos ativos, ordenada por nome,
reaproveitando `ProductResponse` (que já traz categoria, fornecedor, preço, quantidade, mínimo e status).
Existe para o serviço Python não precisar percorrer páginas de 100. Qualquer usuário logado pode chamar.

As movimentações do período vêm da listagem paginada com o filtro novo (`size=100`, várias páginas).

### 1.4 Totais de movimentações no período

`GET /api/reports/movements-summary?from=&to=` (mesmas regras de período da seção 1.1) devolve
`{ "entries": { "count", "units" }, "exits": { "count", "units" } }`, somados no banco. Assim os totais do PDF
Completo cobrem o período inteiro, mesmo quando a tabela de movimentações é cortada no limite de 2.000 linhas.

### 1.5 Testes (Java)

Integração com MockMvc + Testcontainers, estendendo `IntegrationTest`, cada teste criando os próprios dados:

- Filtro `from`/`to`: limites inclusivos, fuso da empresa, combinação com `type`, `from > to` → 400.
- Cada CSV: status 200, `Content-Type`, `Content-Disposition`, BOM, cabeçalho, uma linha conhecida com os
  valores formatados, respeito aos filtros, 401 sem token.
- Injeção de fórmula: produto com nome `=HYPERLINK(...)` sai com `'` na frente.
- Aspas e `;` dentro de campos saem escapados.
- `GET /api/reports/inventory`: lista só ativos, ordenada, 401 sem token.
- `GET /api/reports/movements-summary`: soma entradas e saídas do período (limites inclusivos), período vazio
  devolve zeros, `from > to` → 400.
- A cobertura continua acima do limite atual do JaCoCo (85% linhas, 70% ramos).

## Parte 2 — Serviço Python (`reports-service/`)

### 2.1 Pilha

Python 3.12 · FastAPI + Uvicorn · httpx (cliente da API Java) · Jinja2 (templates) · WeasyPrint (HTML/CSS → PDF) ·
matplotlib (gráficos, backend `Agg`, como SVG ou PNG) · pytest · ruff (lint). Dependências fixadas em
`requirements.txt` (e `requirements-dev.txt` para testes e lint).

### 2.2 Endpoints

| Endpoint | Descrição |
| --- | --- |
| `GET /health` | Público. Usado pelo Render e pelo front para "acordar" o serviço. |
| `GET /reports/summary.pdf` | PDF Resumo, de 1 página. |
| `GET /reports/full.pdf?from=YYYY-MM-DD&to=YYYY-MM-DD` | PDF Completo. Sem datas, usa o mês atual até hoje. `from > to` ou intervalo maior que 366 dias → 400. |

- Todos os relatórios exigem `Authorization: Bearer`. Sem o header → 401, sem chamar a API Java.
- O serviço chama `GET /api/auth/me` (que valida o token e traz o nome para o cabeçalho), depois
  `GET /api/reports/summary`, `GET /api/reports/top-products?limit=5`, `GET /api/products?status=...` (alertas,
  paginados) e, no Completo, `GET /api/reports/inventory`, `GET /api/reports/movements-summary` e
  `GET /api/movements?from&to` paginado.
- Se a API Java responder 401 ou 403, o serviço devolve o mesmo status. Se estiver fora do ar ou demorar
  além do timeout, devolve `502`/`504`. Os erros seguem o mesmo formato Problem Details da API Java.
- Resposta: `application/pdf`, `Content-Disposition: attachment; filename="relatorio-resumo-2026-10-22.pdf"` /
  `relatorio-completo-2026-10-01-a-2026-10-22.pdf`.
- CORS: as mesmas origens da API (`CORS_ALLOWED_ORIGINS`, mesmo padrão), métodos `GET`, headers
  `Authorization`, expondo `Content-Disposition`.

Variáveis de ambiente: `API_BASE_URL` (padrão `http://localhost:8080`), `CORS_ALLOWED_ORIGINS`,
`APP_ZONE_ID` (padrão `America/Sao_Paulo`), `PORT` (padrão `8000`), `API_TIMEOUT_SECONDS` (padrão alto o
bastante para a API Java acordar no plano gratuito: 90).

### 2.3 Conteúdo dos PDFs (modelos aprovados no brainstorming)

Identidade: cores do `style.css` (primária `#4338ca`), logo `logo.svg`, fonte sans-serif disponível na
imagem (Inter, se a imagem a tiver; senão DejaVu Sans). Moeda e números em pt-BR (`R$ 10.398,90`). Data e hora
no fuso da empresa. Formatação pt-BR feita por funções próprias, sem depender de locale do sistema.

**Resumo (1 página):**
- Cabeçalho: logo, "Relatório de estoque", data e hora da geração, "gerado por <nome>".
- 4 indicadores: produtos, unidades, valor total, alertas (baixo + sem estoque).
- Valor por categoria: gráfico de rosca + tabela (categoria, quantidade, valor, %). No Resumo, até 5 categorias;
  as demais viram uma linha "Outras", somada, no gráfico e na tabela. No Completo aparecem todas.
- Os 5 produtos mais valiosos: tabela com barra proporcional (gráfico de barras horizontal).
- Alertas: produtos sem estoque e com estoque baixo (produto, SKU, atual, mínimo, situação). Se não houver
  nenhum, uma frase dizendo isso. Se houver muitos, a seção mostra até 15 e diz quantos ficaram de fora,
  para o Resumo continuar em 1 página.
- Rodapé: "Tana Stock" + "Página X de Y".

**Completo (várias páginas):**
- Página 1: o Resumo (sem o limite de 15 alertas; pode quebrar página), com o período no cabeçalho.
- Inventário completo: SKU, produto, categoria, fornecedor, quantidade, preço, total; linha de total geral;
  cabeçalho da tabela repetido a cada página.
- Movimentações do período: totais de entradas e de saídas (quantidade de movimentações e unidades, vindos
  de `GET /api/reports/movements-summary`, portanto do período inteiro) + tabela (data, produto, tipo,
  quantidade, saldo, usuário). Período sem movimentações → frase dizendo isso. Limite de segurança de
  2.000 movimentações: acima disso, o relatório mostra as 2.000 mais recentes e avisa. (O limite foi reduzido de 5.000 para 2.000
  após medir a memória no plano de 512 MB do Render.)

### 2.4 Estrutura

```
reports-service/
├── app/
│   ├── main.py          # FastAPI, rotas, CORS, tratamento de erros
│   ├── config.py        # variáveis de ambiente
│   ├── api_client.py    # cliente httpx da API Java (repasse do token, paginação)
│   ├── charts.py        # gráficos matplotlib
│   ├── formatting.py    # moeda, números e datas em pt-BR
│   ├── reports.py       # monta os dados de cada relatório e renderiza
│   └── templates/       # Jinja2 + CSS do PDF
├── tests/
├── Dockerfile
├── requirements.txt / requirements-dev.txt
└── pyproject.toml       # config do pytest e do ruff
```

### 2.5 Testes (Python)

pytest com a API Java simulada (`httpx.MockTransport` ou `respx`), sem rede:

- Formatação pt-BR (moeda, milhar, datas no fuso).
- Repasse do token; 401 sem header (sem chamar a API); 401/403 da API repassados; API fora → 502.
- Validação de período (`from > to`, mais de 366 dias, padrão = mês atual).
- Paginação das movimentações, inclusive o limite de 2.000.
- PDFs gerados de verdade: o arquivo começa com `%PDF`, o Resumo tem 1 página, e o texto extraído
  (pypdf) contém os valores esperados. Também casos vazios (sem produtos, sem alertas, sem movimentações).

## Parte 3 — Front-end

- `api.js`: função de download que faz `fetch` com o token, transforma a resposta em `Blob` e dispara o
  download com o nome do `Content-Disposition` (ou um nome padrão). Reaproveita o aviso de "servidor
  acordando" (`api.onSlowServer`) e o tratamento de erro (`ApiError`). Endereço do serviço de relatórios:
  `http://localhost:8000` em `localhost`, `https://tana-stock-reports.onrender.com` fora disso, e
  sobrescrevível por `window.TANA_REPORTS_URL`.
- **Produtos, Movimentações, Fornecedores:** botão "Exportar CSV" na barra do topo, usando os filtros atuais
  da tela.
- **Movimentações:** campos de data "De" e "Até" ao lado dos filtros de tipo, ligados a `from`/`to`.
- **Relatórios:** botão "Gerar PDF", que abre um modal no padrão dos modais existentes, com a escolha
  Resumo/Completo e, no Completo, as datas (preenchidas com o 1º dia do mês atual e hoje).
- Ao abrir a página Relatórios, o front chama `GET /health` do serviço Python em segundo plano, para ele já ir
  acordando.
- Se o serviço de relatórios não responder (por exemplo, no intervalo entre o merge e a criação do serviço no
  Render), o modal mostra "O serviço de relatórios está indisponível no momento. Tente novamente em alguns
  minutos." em vez de um erro genérico. O CSV não depende do serviço Python.
- Durante o download, o botão fica desabilitado e mostra "Gerando...". Em caso de erro, a mensagem aparece
  no lugar habitual de erros.
- Funciona no tema claro e escuro e no layout de celular.
- O CI continua checando a sintaxe de `api.js` e `script.js`.

## Parte 4 — Deploy, CI e documentação

- `render.yaml`: segundo serviço `tana-stock-reports` (Docker, plano gratuito, região virginia,
  `healthCheckPath: /health`, `autoDeployTrigger: checksPass`, `buildFilter` em `reports-service/**`),
  com `API_BASE_URL=https://tana-stock-api.onrender.com` e `CORS_ALLOWED_ORIGINS=https://sknpz.github.io`.
- `reports-service/Dockerfile`: `python:3.12-slim`, bibliotecas de sistema do WeasyPrint (Pango), usuário sem
  root, `uvicorn` na `PORT`.
- CI: job novo "Serviço de relatórios" com `ruff check` + `pytest` (com as bibliotecas de sistema do WeasyPrint
  instaladas no runner).
- README: stack (Python, FastAPI, WeasyPrint, matplotlib), estrutura de pastas, como rodar o serviço
  localmente, tabelas de endpoints (CSV, inventário, período, serviço Python), testes, deploy, e o item do
  roadmap marcado. `.superpowers/` vai para o `.gitignore`.
- **A entrega termina na branch `feat/exportacao-csv-pdf` com os commits locais.** Push, pull request e merge
  ficam com o autor, como nas fases anteriores.
- **O deploy real no Render não faz parte da entrega**: criar o segundo serviço pelo Blueprint exige o painel do
  Render, com a conta do autor. A entrega deixa o `render.yaml` pronto e documentado.

## Decisões tomadas pelo builder (não pelo usuário)

- Período padrão do Completo: mês atual até hoje. Limites: 366 dias, 2.000 movimentações e 15 alertas no Resumo. O limite de movimentações foi reduzido de 5.000 para 2.000 após medir a memória no plano de 512 MB do Render (5.000 derrubou o contêiner por falta de memória).
- Endpoint `GET /api/reports/inventory`, em vez de o Python paginar os produtos.
- Validação do token pelo `GET /api/auth/me`, em vez de compartilhar o segredo do JWT com o Python.
- Números no CSV sem separador de milhar.
- Nome do serviço no Render e URL `tana-stock-reports.onrender.com`. Se o nome estiver ocupado no Render, basta
  trocar a URL em `api.js` (ou definir `window.TANA_REPORTS_URL`); o README diz isso.
- Endpoint `GET /api/reports/movements-summary` para os totais do período (adicionado após a revisão do design).
- Resumo com até 5 categorias + "Outras", para caber em 1 página.
- Serviço fora do ar → mensagem amigável no modal.
- agent-browser instalado globalmente via npm para a validação, como o autor pediu.

## Validação da entrega

1. `./mvnw verify` verde (testes + cobertura).
2. `ruff check` + `pytest` verdes no serviço Python.
3. Ambiente local completo (PostgreSQL do compose + seed, API na porta 8081 se a 8080 estiver ocupada, serviço
   Python, front na 5500), testado no navegador com o **agent-browser** (Vercel Labs): login, os três CSVs com
   e sem filtro, o filtro de período, os dois PDFs, o tema escuro e a largura de celular. Os arquivos baixados
   são conferidos (conteúdo do CSV, texto e número de páginas do PDF).
