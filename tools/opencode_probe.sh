#!/usr/bin/env bash
# OpenCode endpoint probe — safe, non-destructive verification of the upstream
# gateway behavior that OpenCodeClient (Anvex v4.4.1) is built against.
#
# Every request uses Anvex's OWN honest identity (User-Agent below). This script
# deliberately does NOT send the official OpenCode client's fingerprint
# (User-Agent "opencode/<version>", x-opencode-client/-session/-request headers,
# "Bearer public", or the forced tools quartet) — reproducing that fingerprint to
# enter the free tier is client impersonation and is out of bounds for Anvex.
#
# Usage: bash opencode_probe.sh [base_domain]
# Default base: https://opencode.ai

set -u
BASE="${1:-https://opencode.ai}"
UA="Anvex/4.4 (Android; probe)"

probe() {
  local label="$1" method="$2" url="$3" body="${4:-}"
  echo "=== $label ==="
  if [ "$method" = "GET" ]; then
    curl -sS -m 30 "$url" -H "User-Agent: $UA" -o /tmp/probe_body.json -w "HTTP %{http_code}, %{size_download}B, %{time_total}s\n"
  else
    curl -sS -m 30 "$url" -H "User-Agent: $UA" -H "Content-Type: application/json" -d "$body" -o /tmp/probe_body.json -w "HTTP %{http_code}, %{size_download}B, %{time_total}s\n"
  fi
  head -c 220 /tmp/probe_body.json; echo; echo
}

probe "P1  GET public model discovery (current layout)"       GET  "$BASE/inference/v1/models"
probe "P2  GET public model discovery (legacy /zen/v1)"        GET  "$BASE/zen/v1/models"
probe "P3  POST chat, free model, no credential (the gate)"    POST "$BASE/inference/openai/v1/chat/completions" \
      '{"model":"space-bunny-free","messages":[{"role":"user","content":"ping"}],"max_tokens":8}'
probe "P4  POST chat, paid model, no credential"               POST "$BASE/inference/openai/v1/chat/completions" \
      '{"model":"gpt-5.5","messages":[{"role":"user","content":"ping"}],"max_tokens":8}'
probe "P5  POST chat, free model, legacy /zen/v1 path"         POST "$BASE/zen/v1/chat/completions" \
      '{"model":"space-bunny-free","messages":[{"role":"user","content":"ping"}],"max_tokens":8}'
probe "P6  POST chat, nonexistent model id"                    POST "$BASE/inference/openai/v1/chat/completions" \
      '{"model":"nonexistent-model-xyz","messages":[{"role":"user","content":"ping"}],"max_tokens":8}'

echo "=== P7  GET models with an INVALID bearer (key validation check) ==="
curl -sS -m 30 "$BASE/inference/v1/models" -H "User-Agent: $UA" -H "Authorization: Bearer opencode_probe_invalid_key" -o /tmp/probe_body.json -w "HTTP %{http_code}, %{size_download}B\n"
head -c 220 /tmp/probe_body.json; echo; echo

echo "=== P8  GET /inference/openai/v1/models (per-family path check) ==="
curl -sS -m 30 "$BASE/inference/openai/v1/models" -H "User-Agent: $UA" -o /tmp/probe_body.json -w "HTTP %{http_code}, %{size_download}B\n"
head -c 220 /tmp/probe_body.json; echo
