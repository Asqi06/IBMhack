FROM python:3.11-slim

WORKDIR /app

# System deps for httpx/http2 if needed
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# IBM Cloud Code Engine injects $PORT; default to 8000 for local
ENV PORT=8000
EXPOSE 8000

CMD uvicorn main:app --host 0.0.0.0 --port ${PORT}
