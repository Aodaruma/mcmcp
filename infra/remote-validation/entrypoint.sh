#!/usr/bin/env bash
set -euo pipefail

screen_width="${SCREEN_WIDTH:-1280}"
screen_height="${SCREEN_HEIGHT:-720}"

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
