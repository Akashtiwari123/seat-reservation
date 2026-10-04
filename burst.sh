#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
exec java scripts/Burst.java "${1:?usage: ./burst.sh <BASE_URL>}"
