    // Preferências de interface (tema e moeda). Os dados de estoque vêm da API (ver api.js).
    const CONFIG_KEY = 'tana_config';
    let config = JSON.parse(localStorage.getItem(CONFIG_KEY)) || { theme: 'light', currency: 'BRL' };

    const saveConfig = () => localStorage.setItem(CONFIG_KEY, JSON.stringify(config));

    // Aplicar tema
    if (config.theme === 'dark') {
      document.body.classList.add('dark');
      document.getElementById('themeIcon').textContent = '☀️';
    }

    const PAGE_SIZE = 20;
    const DEFAULT_MIN_STOCK = 10;

    const STOCK_STATUS = {
      OK: { badge: 'success', label: 'OK' },
      LOW_STOCK: { badge: 'warning', label: 'Baixo' },
      OUT_OF_STOCK: { badge: 'danger', label: 'Sem Estoque' }
    };

    // Funções Auxiliares
    const formatMoney = (value) => {
      return new Intl.NumberFormat('pt-BR', {
        style: 'currency',
        currency: config.currency || 'BRL'
      }).format(value);
    };

    const formatDate = (date) => {
      return new Date(date).toLocaleDateString('pt-BR', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      });
    };

    // A API guarda o CNPJ sem pontuação: "11222333000181" → "11.222.333/0001-81" (vale também para o alfanumérico)
    const formatCnpj = (cnpj) => {
      if (!cnpj || cnpj.length !== 14) return cnpj;
      return `${cnpj.slice(0, 2)}.${cnpj.slice(2, 5)}.${cnpj.slice(5, 8)}/${cnpj.slice(8, 12)}-${cnpj.slice(12)}`;
    };

    // Escapa texto digitado pelo usuário antes de inserir via innerHTML (evita XSS)
    const escapeHtml = (value) => {
      return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
    };

    // Espera o usuário parar de digitar antes de consultar a API
    const debounce = (fn, delay = 300) => {
      let timer;
      return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), delay);
      };
    };

    const emptyState = (icon, title, text = '') => `
      <div class="empty-state">
        <div class="empty-icon">${icon}</div>
        <div class="empty-title">${escapeHtml(title)}</div>
        ${text ? `<p>${escapeHtml(text)}</p>` : ''}
      </div>
    `;

    // Em erros de validação (400) a mensagem do primeiro campo é mais útil que a genérica
    const errorMessage = (error) => Object.values(error.fieldErrors || {})[0] || error.message;

    // 401 já leva para a tela de login (api.onUnauthorized); os demais erros são exibidos ao usuário
    const reportError = (error) => {
      if (error.status !== 401) alert(errorMessage(error));
    };

    // Desabilita o botão enquanto o arquivo é gerado e baixado
    async function runDownload(button, action) {
      const label = button.innerHTML;
      button.disabled = true;
      button.textContent = 'Gerando...';
      try {
        await action();
      } catch (error) {
        reportError(error);
      } finally {
        button.disabled = false;
        button.innerHTML = label;
      }
    }

    // Data local no formato yyyy-MM-dd (toISOString usaria UTC e viraria o dia à noite)
    const toInputDate = (date) =>
      `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

    const ROLE_LABELS = { ADMIN: 'Administrador', USER: 'Usuário' };

    const movementBadge = (type, arrow = '') => type === 'ENTRY'
      ? `<span class="badge badge-success">${arrow ? '↗ ' : ''}Entrada</span>`
      : `<span class="badge badge-danger">${arrow ? '↘ ' : ''}Saída</span>`;

    // Paginação das listagens da API, que respondem { content: [...], page: { number, totalPages, totalElements } }
    function paginationHtml(pageInfo, label) {
      if (pageInfo.totalPages <= 1) return '';
      return `
        <div class="pagination">
          <button class="btn btn-secondary" data-page-step="-1" ${pageInfo.number === 0 ? 'disabled' : ''}>← Anterior</button>
          <span>Página ${pageInfo.number + 1} de ${pageInfo.totalPages} · ${pageInfo.totalElements} ${label}</span>
          <button class="btn btn-secondary" data-page-step="1" ${pageInfo.number + 1 >= pageInfo.totalPages ? 'disabled' : ''}>Próxima →</button>
        </div>
      `;
    }

    function bindPagination(container, onStep) {
      container.querySelectorAll('[data-page-step]').forEach(btn => {
        btn.addEventListener('click', () => onStep(Number(btn.dataset.pageStep)));
      });
    }

    // Recarrega só a tabela (busca, filtro ou paginação), descartando respostas que chegarem fora de ordem
    const tableRequests = {};
    async function refreshTable(containerId, load, render) {
      const requestId = (tableRequests[containerId] || 0) + 1;
      tableRequests[containerId] = requestId;
      const isLatest = () => tableRequests[containerId] === requestId && document.getElementById(containerId);

      try {
        const data = await load();
        if (isLatest()) render(data);
      } catch (error) {
        if (isLatest()) {
          document.getElementById(containerId).innerHTML = emptyState('🔌', 'Não foi possível carregar os dados', errorMessage(error));
        }
      }
    }

    // Estado dos filtros de cada página (zerado ao navegar pelo menu)
    let productFilters;
    let supplierSearch;
    let movementFilters;

    function resetFilters() {
      productFilters = { search: '', status: '', page: 0 };
      supplierSearch = '';
      movementFilters = { type: '', from: '', to: '', page: 0 };
    }
    resetFilters();

    // Sistema de Navegação: cada página carrega seus dados da API e depois desenha a tela
    const pages = {
      dashboard: { load: loadDashboard, render: renderDashboard },
      products: { load: loadProducts, render: renderProducts },
      suppliers: { load: loadSuppliers, render: renderSuppliers },
      movements: { load: loadMovements, render: renderMovements },
      reports: { load: loadReports, render: renderReports },
      alerts: { load: loadAlerts, render: renderAlerts },
      users: { load: loadUsers, render: renderUsers }
    };

    let currentPage = 'dashboard';
    let navigationId = 0;

    async function showPage(page, { keepContent = false } = {}) {
      currentPage = page;
      const id = ++navigationId;
      const main = document.getElementById('mainContent');
      if (!keepContent) main.innerHTML = emptyState('⏳', 'Carregando...');

      try {
        const data = await pages[page].load();
        // Se o usuário trocou de página enquanto a API respondia, esta resposta não é mais necessária
        if (id === navigationId) pages[page].render(data);
      } catch (error) {
        // 401: a tela de login já assumiu (api.onUnauthorized), não há página de erro para mostrar
        if (id === navigationId && error.status !== 401) renderPageError(error);
      }
    }

    function navigateTo(page) {
      document.querySelectorAll('.nav-item').forEach(i => i.classList.toggle('active', i.dataset.page === page));
      showPage(page);
    }

    // Depois de salvar, excluir ou movimentar: recarrega a página atual sem piscar o "Carregando..."
    const reloadCurrentPage = () => showPage(currentPage, { keepContent: true });

    function renderPageError(error) {
      console.error(error);
      document.getElementById('mainContent').innerHTML = `
        ${emptyState('🔌', 'Não foi possível carregar os dados', error.message)}
        <div style="text-align: center;">
          <button class="btn btn-primary" id="retryBtn">Tentar novamente</button>
        </div>
      `;
      document.getElementById('retryBtn').addEventListener('click', () => showPage(currentPage));
    }

    // Menu lateral no celular e tablet (abaixo de 1024px): abre por cima do conteúdo
    const sidebar = document.getElementById('sidebar');
    const menuToggle = document.getElementById('menuToggle');
    const sidebarBackdrop = document.getElementById('sidebarBackdrop');

    function setMenuOpen(open) {
      sidebar.classList.toggle('open', open);
      sidebarBackdrop.classList.toggle('open', open);
      menuToggle.setAttribute('aria-expanded', String(open));
    }

    menuToggle.addEventListener('click', () => setMenuOpen(!sidebar.classList.contains('open')));
    sidebarBackdrop.addEventListener('click', () => setMenuOpen(false));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') setMenuOpen(false);
    });

    document.querySelectorAll('.nav-item').forEach(item => {
      item.addEventListener('click', (e) => {
        e.preventDefault();
        setMenuOpen(false);
        resetFilters();
        navigateTo(e.currentTarget.dataset.page);
      });
    });

    // Toggle Tema
    document.getElementById('themeToggle').addEventListener('click', () => {
      document.body.classList.toggle('dark');
      config.theme = document.body.classList.contains('dark') ? 'dark' : 'light';
      document.getElementById('themeIcon').textContent = config.theme === 'dark' ? '☀️' : '🌙';
      saveConfig();
    });

    // Dashboard
    async function loadDashboard() {
      const [summary, topProducts, recentMovements] = await Promise.all([
        api.reports.summary(),
        api.reports.topProducts(5),
        api.movements.list({ size: 5 })
      ]);
      return { summary, topProducts, recentMovements: recentMovements.content };
    }

    function renderDashboard({ summary, topProducts, recentMovements }) {
      // Mesmo critério do card desde a fase 0: estoque baixo inclui os produtos zerados
      const lowStock = summary.lowStockProducts + summary.outOfStockProducts;

      document.getElementById('mainContent').innerHTML = `
        <div class="top-bar">
          <div class="page-title">
            <h2>Dashboard</h2>
            <p>Visão geral do estoque</p>
          </div>
          <div class="top-actions">
            <div class="search-box">
              <span class="search-icon">🔍</span>
              <input type="text" placeholder="Buscar produtos..." id="quickSearch">
            </div>
            <button class="btn btn-primary" id="addProductBtn">
              <span>➕</span> Novo Produto
            </button>
          </div>
        </div>

        <div class="stats-grid">
          <div class="stat-card">
            <div class="stat-header">
              <span class="stat-title">Total de Produtos</span>
              <div class="stat-icon" style="background: rgba(23, 162, 184, 0.1); color: var(--info);">📦</div>
            </div>
            <div class="stat-value">${summary.totalProducts}</div>
            <span class="stat-change">${summary.categories} categoria${summary.categories !== 1 ? 's' : ''}</span>
          </div>

          <div class="stat-card">
            <div class="stat-header">
              <span class="stat-title">Quantidade Total</span>
              <div class="stat-icon" style="background: rgba(40, 167, 69, 0.1); color: var(--success);">📊</div>
            </div>
            <div class="stat-value">${summary.totalQuantity}</div>
            <span class="stat-change">${summary.movementsThisMonth} movimentaç${summary.movementsThisMonth !== 1 ? 'ões' : 'ão'} este mês</span>
          </div>

          <div class="stat-card">
            <div class="stat-header">
              <span class="stat-title">Valor Total</span>
              <div class="stat-icon" style="background: rgba(67, 56, 202, 0.1); color: var(--primary);">💰</div>
            </div>
            <div class="stat-value">${formatMoney(summary.totalValue)}</div>
            <span class="stat-change">Média de ${formatMoney(summary.averageValuePerProduct)} por produto</span>
          </div>

          <div class="stat-card">
            <div class="stat-header">
              <span class="stat-title">Estoque Baixo</span>
              <div class="stat-icon" style="background: rgba(255, 193, 7, 0.1); color: var(--warning);">⚠️</div>
            </div>
            <div class="stat-value">${lowStock}</div>
            <span class="stat-change ${lowStock > 0 ? 'negative' : 'positive'}">${lowStock > 0 ? '⚠️ Atenção necessária' : '✓ Tudo OK'}</span>
          </div>
        </div>

        <div class="dashboard-grid">
          <div class="card">
            <div class="card-header">
              <h3 class="card-title">Produtos Mais Valiosos</h3>
            </div>
            ${topProducts.length > 0 ? `
              <table>
                <thead>
                  <tr>
                    <th>Produto</th>
                    <th>Qtd</th>
                    <th>Valor Total</th>
                  </tr>
                </thead>
                <tbody>
                  ${topProducts.map(p => `
                    <tr>
                      <td>
                        <strong>${escapeHtml(p.name)}</strong><br>
                        <small style="color: var(--text-muted);">${escapeHtml(p.category || 'Sem categoria')}</small>
                      </td>
                      <td>${p.quantity}</td>
                      <td><strong>${formatMoney(p.totalValue)}</strong></td>
                    </tr>
                  `).join('')}
                </tbody>
              </table>
            ` : emptyState('📦', 'Nenhum produto cadastrado')}
          </div>

          <div class="card">
            <div class="card-header">
              <h3 class="card-title">Movimentações Recentes</h3>
            </div>
            ${recentMovements.length > 0 ? `
              <table>
                <thead>
                  <tr>
                    <th>Tipo</th>
                    <th>Produto</th>
                    <th>Qtd</th>
                    <th>Data</th>
                  </tr>
                </thead>
                <tbody>
                  ${recentMovements.map(m => `
                    <tr>
                      <td>${movementBadge(m.type)}</td>
                      <td>${escapeHtml(m.product.name)}</td>
                      <td>${m.quantity}</td>
                      <td><small>${formatDate(m.createdAt)}</small></td>
                    </tr>
                  `).join('')}
                </tbody>
              </table>
            ` : emptyState('🔄', 'Nenhuma movimentação registrada')}
          </div>
        </div>
      `;

      document.getElementById('addProductBtn').addEventListener('click', () => openProductModal());
      document.getElementById('quickSearch').addEventListener('keydown', quickSearch);
    }

    // Produtos
    function loadProducts() {
      return api.products.list({ ...productFilters, size: PAGE_SIZE });
    }
    const refreshProductsTable = () => refreshTable('productsTable', loadProducts, renderProductsTable);

    function renderProducts(productsPage) {
      document.getElementById('mainContent').innerHTML = `
        <div class="top-bar">
          <div class="page-title">
            <h2>Produtos</h2>
            <p>Gerencie seu catálogo de produtos</p>
          </div>
          <div class="top-actions">
            <div class="search-box">
              <span class="search-icon">🔍</span>
              <input type="text" placeholder="Buscar por nome ou SKU..." id="searchProducts" value="${escapeHtml(productFilters.search)}">
            </div>
            <button class="btn btn-secondary" id="exportProductsBtn">⬇️ Exportar CSV</button>
            <button class="btn btn-primary" id="addProductBtn">
              <span>➕</span> Novo Produto
            </button>
          </div>
        </div>

        <div class="filters">
          <button class="filter-btn ${productFilters.status === '' ? 'active' : ''}" data-filter="">Todos</button>
          <button class="filter-btn ${productFilters.status === 'LOW_STOCK' ? 'active' : ''}" data-filter="LOW_STOCK">Estoque Baixo</button>
          <button class="filter-btn ${productFilters.status === 'OUT_OF_STOCK' ? 'active' : ''}" data-filter="OUT_OF_STOCK">Sem Estoque</button>
        </div>

        <div class="card">
          <div id="productsTable"></div>
        </div>
      `;

      renderProductsTable(productsPage);

      document.getElementById('addProductBtn').addEventListener('click', () => openProductModal());
      document.getElementById('exportProductsBtn').addEventListener('click', (e) =>
        runDownload(e.currentTarget, () => api.products.exportCsv({ search: productFilters.search, status: productFilters.status })));
      document.getElementById('searchProducts').addEventListener('input', debounce((e) => {
        productFilters.search = e.target.value.trim();
        productFilters.page = 0;
        refreshProductsTable();
      }));

      document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
          document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
          e.target.classList.add('active');

          productFilters.status = e.target.dataset.filter;
          productFilters.page = 0;
          refreshProductsTable();
        });
      });
    }

    function renderProductsTable(productsPage) {
      const tableEl = document.getElementById('productsTable');
      const list = productsPage.content;
      const pageInfo = productsPage.page;

      // A última página pode ficar vazia depois de uma exclusão: volta para a anterior
      if (list.length === 0 && pageInfo.number > 0) {
        productFilters.page = Math.max(0, pageInfo.totalPages - 1);
        refreshProductsTable();
        return;
      }

      if (list.length === 0) {
        tableEl.innerHTML = emptyState('📦', 'Nenhum produto encontrado', 'Adicione um novo produto para começar');
        return;
      }

      tableEl.innerHTML = `
        <table>
          <thead>
            <tr>
              <th>Produto</th>
              <th>SKU</th>
              <th>Categoria</th>
              <th>Quantidade</th>
              <th>Preço Unit.</th>
              <th>Valor Total</th>
              <th>Status</th>
              <th>Ações</th>
            </tr>
          </thead>
          <tbody>
            ${list.map(p => {
              const status = STOCK_STATUS[p.stockStatus];

              return `
                <tr>
                  <td>
                    <strong>${escapeHtml(p.name)}</strong><br>
                    ${p.supplier ? `<small style="color: var(--text-muted);">Fornecedor: ${escapeHtml(p.supplier.name)}</small>` : ''}
                  </td>
                  <td><span class="badge badge-info">${escapeHtml(p.sku || '-')}</span></td>
                  <td>${escapeHtml(p.category?.name || '-')}</td>
                  <td><strong>${p.quantity}</strong></td>
                  <td>${formatMoney(p.unitPrice)}</td>
                  <td><strong>${formatMoney(p.totalValue)}</strong></td>
                  <td><span class="badge badge-${status.badge}">${status.label}</span></td>
                  <td>
                    <div class="action-btns">
                      <button class="btn-icon" onclick="adjustStock(${p.id}, 'entry')" title="Entrada">➕</button>
                      <button class="btn-icon" onclick="adjustStock(${p.id}, 'exit')" title="Saída">➖</button>
                      <button class="btn-icon" onclick="editProduct(${p.id})" title="Editar">✏️</button>
                      <button class="btn-icon" onclick="deleteProduct(${p.id})" title="Excluir">🗑️</button>
                    </div>
                  </td>
                </tr>
              `;
            }).join('')}
          </tbody>
        </table>
        ${paginationHtml(pageInfo, 'produtos')}
      `;

      bindPagination(tableEl, (step) => {
        productFilters.page += step;
        refreshProductsTable();
      });
    }

    // Fornecedores
    function loadSuppliers() {
      return api.suppliers.list(supplierSearch);
    }

    function renderSuppliers(suppliers) {
      document.getElementById('mainContent').innerHTML = `
        <div class="top-bar">
          <div class="page-title">
            <h2>Fornecedores</h2>
            <p>Gerencie seus fornecedores</p>
          </div>
          <div class="top-actions">
            <div class="search-box">
              <span class="search-icon">🔍</span>
              <input type="text" placeholder="Buscar fornecedores..." id="searchSuppliers" value="${escapeHtml(supplierSearch)}">
            </div>
            <button class="btn btn-secondary" id="exportSuppliersBtn">⬇️ Exportar CSV</button>
            <button class="btn btn-primary" id="addSupplierBtn">
              <span>➕</span> Novo Fornecedor
            </button>
          </div>
        </div>

        <div class="card">
          <div id="suppliersTable"></div>
        </div>
      `;

      renderSuppliersTable(suppliers);

      document.getElementById('addSupplierBtn').addEventListener('click', () => openSupplierModal());
      document.getElementById('exportSuppliersBtn').addEventListener('click', (e) =>
        runDownload(e.currentTarget, () => api.suppliers.exportCsv(supplierSearch)));
      document.getElementById('searchSuppliers').addEventListener('input', debounce((e) => {
        supplierSearch = e.target.value.trim();
        refreshTable('suppliersTable', loadSuppliers, renderSuppliersTable);
      }));
    }

    function renderSuppliersTable(suppliers) {
      const tableEl = document.getElementById('suppliersTable');

      if (suppliers.length === 0) {
        tableEl.innerHTML = emptyState('🏢', 'Nenhum fornecedor encontrado', 'Adicione um novo fornecedor para começar');
        return;
      }

      tableEl.innerHTML = `
        <table>
          <thead>
            <tr>
              <th>Nome</th>
              <th>CNPJ</th>
              <th>Telefone</th>
              <th>E-mail</th>
              <th>Produtos</th>
              <th>Ações</th>
            </tr>
          </thead>
          <tbody>
            ${suppliers.map(s => `
              <tr>
                <td><strong>${escapeHtml(s.name)}</strong></td>
                <td>${escapeHtml(formatCnpj(s.cnpj) || '-')}</td>
                <td>${escapeHtml(s.phone || '-')}</td>
                <td>${escapeHtml(s.email || '-')}</td>
                <td><span class="badge badge-info">${s.activeProducts} produto${s.activeProducts !== 1 ? 's' : ''}</span></td>
                <td>
                  <div class="action-btns">
                    <button class="btn-icon" onclick="editSupplier(${s.id})" title="Editar">✏️</button>
                    <button class="btn-icon" onclick="deleteSupplier(${s.id})" title="Excluir">🗑️</button>
                  </div>
                </td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      `;
    }

    // Movimentações
    function loadMovements() {
      return api.movements.list({ ...movementFilters, size: PAGE_SIZE });
    }
    const refreshMovementsTable = () => refreshTable('movementsTable', loadMovements, renderMovementsTable);

    function renderMovements(movementsPage) {
      document.getElementById('mainContent').innerHTML = `
        <div class="top-bar">
          <div class="page-title">
            <h2>Movimentações</h2>
            <p>Histórico de entradas e saídas</p>
          </div>
          <div class="top-actions">
            <button class="btn btn-secondary" id="exportMovementsBtn">⬇️ Exportar CSV</button>
          </div>
        </div>

        <div class="filters">
          <button class="filter-btn ${movementFilters.type === '' ? 'active' : ''}" data-filter="">Todas</button>
          <button class="filter-btn ${movementFilters.type === 'ENTRY' ? 'active' : ''}" data-filter="ENTRY">Entradas</button>
          <button class="filter-btn ${movementFilters.type === 'EXIT' ? 'active' : ''}" data-filter="EXIT">Saídas</button>
          <label class="date-filter">De
            <input type="date" id="movementsFrom" value="${escapeHtml(movementFilters.from)}">
          </label>
          <label class="date-filter">Até
            <input type="date" id="movementsTo" value="${escapeHtml(movementFilters.to)}">
          </label>
        </div>

        <div class="card">
          <div id="movementsTable"></div>
        </div>
      `;

      renderMovementsTable(movementsPage);

      document.getElementById('exportMovementsBtn').addEventListener('click', (e) =>
        runDownload(e.currentTarget, () => api.movements.exportCsv({
          type: movementFilters.type, from: movementFilters.from, to: movementFilters.to
        })));

      [['movementsFrom', 'from'], ['movementsTo', 'to']].forEach(([id, key]) => {
        document.getElementById(id).addEventListener('change', (e) => {
          movementFilters[key] = e.target.value;
          movementFilters.page = 0;
          refreshMovementsTable();
        });
      });

      document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
          document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
          e.target.classList.add('active');

          movementFilters.type = e.target.dataset.filter;
          movementFilters.page = 0;
          refreshMovementsTable();
        });
      });
    }

    function renderMovementsTable(movementsPage) {
      const tableEl = document.getElementById('movementsTable');
      const list = movementsPage.content;

      if (list.length === 0) {
        tableEl.innerHTML = emptyState('🔄', 'Nenhuma movimentação registrada');
        return;
      }

      tableEl.innerHTML = `
        <table>
          <thead>
            <tr>
              <th>Data</th>
              <th>Tipo</th>
              <th>Produto</th>
              <th>Quantidade</th>
              <th>Saldo</th>
              <th>Observações</th>
              <th>Usuário</th>
            </tr>
          </thead>
          <tbody>
            ${list.map(m => `
              <tr>
                <td>${formatDate(m.createdAt)}</td>
                <td>${movementBadge(m.type, true)}</td>
                <td><strong>${escapeHtml(m.product.name)}</strong></td>
                <td><strong>${m.quantity}</strong></td>
                <td>${m.previousQuantity} → ${m.newQuantity}</td>
                <td>${escapeHtml(m.notes || '-')}</td>
                <td>${escapeHtml(m.user?.name || '-')}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
        ${paginationHtml(movementsPage.page, 'movimentações')}
      `;

      bindPagination(tableEl, (step) => {
        movementFilters.page += step;
        refreshMovementsTable();
      });
    }

    // Relatórios
    function loadReports() {
      return api.reports.summary();
    }

    function renderReports(summary) {
      const byCategory = summary.valueByCategory;

      document.getElementById('mainContent').innerHTML = `
        <div class="top-bar">
          <div class="page-title">
            <h2>Relatórios</h2>
            <p>Análises e estatísticas do estoque</p>
          </div>
          <div class="top-actions">
            <button class="btn btn-primary" id="openPdfModalBtn">📄 Gerar PDF</button>
          </div>
        </div>

        <div class="stats-grid">
          <div class="stat-card">
            <div class="stat-header">
              <span class="stat-title">Valor Médio por Produto</span>
              <div class="stat-icon" style="background: rgba(67, 56, 202, 0.1); color: var(--primary);">💵</div>
            </div>
            <div class="stat-value">${formatMoney(summary.averageValuePerProduct)}</div>
          </div>

          <div class="stat-card">
            <div class="stat-header">
              <span class="stat-title">Categorias Cadastradas</span>
              <div class="stat-icon" style="background: rgba(23, 162, 184, 0.1); color: var(--info);">📂</div>
            </div>
            <div class="stat-value">${summary.categories}</div>
          </div>

          <div class="stat-card">
            <div class="stat-header">
              <span class="stat-title">Movimentações (mês)</span>
              <div class="stat-icon" style="background: rgba(40, 167, 69, 0.1); color: var(--success);">🔄</div>
            </div>
            <div class="stat-value">${summary.movementsThisMonth}</div>
          </div>
        </div>

        <div class="card">
          <div class="card-header">
            <h3 class="card-title">Valor por Categoria</h3>
          </div>
          ${byCategory.length > 0 ? `
            <table>
              <thead>
                <tr>
                  <th>Categoria</th>
                  <th>Quantidade Total</th>
                  <th>Valor Total</th>
                  <th>% do Estoque</th>
                </tr>
              </thead>
              <tbody>
                ${byCategory.map(c => {
                  const percentage = Number(c.percentage).toFixed(1);
                  return `
                    <tr>
                      <td><strong>${escapeHtml(c.category)}</strong></td>
                      <td>${c.quantity}</td>
                      <td><strong>${formatMoney(c.value)}</strong></td>
                      <td>
                        <div style="display: flex; align-items: center; gap: 8px;">
                          <div style="flex: 1; height: 8px; background: var(--bg-light); border-radius: 4px; overflow: hidden;">
                            <div style="height: 100%; width: ${percentage}%; background: var(--primary);"></div>
                          </div>
                          <span style="font-weight: 600;">${percentage}%</span>
                        </div>
                      </td>
                    </tr>
                  `;
                }).join('')}
              </tbody>
            </table>
          ` : emptyState('📊', 'Sem dados para exibir')}
        </div>
      `;

      document.getElementById('openPdfModalBtn').addEventListener('click', openPdfModal);
      api.reports.wakeUp();
    }

    // Alertas
    const ALERTS_LIMIT = 100;

    async function loadAlerts() {
      const [lowStock, outOfStock] = await Promise.all([
        api.products.list({ status: 'LOW_STOCK', size: ALERTS_LIMIT, sort: 'quantity' }),
        api.products.list({ status: 'OUT_OF_STOCK', size: ALERTS_LIMIT })
      ]);
      return { lowStock, outOfStock };
    }

    // Quando há mais produtos do que o limite carregado, avisa em vez de esconder o restante
    const alertsCount = (productsPage) => {
      const total = productsPage.page.totalElements;
      return total > productsPage.content.length ? `${productsPage.content.length} de ${total}` : total;
    };

    function renderAlerts({ lowStock, outOfStock }) {
      document.getElementById('mainContent').innerHTML = `
        <div class="top-bar">
          <div class="page-title">
            <h2>Alertas</h2>
            <p>Produtos que requerem atenção</p>
          </div>
        </div>

        <div class="card">
          <div class="card-header">
            <h3 class="card-title">⚠️ Estoque Baixo (${alertsCount(lowStock)})</h3>
          </div>
          ${lowStock.content.length > 0 ? `
            <table>
              <thead>
                <tr>
                  <th>Produto</th>
                  <th>Quantidade Atual</th>
                  <th>Estoque Mínimo</th>
                  <th>Ação</th>
                </tr>
              </thead>
              <tbody>
                ${lowStock.content.map(p => `
                  <tr>
                    <td><strong>${escapeHtml(p.name)}</strong></td>
                    <td><span class="badge badge-warning">${p.quantity}</span></td>
                    <td>${p.minStock}</td>
                    <td>
                      <button class="btn btn-primary" onclick="adjustStock(${p.id}, 'entry')" style="padding: 6px 12px; font-size: 12px;">
                        Adicionar Estoque
                      </button>
                    </td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          ` : emptyState('✅', 'Nenhum produto com estoque baixo')}
        </div>

        <div class="card">
          <div class="card-header">
            <h3 class="card-title">🚫 Sem Estoque (${alertsCount(outOfStock)})</h3>
          </div>
          ${outOfStock.content.length > 0 ? `
            <table>
              <thead>
                <tr>
                  <th>Produto</th>
                  <th>Categoria</th>
                  <th>Última Atualização</th>
                  <th>Ação</th>
                </tr>
              </thead>
              <tbody>
                ${outOfStock.content.map(p => `
                  <tr>
                    <td><strong>${escapeHtml(p.name)}</strong></td>
                    <td>${escapeHtml(p.category?.name || '-')}</td>
                    <td>${formatDate(p.updatedAt)}</td>
                    <td>
                      <button class="btn btn-primary" onclick="adjustStock(${p.id}, 'entry')" style="padding: 6px 12px; font-size: 12px;">
                        Repor Estoque
                      </button>
                    </td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          ` : emptyState('✅', 'Nenhum produto sem estoque')}
        </div>
      `;
    }

    // Erros de formulário: mensagem geral da API + uma linha por campo inválido (respostas 400)
    function showFormError(form, error) {
      const box = form.querySelector('.form-error');
      const fieldMessages = Object.values(error.fieldErrors || {});
      box.innerHTML = `
        <strong>${escapeHtml(error.message)}</strong>
        ${fieldMessages.length > 0 ? `<ul>${fieldMessages.map(m => `<li>${escapeHtml(m)}</li>`).join('')}</ul>` : ''}
      `;
      box.hidden = false;
    }

    function clearFormError(form) {
      const box = form.querySelector('.form-error');
      box.hidden = true;
      box.innerHTML = '';
    }

    // Modal Produto
    let categories = [];

    // A categoria é digitada livremente: usa a existente com o mesmo nome (sem diferenciar maiúsculas) ou cria uma nova
    async function resolveCategoryId(name) {
      const trimmed = name.trim();
      if (!trimmed) return null;

      const existing = categories.find(c => c.name.toLowerCase() === trimmed.toLowerCase());
      if (existing) return existing.id;

      const created = await api.categories.create(trimmed);
      categories.push(created);
      return created.id;
    }

    async function openProductModal(productId = null) {
      const modal = document.getElementById('productModal');
      const form = document.getElementById('productForm');
      const title = document.getElementById('productModalTitle');

      let suppliers;
      let product = null;
      try {
        [categories, suppliers, product] = await Promise.all([
          api.categories.list(),
          api.suppliers.list(),
          productId ? api.products.get(productId) : null
        ]);
      } catch (error) {
        reportError(error);
        return;
      }

      form.reset();
      clearFormError(form);
      document.getElementById('categoryOptions').innerHTML =
        categories.map(c => `<option value="${escapeHtml(c.name)}"></option>`).join('');
      form.supplier.innerHTML = '<option value="">Selecione...</option>' +
        suppliers.map(s => `<option value="${s.id}">${escapeHtml(s.name)}</option>`).join('');

      // O saldo só muda por movimentações: na edição a quantidade aparece, mas não é editável
      form.qty.disabled = Boolean(product);
      document.getElementById('qtyHint').hidden = !product;

      if (product) {
        title.textContent = 'Editar Produto';
        form.name.value = product.name;
        form.sku.value = product.sku || '';
        form.category.value = product.category?.name || '';
        form.qty.value = product.quantity;
        form.price.value = product.unitPrice;
        form.minStock.value = product.minStock;
        form.supplier.value = product.supplier?.id ?? '';
        form.dataset.editId = product.id;
      } else {
        title.textContent = 'Novo Produto';
        delete form.dataset.editId;
      }

      modal.classList.add('open');
    }

    function closeProductModal() {
      const form = document.getElementById('productForm');
      document.getElementById('productModal').classList.remove('open');
      form.reset();
      clearFormError(form);
      delete form.dataset.editId;
    }

    document.getElementById('closeProductModal').addEventListener('click', closeProductModal);
    document.getElementById('cancelProduct').addEventListener('click', closeProductModal);

    document.getElementById('productForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const editId = form.dataset.editId;
      const submitBtn = form.querySelector('button[type="submit"]');

      clearFormError(form);
      submitBtn.disabled = true;
      try {
        const data = {
          name: form.name.value.trim(),
          sku: form.sku.value.trim() || null,
          categoryId: await resolveCategoryId(form.category.value),
          supplierId: form.supplier.value ? Number(form.supplier.value) : null,
          unitPrice: parseFloat(form.price.value),
          minStock: form.minStock.value === '' ? DEFAULT_MIN_STOCK : parseInt(form.minStock.value, 10)
        };

        if (editId) {
          await api.products.update(editId, data);
        } else {
          // A quantidade inicial vira uma movimentação de entrada "Estoque inicial" na API
          await api.products.create({ ...data, initialQuantity: parseInt(form.qty.value, 10) || 0 });
        }

        closeProductModal();
        reloadCurrentPage();
      } catch (error) {
        showFormError(form, error);
      } finally {
        submitBtn.disabled = false;
      }
    });

    // Modal Fornecedor
    async function openSupplierModal(supplierId = null) {
      const modal = document.getElementById('supplierModal');
      const form = document.getElementById('supplierForm');
      const title = document.getElementById('supplierModalTitle');

      let supplier = null;
      if (supplierId) {
        try {
          supplier = await api.suppliers.get(supplierId);
        } catch (error) {
          reportError(error);
          return;
        }
      }

      form.reset();
      clearFormError(form);

      if (supplier) {
        title.textContent = 'Editar Fornecedor';
        form.name.value = supplier.name;
        form.cnpj.value = formatCnpj(supplier.cnpj) || '';
        form.phone.value = supplier.phone || '';
        form.email.value = supplier.email || '';
        form.address.value = supplier.address || '';
        form.dataset.editId = supplier.id;
      } else {
        title.textContent = 'Novo Fornecedor';
        delete form.dataset.editId;
      }

      modal.classList.add('open');
    }

    function closeSupplierModal() {
      const form = document.getElementById('supplierForm');
      document.getElementById('supplierModal').classList.remove('open');
      form.reset();
      clearFormError(form);
      delete form.dataset.editId;
    }

    document.getElementById('closeSupplierModal').addEventListener('click', closeSupplierModal);
    document.getElementById('cancelSupplier').addEventListener('click', closeSupplierModal);

    document.getElementById('supplierForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const editId = form.dataset.editId;
      const submitBtn = form.querySelector('button[type="submit"]');

      // A API remove a pontuação do CNPJ e converte campos vazios em nulo
      const data = {
        name: form.name.value.trim(),
        cnpj: form.cnpj.value.trim(),
        phone: form.phone.value.trim(),
        email: form.email.value.trim(),
        address: form.address.value.trim()
      };

      clearFormError(form);
      submitBtn.disabled = true;
      try {
        if (editId) {
          await api.suppliers.update(editId, data);
        } else {
          await api.suppliers.create(data);
        }

        closeSupplierModal();
        reloadCurrentPage();
      } catch (error) {
        showFormError(form, error);
      } finally {
        submitBtn.disabled = false;
      }
    });

    // Funções Globais
    window.editProduct = (id) => openProductModal(id);
    window.editSupplier = (id) => openSupplierModal(id);

    window.deleteProduct = async (id) => {
      if (!confirm('Deseja realmente excluir este produto?\n\nO histórico de movimentações dele continuará disponível.')) return;
      try {
        await api.products.remove(id);
        reloadCurrentPage();
      } catch (error) {
        reportError(error);
      }
    };

    // A API recusa (409) excluir fornecedor com produtos vinculados; a mensagem dela é exibida ao usuário
    window.deleteSupplier = async (id) => {
      if (!confirm('Deseja realmente excluir este fornecedor?')) return;
      try {
        await api.suppliers.remove(id);
        reloadCurrentPage();
      } catch (error) {
        reportError(error);
      }
    };

    window.adjustStock = async (id, type) => {
      const label = type === 'entry' ? 'Entrada' : 'Saída';
      try {
        const product = await api.products.get(id);

        const input = prompt(`${label} de estoque para ${product.name}\nSaldo atual: ${product.quantity}\n\nQuantidade:`);
        if (input === null) return;
        const qty = Number(input);
        if (!Number.isInteger(qty) || qty <= 0) {
          alert('Informe uma quantidade inteira maior que zero.');
          return;
        }

        const notes = prompt('Observações (opcional):', '');
        if (notes === null) return;

        // Saída maior que o saldo é recusada pela API (422) com uma mensagem explicando o motivo
        await api.products.registerMovement(id, {
          type: type === 'entry' ? 'ENTRY' : 'EXIT',
          quantity: qty,
          notes: notes.trim() || null
        });
        reloadCurrentPage();
      } catch (error) {
        reportError(error);
      }
    };

    // Usuários (somente administradores; a API responde 403 para os demais)
    function loadUsers() {
      return api.users.list();
    }

    function renderUsers(users) {
      const currentUserId = session.user()?.id;

      document.getElementById('mainContent').innerHTML = `
        <div class="top-bar">
          <div class="page-title">
            <h2>Usuários</h2>
            <p>Quem pode acessar o sistema</p>
          </div>
          <div class="top-actions">
            <button class="btn btn-primary" id="addUserBtn">
              <span>➕</span> Novo Usuário
            </button>
          </div>
        </div>

        <div class="card">
          ${users.length > 0 ? `
            <table>
              <thead>
                <tr>
                  <th>Nome</th>
                  <th>E-mail</th>
                  <th>Perfil</th>
                  <th>Situação</th>
                  <th>Cadastro</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                ${users.map(u => `
                  <tr>
                    <td>
                      <strong>${escapeHtml(u.name)}</strong>
                      ${u.id === currentUserId ? '<small style="color: var(--text-muted);">(você)</small>' : ''}
                    </td>
                    <td>${escapeHtml(u.email)}</td>
                    <td>${u.role === 'ADMIN' ? '🛡️ ' : ''}${ROLE_LABELS[u.role]}</td>
                    <td><span class="badge ${u.active ? 'badge-success' : 'badge-danger'}">${u.active ? 'Ativo' : 'Inativo'}</span></td>
                    <td>${formatDate(u.createdAt)}</td>
                    <td>
                      <div class="action-btns">
                        <button class="btn-icon" onclick="editUser(${u.id})" title="Editar">✏️</button>
                      </div>
                    </td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          ` : emptyState('👥', 'Nenhum usuário cadastrado')}
        </div>
      `;

      document.getElementById('addUserBtn').addEventListener('click', () => openUserModal());
    }

    // Modal Usuário
    async function openUserModal(userId = null) {
      const modal = document.getElementById('userModal');
      const form = document.getElementById('userForm');

      let user = null;
      if (userId) {
        try {
          user = await api.users.get(userId);
        } catch (error) {
          reportError(error);
          return;
        }
      }

      form.reset();
      clearFormError(form);

      // Na edição o e-mail (login) fica fixo e a senha é opcional: preenchida, redefine a senha do usuário
      const editing = Boolean(user);
      document.getElementById('userModalTitle').textContent = editing ? 'Editar Usuário' : 'Novo Usuário';
      form.email.disabled = editing;
      document.getElementById('userEmailHint').hidden = !editing;
      form.password.required = !editing;
      document.getElementById('userPasswordLabel').textContent = editing ? 'Nova senha' : 'Senha *';
      document.getElementById('userPasswordHint').textContent = editing
        ? 'Deixe em branco para manter a senha atual.'
        : 'Mínimo de 8 caracteres.';
      document.getElementById('userActiveGroup').hidden = !editing;

      if (editing) {
        form.name.value = user.name;
        form.email.value = user.email;
        form.role.value = user.role;
        form.active.checked = user.active;
        form.dataset.editId = user.id;
      } else {
        delete form.dataset.editId;
      }

      modal.classList.add('open');
    }

    function closeUserModal() {
      const form = document.getElementById('userForm');
      document.getElementById('userModal').classList.remove('open');
      form.reset();
      clearFormError(form);
      delete form.dataset.editId;
    }

    document.getElementById('closeUserModal').addEventListener('click', closeUserModal);
    document.getElementById('cancelUser').addEventListener('click', closeUserModal);

    document.getElementById('userForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const editId = form.dataset.editId;
      const submitBtn = form.querySelector('button[type="submit"]');

      clearFormError(form);
      submitBtn.disabled = true;
      try {
        if (editId) {
          const updated = await api.users.update(editId, {
            name: form.name.value.trim(),
            role: form.role.value,
            active: form.active.checked,
            password: form.password.value || null
          });
          // Editou o próprio cadastro: atualiza o nome exibido na barra lateral
          if (updated.id === session.user()?.id) {
            session.updateUser(updated);
            renderSessionUser();
          }
        } else {
          await api.users.create({
            name: form.name.value.trim(),
            email: form.email.value.trim(),
            password: form.password.value,
            role: form.role.value
          });
        }

        closeUserModal();
        reloadCurrentPage();
      } catch (error) {
        showFormError(form, error);
      } finally {
        submitBtn.disabled = false;
      }
    });

    window.editUser = (id) => openUserModal(id);

    // Modal Alterar Senha
    function openPasswordModal() {
      setMenuOpen(false);
      const form = document.getElementById('passwordForm');
      form.reset();
      clearFormError(form);
      document.getElementById('passwordModal').classList.add('open');
    }

    function closePasswordModal() {
      const form = document.getElementById('passwordForm');
      document.getElementById('passwordModal').classList.remove('open');
      form.reset();
      clearFormError(form);
    }

    document.getElementById('changePasswordBtn').addEventListener('click', openPasswordModal);
    document.getElementById('closePasswordModal').addEventListener('click', closePasswordModal);
    document.getElementById('cancelPassword').addEventListener('click', closePasswordModal);

    document.getElementById('passwordForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const submitBtn = form.querySelector('button[type="submit"]');

      clearFormError(form);
      if (form.newPassword.value !== form.confirmPassword.value) {
        showFormError(form, { message: 'A confirmação não confere com a nova senha.' });
        return;
      }

      submitBtn.disabled = true;
      try {
        await api.auth.changePassword(form.currentPassword.value, form.newPassword.value);
        closePasswordModal();
        alert('Senha alterada com sucesso.');
      } catch (error) {
        showFormError(form, error);
      } finally {
        submitBtn.disabled = false;
      }
    });

    // Modal Gerar PDF
    const pdfForm = document.getElementById('pdfForm');

    function updatePdfDates() {
      document.getElementById('pdfDates').hidden = pdfForm.kind.value !== 'full';
    }

    function openPdfModal() {
      pdfForm.reset();
      clearFormError(pdfForm);
      const today = new Date();
      pdfForm.from.value = toInputDate(new Date(today.getFullYear(), today.getMonth(), 1));
      pdfForm.to.value = toInputDate(today);
      updatePdfDates();
      document.getElementById('pdfModal').classList.add('open');
    }

    function closePdfModal() {
      document.getElementById('pdfModal').classList.remove('open');
      pdfForm.reset();
      clearFormError(pdfForm);
    }

    document.getElementById('closePdfModal').addEventListener('click', closePdfModal);
    document.getElementById('cancelPdf').addEventListener('click', closePdfModal);
    pdfForm.querySelectorAll('input[name="kind"]').forEach(radio => radio.addEventListener('change', updatePdfDates));

    pdfForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      const submitBtn = pdfForm.querySelector('button[type="submit"]');
      const label = submitBtn.textContent;

      clearFormError(pdfForm);
      submitBtn.disabled = true;
      submitBtn.textContent = 'Gerando...';
      try {
        await api.reports.downloadPdf(pdfForm.kind.value, { from: pdfForm.from.value, to: pdfForm.to.value });
        closePdfModal();
      } catch (error) {
        showFormError(pdfForm, error);
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = label;
      }
    });

    // Sessão: alterna entre a tela de login e o sistema
    function renderSessionUser() {
      const user = session.user();
      if (!user) return;
      document.getElementById('currentUserName').textContent = user.name;
      document.getElementById('currentUserRole').textContent = ROLE_LABELS[user.role];
      document.getElementById('adminNav').hidden = user.role !== 'ADMIN';
    }

    function showApp() {
      document.getElementById('loginScreen').hidden = true;
      document.getElementById('app').hidden = false;
      renderSessionUser();
      resetFilters();
      navigateTo('dashboard');
    }

    function showLogin(message = '') {
      setMenuOpen(false);
      document.querySelectorAll('.modal.open').forEach(modal => modal.classList.remove('open'));
      document.getElementById('app').hidden = true;
      document.getElementById('loginScreen').hidden = false;

      const form = document.getElementById('loginForm');
      clearFormError(form);
      if (message) showFormError(form, { message });
      form.email.focus();
    }

    // Token expirado ou usuário desativado durante o uso: volta para o login com a mensagem da API
    api.onUnauthorized = (message) => showLogin(message);

    // API acordando no Render: mostra o aviso enquanto as respostas não chegam
    api.onSlowServer = (slow) => {
      document.getElementById('serverNotice').hidden = !slow;
    };

    document.getElementById('loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const submitBtn = form.querySelector('button[type="submit"]');

      clearFormError(form);
      submitBtn.disabled = true;
      try {
        await api.auth.login(form.email.value.trim(), form.password.value);
        form.reset();
        showApp();
      } catch (error) {
        showFormError(form, error);
      } finally {
        submitBtn.disabled = false;
      }
    });

    document.getElementById('logoutBtn').addEventListener('click', () => {
      api.auth.logout();
      showLogin();
    });

    // Busca rápida do dashboard: ao apertar Enter, abre a página de produtos já filtrada
    function quickSearch(e) {
      if (e.key !== 'Enter') return;
      resetFilters();
      productFilters.search = e.target.value.trim();
      navigateTo('products');
    }

    // Inicializar: com uma sessão válida abre o sistema; sem ela, a tela de login
    if (session.get()) {
      showApp();
    } else {
      showLogin();
    }
