# ============================================================
# 前端镜像 —— 多阶段构建
# ============================================================
# 构建阶段：Node 把 React 编译成静态文件
# 运行阶段：nginx 托管这些静态文件，并把 /api 转发给后端
#
# 为什么运行阶段不用 Node：编译完之后 Node 就没用了。
# 用 nginx 托管几个 HTML/JS/CSS 文件，镜像小得多、启动快得多、
# 也没有 Node 运行时的安全问题要跟。
# ============================================================

# ---------- 阶段一：构建 ----------
FROM node:22-alpine AS build

WORKDIR /build

# 允许在构建时指定 npm 源。
# 国内直连 registry.npmjs.org 很慢，可以这样覆盖：
#   docker compose build --build-arg NPM_REGISTRY=https://registry.npmmirror.com
ARG NPM_REGISTRY=https://registry.npmjs.org

# 先装依赖（同样是为了让这一层能被缓存）
COPY package.json package-lock.json ./
# 用 ci 而不是 install：严格按 lock 文件装，且 lock 与 package.json
# 不一致时直接失败。能防止"本地能跑、镜像里装出另一套版本"。
RUN npm ci --registry=$NPM_REGISTRY --no-audit --no-fund

COPY . .

# 构建（含 TypeScript 类型检查，类型错了这一步就会失败）
RUN npm run build

# ---------- 阶段二：运行 ----------
FROM nginx:alpine

# 用我们自己的配置替换默认的
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 只把构建产物拷过去，源码和 node_modules 全部丢弃
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

# ⚠️ 健康检查必须写 127.0.0.1，**不能写 localhost**。
#
# 原因：容器的 /etc/hosts 里同时有这两行
#     127.0.0.1  localhost
#     ::1        localhost
# 而 wget 解析 localhost 时**优先试 IPv6**（::1）。
# 我们的 nginx 配置是 `listen 80;`，只绑定 IPv4（0.0.0.0:80），
# IPv6 上根本没有监听 → connection refused → 容器永远显示 unhealthy。
#
# 这个坑很阴：**从宿主机访问页面完全正常**（走的是 IPv4 端口映射），
# 只有容器内部的健康检查会失败。看 docker compose ps 才发现，
# 而且报错信息 "Connection refused" 会让人以为是 nginx 没起来。
#
# 同类问题在任何"容器内自检"的场景都会出现。
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1
