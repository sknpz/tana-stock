    // Cliente da API REST do Tana Stock (backend/ em Java + Spring Boot)

    // Endereço da API: a local durante o desenvolvimento e a publicada no Render quando o front está
    // no GitHub Pages. Pode ser sobrescrito definindo window.TANA_API_URL antes de carregar este arquivo.
    const LOCAL_HOSTS = ['localhost', '127.0.0.1', ''];
    const API_BASE_URL = window.TANA_API_URL
      || (LOCAL_HOSTS.includes(location.hostname) ? 'http://localhost:8080' : 'https://tana-stock-api.onrender.com');

    // Serviço de relatórios em PDF (Python): local no desenvolvimento e no Render quando o front está no GitHub Pages.
    // Pode ser sobrescrito definindo window.TANA_REPORTS_URL antes de carregar este arquivo.
    const REPORTS_BASE_URL = window.TANA_REPORTS_URL
      || (LOCAL_HOSTS.includes(location.hostname) ? 'http://localhost:8000' : 'https://tana-stock-reports.onrender.com');

    // Erro devolvido pela API no formato Problem Details (RFC 9457)
    class ApiError extends Error {
      constructor(message, status, fieldErrors = {}) {
        super(message);
        this.status = status;
        this.fieldErrors = fieldErrors;
      }
    }

    // Sessão do usuário logado: token JWT, validade e dados do usuário, guardados no localStorage
    const session = (() => {
      const SESSION_KEY = 'tana_session';

      const clear = () => localStorage.removeItem(SESSION_KEY);

      const get = () => {
        let stored = null;
        try {
          stored = JSON.parse(localStorage.getItem(SESSION_KEY));
        } catch {
          // valor corrompido: trata como deslogado
        }
        if (!stored || new Date(stored.expiresAt) <= new Date()) {
          clear();
          return null;
        }
        return stored;
      };

      const save = (loginResponse) => {
        localStorage.setItem(SESSION_KEY, JSON.stringify({
          token: loginResponse.accessToken,
          expiresAt: loginResponse.expiresAt,
          user: loginResponse.user
        }));
      };

      const updateUser = (user) => {
        const current = get();
        if (current) localStorage.setItem(SESSION_KEY, JSON.stringify({ ...current, user }));
      };

      return { get, save, clear, updateUser, user: () => get()?.user ?? null };
    })();

    const api = (() => {
      // No plano gratuito do Render a API desliga depois de 15 minutos sem uso, e a primeira requisição
      // espera ela iniciar de novo. Se a resposta demorar, o script.js avisa o usuário (api.onSlowServer).
      const SLOW_REQUEST_MS = 3000;
      let pendingRequests = 0;
      let slowTimer = null;

      function trackRequest(promise) {
        if (pendingRequests++ === 0) slowTimer = setTimeout(() => api.onSlowServer(true), SLOW_REQUEST_MS);
        return promise.finally(() => {
          if (--pendingRequests === 0) {
            clearTimeout(slowTimer);
            api.onSlowServer(false);
          }
        });
      }

      // Transforma uma resposta de erro (Problem Details) em ApiError; 401 fora do login encerra a sessão
      function failFrom(response, data, path) {
        const error = new ApiError(data?.detail || `A API respondeu com erro ${response.status}.`, response.status, data?.errors || {});
        // 401 fora do login significa token expirado ou usuário desativado: encerra a sessão
        if (response.status === 401 && path !== '/api/auth/login') {
          session.clear();
          api.onUnauthorized(error.message);
        }
        return error;
      }

      // Baixa um arquivo (CSV ou PDF) autenticado e dispara o salvamento pelo navegador
      async function download(baseUrl, path, fallbackName, unreachableMessage) {
        const token = session.get()?.token;
        const headers = token ? { Authorization: `Bearer ${token}` } : {};

        let response;
        try {
          response = await trackRequest(fetch(baseUrl + path, { headers }));
        } catch {
          throw new ApiError(unreachableMessage, 0);
        }

        if (!response.ok) {
          const data = await response.json().catch(() => null);
          throw failFrom(response, data, path);
        }

        const blob = await response.blob();
        const disposition = response.headers.get('Content-Disposition') || '';
        const filename = /filename="([^"]+)"/.exec(disposition)?.[1] || fallbackName;

        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
      }

      const apiUnreachable = `Não foi possível conectar à API em ${API_BASE_URL}. Verifique se o backend está rodando.`;
      const reportsUnreachable = 'O serviço de relatórios está indisponível no momento. Tente novamente em alguns minutos.';
      const today = () => new Date().toLocaleDateString('sv-SE');

      async function request(method, path, body) {
        const headers = body === undefined ? {} : { 'Content-Type': 'application/json' };
        const token = session.get()?.token;
        if (token) headers.Authorization = `Bearer ${token}`;

        let response;
        try {
          response = await trackRequest(fetch(API_BASE_URL + path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body)
          }));
        } catch {
          // fetch só rejeita quando não há resposta: API fora do ar, endereço errado ou CORS bloqueado
          throw new ApiError(apiUnreachable, 0);
        }

        if (response.status === 204) return null;

        const data = await response.json().catch(() => null);
        if (!response.ok) throw failFrom(response, data, path);
        return data;
      }

      // Monta a query string ignorando parâmetros vazios: { search: '', page: 0 } → "?page=0"
      const query = (params = {}) => {
        const search = new URLSearchParams();
        Object.entries(params).forEach(([key, value]) => {
          if (value !== undefined && value !== null && value !== '') search.append(key, value);
        });
        const result = search.toString();
        return result ? `?${result}` : '';
      };

      return {
        // Chamado quando a sessão deixa de valer; o script.js troca para a tela de login
        onUnauthorized: () => {},
        // Chamado com true quando a API demora a responder e com false quando todas as respostas chegam
        onSlowServer: () => {},

        auth: {
          login: async (email, password) => {
            const response = await request('POST', '/api/auth/login', { email, password });
            session.save(response);
            return response.user;
          },
          logout: () => session.clear(),
          me: () => request('GET', '/api/auth/me'),
          changePassword: (currentPassword, newPassword) =>
            request('PUT', '/api/auth/password', { currentPassword, newPassword })
        },
        users: {
          list: () => request('GET', '/api/users'),
          get: (id) => request('GET', `/api/users/${id}`),
          create: (user) => request('POST', '/api/users', user),
          update: (id, user) => request('PUT', `/api/users/${id}`, user)
        },
        categories: {
          list: () => request('GET', '/api/categories'),
          create: (name) => request('POST', '/api/categories', { name })
        },
        suppliers: {
          list: (search) => request('GET', `/api/suppliers${query({ search })}`),
          exportCsv: (search) =>
            download(API_BASE_URL, `/api/suppliers/export.csv${query({ search })}`, `fornecedores-${today()}.csv`, apiUnreachable),
          get: (id) => request('GET', `/api/suppliers/${id}`),
          create: (supplier) => request('POST', '/api/suppliers', supplier),
          update: (id, supplier) => request('PUT', `/api/suppliers/${id}`, supplier),
          remove: (id) => request('DELETE', `/api/suppliers/${id}`)
        },
        products: {
          list: (params) => request('GET', `/api/products${query(params)}`),
          exportCsv: ({ search, status } = {}) =>
            download(API_BASE_URL, `/api/products/export.csv${query({ search, status })}`, `produtos-${today()}.csv`, apiUnreachable),
          get: (id) => request('GET', `/api/products/${id}`),
          create: (product) => request('POST', '/api/products', product),
          update: (id, product) => request('PUT', `/api/products/${id}`, product),
          remove: (id) => request('DELETE', `/api/products/${id}`),
          registerMovement: (id, movement) => request('POST', `/api/products/${id}/movements`, movement)
        },
        movements: {
          list: (params) => request('GET', `/api/movements${query(params)}`),
          exportCsv: ({ type, from, to } = {}) =>
            download(API_BASE_URL, `/api/movements/export.csv${query({ type, from, to })}`, `movimentacoes-${today()}.csv`, apiUnreachable)
        },
        reports: {
          summary: () => request('GET', '/api/reports/summary'),
          topProducts: (limit) => request('GET', `/api/reports/top-products${query({ limit })}`),
          // kind: 'summary' (1 página) ou 'full' (inventário e movimentações do período from..to)
          downloadPdf: (kind, { from, to } = {}) => kind === 'full'
            ? download(REPORTS_BASE_URL, `/reports/full.pdf${query({ from, to })}`, `relatorio-completo-${from}-a-${to}.pdf`, reportsUnreachable)
            : download(REPORTS_BASE_URL, '/reports/summary.pdf', `relatorio-resumo-${today()}.pdf`, reportsUnreachable),
          // Acorda o serviço de PDF (Render free) sem avisar o usuário; erros são ignorados
          wakeUp: () => fetch(`${REPORTS_BASE_URL}/health`).catch(() => {})
        }
      };
    })();
