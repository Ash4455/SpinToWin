# 前端：以 Node 建置靜態檔，由 Nginx 提供並把 /api 反向代理到 gateway。
# build context 為 frontend/

FROM node:24.19.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
# Keycloak 位址是瀏覽器看到的位址，在建置時寫入前端
ARG VITE_KEYCLOAK_URL=http://localhost:8080
ARG VITE_KEYCLOAK_REALM=spintowin
ARG VITE_KEYCLOAK_CLIENT_ID=spintowin-frontend
ARG VITE_DEFAULT_CAMPAIGN_ID=1
RUN npm run build

# 1.30.7 含 try_files 相關的安全修正（CVE-2026-125283）
FROM nginx:1.30.7-alpine
ENV GATEWAY_UPSTREAM=http://gateway:8000
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
