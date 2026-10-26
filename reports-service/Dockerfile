# Imagem do serviço de relatórios em PDF (FastAPI + WeasyPrint), usada no deploy (Render) e no compose.yaml

FROM python:3.12-slim
WORKDIR /app

# Bibliotecas nativas do WeasyPrint (texto e fontes) e a fonte DejaVu Sans usada nos PDFs
RUN apt-get update \
    && apt-get install -y --no-install-recommends libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz-subset0 fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

# Dependências primeiro: enquanto o requirements.txt não mudar, o Docker reaproveita esta camada
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ app/

# Roda sem root
RUN useradd --system --no-create-home tana
USER tana
# Caches do matplotlib e do fontconfig em pastas com escrita para o usuário sem root
ENV MPLCONFIGDIR=/tmp/matplotlib \
    XDG_CACHE_HOME=/tmp/cache

EXPOSE 8000
# Forma shell para expandir ${PORT}, que o Render define; sem ela, usa 8000
CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}
