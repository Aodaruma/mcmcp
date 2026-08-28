FROM eclipse-temurin:25-jre-noble

ARG PRISM_VERSION=11.0.3
ARG PRISM_SHA256=0823a0e5f53694f0ff65a9523b6f221e6f10565b904941fa9522d89e8e7c69d1

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates curl dbus-x11 libasound2t64 libfontconfig1 libfreetype6 \
        libgl1 libgl1-mesa-dri libglx-mesa0 libnss3 libpulse0 libxcomposite1 \
        libxcursor1 libxi6 libxkbcommon-x11-0 libxrandr2 libxtst6 locales \
        mesa-utils novnc openbox procps socat tzdata websockify x11-utils \
        x11vnc xdotool xfonts-base xvfb \
    && rm -rf /var/lib/apt/lists/*

RUN curl --fail --location --retry 3 \
        "https://github.com/PrismLauncher/PrismLauncher/releases/download/${PRISM_VERSION}/PrismLauncher-Linux-x86_64.AppImage" \
        --output /tmp/prism.AppImage \
    && echo "${PRISM_SHA256}  /tmp/prism.AppImage" | sha256sum --check --strict \
    && chmod 0755 /tmp/prism.AppImage \
    && cd /opt \
    && /tmp/prism.AppImage --appimage-extract >/dev/null \
    && mv squashfs-root prism \
    && rm /tmp/prism.AppImage

RUN install -d -o ubuntu -g ubuntu /data /home/ubuntu/.cache \
    && ln -sf /usr/share/novnc/vnc.html /usr/share/novnc/index.html

COPY --chmod=0755 entrypoint.sh /usr/local/bin/mcmcp-validation-entrypoint

USER ubuntu
WORKDIR /data

ENV DISPLAY=:99 \
    HOME=/home/ubuntu \
    LANG=C.UTF-8 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    PRISM_DATA=/data/prism \
    TZ=Asia/Tokyo

EXPOSE 6080 28765 28766

ENTRYPOINT ["/usr/local/bin/mcmcp-validation-entrypoint"]
