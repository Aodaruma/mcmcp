#!/usr/bin/env bash
set -euo pipefail

screen_width="${SCREEN_WIDTH:-1280}"
screen_height="${SCREEN_HEIGHT:-720}"

minecraft_config="/data/prism/instances/MCMCP-Validation/minecraft/config"
mcp_config="${minecraft_config}/mcmcp"
admin_config="${minecraft_config}/mcmcp-fixture-admin"

# Docker Desktop's Windows bind mount cannot enforce POSIX owner-only modes.
# Initialize only the two security-sensitive config directories on disposable
# named volumes, seed public fixture data, then drop permanently to ubuntu.
if [[ "$(id -u)" == "0" ]]; then
  install -d -o ubuntu -g ubuntu "${mcp_config}" "${admin_config}"
  if [[ -f /seed/mcmcp-fixture-admin/enabled-profile.marker ]]; then
    install -m 0600 -o ubuntu -g ubuntu \
      /seed/mcmcp-fixture-admin/enabled-profile.marker \
      "${admin_config}/enabled-profile.marker"
  fi
  if [[ -d /seed/mcmcp-fixture-admin/fixtures ]]; then
    rm -rf "${admin_config}/fixtures"
    cp -R /seed/mcmcp-fixture-admin/fixtures "${admin_config}/fixtures"
    chown -R ubuntu:ubuntu "${admin_config}/fixtures"
  fi
  exec setpriv --reuid=1000 --regid=1000 --init-groups \
    --inh-caps=-all --ambient-caps=-all --no-new-privs \
    "$0" "$@"
fi

mkdir -p "${PRISM_DATA}" /data/runtime

Xvfb "${DISPLAY}" -screen 0 "${screen_width}x${screen_height}x24" -nolisten tcp \
  > /data/runtime/xvfb.log 2>&1 &
xvfb_pid=$!

cleanup() {
  kill "${prism_pid:-}" "${novnc_pid:-}" "${vnc_pid:-}" "${openbox_pid:-}" \
    "${mcp_relay_pid:-}" "${admin_relay_pid:-}" "${DBUS_SESSION_BUS_PID:-}" \
    "${xvfb_pid:-}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

for _ in $(seq 1 100); do
  xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1 && break
  sleep 0.1
done
xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1

eval "$(dbus-launch --sh-syntax)"
openbox-session > /data/runtime/openbox.log 2>&1 &
openbox_pid=$!

x11vnc -display "${DISPLAY}" -localhost -forever -shared -nopw \
  > /data/runtime/x11vnc.log 2>&1 &
vnc_pid=$!

websockify --web=/usr/share/novnc 0.0.0.0:6080 127.0.0.1:5900 \
  > /data/runtime/novnc.log 2>&1 &
novnc_pid=$!

# The production and admin MODs remain loopback-only inside Minecraft. These
# test-only relays are published by Compose to the remote host's loopback only.
socat TCP-LISTEN:28765,bind=0.0.0.0,reuseaddr,fork TCP:127.0.0.1:8765 \
  > /data/runtime/mcp-relay.log 2>&1 &
mcp_relay_pid=$!
socat TCP-LISTEN:28766,bind=0.0.0.0,reuseaddr,fork TCP:127.0.0.1:18766 \
  > /data/runtime/admin-relay.log 2>&1 &
admin_relay_pid=$!

/opt/prism/AppRun -d "${PRISM_DATA}" --alive > /data/runtime/prism.log 2>&1 &
prism_pid=$!
wait "${prism_pid}"
