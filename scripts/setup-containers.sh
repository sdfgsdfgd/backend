#!/bin/bash
# Multi-Stage Example (Compiles tree_sitter in builder, final is lighter)

set -e
ARCANA_DIR=~/Desktop/arcana

if [ ! -d "$ARCANA_DIR" ]; then
    echo "❌ Error: Arcana directory not found at $ARCANA_DIR"
    exit 1
fi

TEMP_DIR=$(mktemp -d)
echo "📁 Creating temporary build directory: $TEMP_DIR"

echo "📦 Copying arcana code..."
cp -r "$ARCANA_DIR"/* "$TEMP_DIR"/

cat > "$TEMP_DIR/Dockerfile" <<'EOL'
# Stage 1: Builder
FROM python:3.9-slim as builder

RUN apt-get update && apt-get install -y --no-install-recommends \
    jq git build-essential gcc cmake python3-dev \
    && rm -rf /var/lib/apt/lists/* \
    && apt-get update \
    && apt-get install -y --no-install-recommends software-properties-common \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
    default-jdk \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir --upgrade pip

# Compile tree_sitter + language pack
RUN pip install --no-cache-dir --default-timeout=900 \
    tree_sitter==0.23.2 \
    tree_sitter_language_pack==0.2.0

# Stage 2: Final
FROM python:3.9-slim

COPY --from=builder /usr/local/lib/python3.9/site-packages /usr/local/lib/python3.9/site-packages

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . /app

RUN if [ -f requirements.txt ]; then \
      pip install --no-cache-dir -r requirements.txt; \
    fi

ENV PYTHONUNBUFFERED=1
CMD ["python", "/app/_0.py"]
EOL

echo "🔨 Building multi-stage arcana Docker image..."
docker build -t python-client "$TEMP_DIR"

echo "🧹 Cleaning up..."
rm -rf "$TEMP_DIR"

echo "🚀 Arcana Docker image is ready!"
