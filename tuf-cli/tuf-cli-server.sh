#!/bin/bash

set -o pipefail -o errexit -o nounset

CWD=$PWD

FAKETIME_VAL="${FAKETIME:-}"

JSON_PAYLOAD=$(jq -n -c \
  --arg cwd "$CWD" \
  --arg faketime "$FAKETIME_VAL" \
  --json args "$(jq -n -c '$ARGS.positional' --args "$@")" \
  '{cwd: $cwd, args: $args, faketime: $faketime}')

RESPONSE=$(curl -s -X POST --header "Content-Type: application/json" --data-binary "$JSON_PAYLOAD" http://localhost:8080/execute)

STDOUT=$(echo "$RESPONSE" | jq -r .stdout)
STDERR=$(echo "$RESPONSE" | jq -r .stderr)
EXIT_CODE=$(echo "$RESPONSE" | jq .exitCode)

echo -n "$STDOUT"
echo -n "$STDERR" >&2

exit "$EXIT_CODE"
