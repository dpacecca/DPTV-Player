# Build the web page.
FROM node:22-alpine AS web
WORKDIR /app/web
COPY web/package.json web/package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund
COPY web/ ./
RUN npm run build

# Run the small Node server that signs in to DPTV-Server, relays the guide and the streams, and serves the page.
FROM node:22-alpine
# The DPTV-Player release this image was built with (the release workflow passes it).
ARG VERSION=dev
ENV NODE_ENV=production PORT=3000 DATA_DIR=/data DPTV_WEB_VERSION=$VERSION
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev --no-audit --no-fund || npm install --omit=dev --no-audit --no-fund
COPY server/ ./server/
COPY --from=web /app/web/dist ./web/dist
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s CMD wget -qO- http://127.0.0.1:3000/healthz >/dev/null || exit 1
CMD ["node", "server/index.js"]
