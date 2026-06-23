#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-smoke-user-$(date +%s)}"

post_json() {
  local path="$1"
  local payload="$2"
  curl -fsS "${BASE_URL}${path}" \
    -H 'Content-Type: application/json' \
    -d "${payload}"
}

echo "Checking ${BASE_URL}/api/health"
curl -fsS "${BASE_URL}/api/health" >/dev/null

create_payload="$(USER_ID="${USER_ID}" python3 - <<'PY'
import json
import os

print(json.dumps({
    "messages": [
        {
            "role": "user",
            "content": "Smoke codename zeta uses pgvector keyword search."
        }
    ],
    "user_id": os.environ["USER_ID"],
    "metadata": {"source": "smoke-test"},
    "infer": False
}))
PY
)"

create_response="$(post_json "/memories" "${create_payload}")"
memory_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["results"][0]["id"])' <<< "${create_response}")"
echo "Created memory ${memory_id}"

search_payload="$(USER_ID="${USER_ID}" python3 - <<'PY'
import json
import os

print(json.dumps({
    "query": "codename zeta",
    "user_id": os.environ["USER_ID"],
    "filters": {"source": {"eq": "smoke-test"}},
    "search_mode": "keyword",
    "top_k": 5
}))
PY
)"

search_response="$(post_json "/search" "${search_payload}")"
python3 -c 'import json,sys; data=json.load(sys.stdin); assert data["results"], "search returned no results"' <<< "${search_response}"
echo "Keyword search returned results"

curl -fsS "${BASE_URL}/memories/${memory_id}/history" >/dev/null
curl -fsS -X DELETE "${BASE_URL}/memories/${memory_id}" >/dev/null
echo "Smoke test passed"
