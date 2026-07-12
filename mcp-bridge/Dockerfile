# ---- Build stage ----
FROM node:20-bookworm-slim AS builder
WORKDIR /app

# Copy manifest first for better layer caching.
COPY package.json package-lock.json* tsconfig.json ./
RUN npm install --no-audit --no-fund

COPY src ./src
RUN npm run build

# ---- Runtime stage ----
FROM node:20-bookworm-slim AS runtime
WORKDIR /app

ENV NODE_ENV=production
ENV PORT=10000

COPY package.json package-lock.json* ./
RUN npm install --omit=dev --no-audit --no-fund && \
    npm cache clean --force

COPY --from=builder /app/dist ./dist

# Render runs as non-root by default; ensure we can write nothing to disk.
USER node

EXPOSE 10000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+process.env.PORT+'/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"

CMD ["node", "dist/index.js"]
