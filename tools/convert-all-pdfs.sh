#!/usr/bin/env bash
#
# Simple script to authenticate with docspell and trigger the "convert
# all pdf" route that submits a task to convert all pdf files using
# ocrmypdf.

set -e

BASE_URL="${1:-http://localhost:7880}"
LOGIN_URL="$BASE_URL/api/v1/open/auth/login"
TRIGGER_URL="$BASE_URL/api/v1/sec/item/convertallpdfs"

echo "Login to trigger converting all pdfs."
echo "Using url: $BASE_URL"
echo -n "Account: "
read USER
echo -n "Password: "
read -s PASS
echo

auth=$(curl --fail -XPOST --silent --data-binary "{\"account\":\"$USER\", \"password\":\"$PASS\"}" "$LOGIN_URL")

if [ "$(echo $auth | jq .success)" == "true" ]; then
    echo "Login successful"
    auth_token=$(echo $auth | jq -r .token)
    curl --fail -XPOST -H "X-Docspell-Auth: $auth_token" "$TRIGGER_URL"
else
    echo "Login failed."
fi
