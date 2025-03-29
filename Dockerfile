# -----------------------------------------
# Stage 1:  Arcana/Engine uses  python3.9
# -----------------------------------------
FROM python:3.9-slim as builder

# 1) Install OS packages needed for compilation
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    build-essential \
    gcc \
    cmake \
    python3-dev \
    && rm -rf /var/lib/apt/lists/*

# 2) Upgrade pip
RUN pip install -vvv --no-cache-dir --upgrade pip

# 3) Compile tree_sitter and the language pack (the wheels, takes like ~ 8-15 mins on ARM lol)
#    Increase timeout and use verbose so we can see progress
RUN pip install -vvv --no-cache-dir --default-timeout=2900 \
    tree_sitter==0.23.2 \
    tree_sitter_language_pack==0.2.0

# -----------------------------------------
# Stage 2: Final image
# -----------------------------------------
FROM python:3.9-slim

# Copy compiled tree_sitter / language pack from Stage 1
COPY --from=builder /usr/local/lib/python3.9/site-packages \
                    /usr/local/lib/python3.9/site-packages

# Copy any other needed data from builder if necessary
# e.g. /usr/local/bin if the package installed scripts

# Set up build essentials if other dependencies need them
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy Arcana code
COPY . /app

# Install other Python deps (excluding tree_sitter lines, since it's already installed)
RUN if [ -f requirements.txt ]; then \
        pip install -vvv --no-cache-dir -r requirements.txt; \
    fi

# Ensure Python doesn't buffer output (helps with our real-time logs, stdout/stdin forwarding etc)
ENV PYTHONUNBUFFERED=1

# Default cmd (override @ runtime w/ `docker run ...`)
CMD ["python", "/app/_0.py"]
