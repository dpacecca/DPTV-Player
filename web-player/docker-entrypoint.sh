#!/bin/sh
# Started as root: make the data folder the server's own, then run the server as the unprivileged
# "node" user. A folder Docker or unRAID created for the volume belongs to root, and without this the
# server could keep neither its cookie key nor the single-sign-on links there - every restart then
# signed everyone out and asked each person to link their DPTV-Server login again.
set -e
DATA="${DATA_DIR:-/data}"
if [ "$(id -u)" = "0" ]; then
  mkdir -p "$DATA"
  chown -R node:node "$DATA" 2>/dev/null || echo "[data] could not hand $DATA to the node user; check its permissions"
  exec su-exec node "$@"
fi
exec "$@"
