# 前端镜像：两阶段构建。
# ★ 为什么用多阶段而不是"本地 npm build 完再 COPY dist"：
#   那样镜像能否复现取决于构建者的本机状态（node 版本、有没有装依赖）。
#   多阶段把"构建"也装进镜像里，任何机器上 `docker compose build` 结果一致。
FROM node:22-alpine AS build
WORKDIR /app

# 先只拷依赖清单：源码改动时这一层还能命中缓存，不必重装依赖
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- 运行阶段：nginx 既发静态文件，也反代 /api ----
# ★ nginx.conf 不 COPY 进镜像，而是由 compose 挂载（见 deploy/docker-compose.yml）。
#   理由：反代目标是 `api:8081`，属于**部署拓扑**而不是前端产物；
#   把它烤进镜像会让"换个拓扑就要重建镜像"，而挂载只需改一个文件。
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
