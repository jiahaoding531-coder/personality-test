# TravelMind：Windows 本机构建 → GCP Ubuntu 单机部署

本文件是操作手册，不代表已执行远程部署。连接服务器、改 DNS、启用付费 API 和切换数据库都需要用户授权。

## 1. 固定边界

- 开发工作区：`<项目目录>`；分支 `feat/travelmind-ui-v2`。
- 当前 HEAD `1e2315dac20289057096aeb9fb5385713562eb81` 不含全部行为系统：V15、V16 及相关实现仍未提交，不能只拉取 HEAD 或 main。
- GCP 实例由用户确认已创建：`travelmind-server`，`asia-east2-c`，公网 IPv4 `35.220.176.74`，Ubuntu 24.04.5，约 3.8 GiB 内存、40 GB 磁盘。当前 IP 为临时地址。
- 不改 RecommendationEngine、餐饮算法或历史迁移；不复制开发数据库、账号、GPS 或本地密钥。
- 使用独立 `deploy/compose.production.yml`，不要叠加根目录开发 Compose。
- 仅 Caddy 发布 TCP 80/443；PostgreSQL、后端、Nginx 和 Caddy 管理端口不发布到宿主机。

## 2. 架构与资源

浏览器 → Caddy HTTPS → `/api/*` 直达 Spring Boot；其余路径 → Nginx 静态页面。后端通过内部 data 网络连接 PostgreSQL。app 网络允许后端向高德、模型和证书服务发起 HTTPS 出站访问。

容器上限：后端 1536 MiB（堆 768 MiB）、PostgreSQL 1024 MiB、Nginx/Caddy 各 128 MiB，总计 2816 MiB；CPU 限制不代表独享核心或并发能力承诺。服务器不运行 Maven、Node 编译或全量测试。首次使用应观察 RSS、OOM、CPU、磁盘和接口延迟，再调整预算。

数据库管理员和应用用户使用不同密码；应用用户是本数据库所有者，能执行 Flyway DDL，但无 SUPERUSER/CREATEDB/CREATEROLE 权限。暂未把迁移用户与运行用户进一步分离。

## 3. 本机构建和测试（PowerShell）

在本工作区串行执行，不能与其他大任务并行：

```powershell
cd <项目目录>
git branch --show-current
git status --short
git diff --check
cd backend
.\mvnw.cmd -B -ntp clean test
.\mvnw.cmd -B -ntp package -DskipTests
cd ..\frontend
npm ci
npm test
npm run build
cd ..
```

后端测试使用既有独立测试库，不是生产库；数据库密码应由当前进程安全配置，禁止复制默认开发密码到部署模板。已有 node_modules 且锁文件未变时可直接运行测试，无需重复 npm ci。

每次发布选择新的不可复用标签，下例是本轮本地验收标签：

```powershell
docker build --pull=false --network=none --platform linux/amd64 -f backend/Dockerfile.production -t travelmind-backend:localcheck-20261012 backend
docker build --pull=false --network=none --platform linux/amd64 -f frontend/Dockerfile.production -t travelmind-frontend:localcheck-20261012 frontend
node --test deploy/tests/production.test.mjs
```

专用 Dockerignore 只允许 JAR 或 dist 及必要配置，`COPY . .` 不用于生产镜像。后端非 root，根文件系统只读。基础镜像固定摘要；更新基础镜像必须重新验证，不能长期不更新补丁。

本地隔离验收使用 `deploy/tests/compose.smoke.yml`，只绑定 127.0.0.1:18080/18443，域名 `travelmind.localhost`。数据库测试会建立独立 `travelmind-deploycheck-20261012` 项目和卷；不会连接开发库。HTTPS 测试需先启动这个验收栈；它信任仅该容器的本地 CA，不关闭 TLS 校验、不向系统安装证书。不要在 GCP 使用 smoke 配置。

```powershell
node --test deploy/tests/database.test.mjs
# 接着执行下方启动命令，再运行：
node --test deploy/tests/https.test.mjs
```

完整 smoke 栈启动命令（仅本机；先运行上面的 database.test.mjs）：

```powershell
cd <项目目录>\deploy
$taskSmoke = (docker inspect travelmind-deploycheck-20261012-postgres-1 | ConvertFrom-Json)[0]
foreach ($taskItem in $taskSmoke.Config.Env) {
    if ($taskItem.StartsWith('POSTGRES_PASSWORD=')) { $env:POSTGRES_ADMIN_PASSWORD = $taskItem.Substring(18) }
    if ($taskItem.StartsWith('DB_APP_PASSWORD=')) { $env:DB_PASSWORD = $taskItem.Substring(16) }
}
$env:DEPLOY_DOMAIN = 'travelmind.localhost'
$env:ACME_EMAIL = 'ops@example.invalid'
$env:RELEASE_TAG = 'localcheck-20261012'
$env:BACKUP_DIR = '<项目目录>/deploy/backups'
$env:AMAP_ENABLED = 'false'
$env:AI_ENABLED = 'false'
docker compose --env-file .env.example -f compose.production.yml -f tests/compose.smoke.yml -p travelmind-deploycheck-20261012 up -d --wait --wait-timeout 120
cd ..
node --test --test-concurrency=1 deploy/tests/production.test.mjs deploy/tests/database.test.mjs deploy/tests/https.test.mjs
```

不要打印 `$taskSmoke` 或这些环境变量；它们只属于隔离测试容器。需要停止验收栈时，使用相同 Compose 参数的 `stop`，保留数据库卷。再次构建镜像后应使用 `up -d --force-recreate backend` 更新本地验收后端，不能把运行旧镜像当成新验证。

上述测试依赖本机 Docker，口令由夹具随机生成，只保存在测试进程与该容器中。验收后保留测试卷和恢复库，不自动删除数据。

## 4. 发布包与传输

先完成测试和敏感文件检查，再导出两个应用镜像；PostgreSQL/Caddy 在服务器从官方仓库按固定摘要拉取，不要求给 VM 绑定服务账户。

```powershell
New-Item -ItemType Directory -Force deploy\artifacts
docker image save -o deploy\artifacts\travelmind-localcheck-20261012-images.tar travelmind-backend:localcheck-20261012 travelmind-frontend:localcheck-20261012
Get-FileHash deploy\artifacts\travelmind-localcheck-20261012-images.tar -Algorithm SHA256
```

另外传输 `deploy/compose.production.yml`、`deploy/Caddyfile`、`deploy/.env.example`、`deploy/postgres/` 和 `deploy/scripts/`；不传 `.env`、备份、.git、.worktrees、日志、node_modules 或测试配置。发布记录须保留基础 HEAD、未提交状态、JAR/dist/镜像哈希及验证结果；当前镜像不是已批准正式上线版本。

实际操作前确认项目 ID。Windows 安装并登录 Google Cloud CLI 后，经授权可使用：

```powershell
gcloud compute ssh travelmind-server --project <项目ID> --zone asia-east2-c
# 先在远端建立 ~/travelmind-upload，再传文件。
gcloud compute scp deploy\artifacts\travelmind-localcheck-20261012-images.tar travelmind-server:~/travelmind-upload/ --project <项目ID> --zone asia-east2-c
```

SSH 用户身份不等于 VM 服务账户。不发送私钥到聊天，不给服务器放个人 GitHub Token。可优先使用 GCP 控制台 SSH；IAP 需要单独配置 IAM/防火墙，不能假设已经启用。

## 5. Ubuntu / Docker 检查与安装（未执行）

先执行 `docker version`、`docker compose version`、`systemctl is-active docker`。用户正在自行确认安装状态；已有可用 Docker 时不要重装。

如尚未安装，按 Docker 官方 apt 仓库流程安装 Engine 与 Compose 插件，不安装 Docker Desktop，不执行卸载/清库步骤：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
# 通过受控编辑器创建 /etc/apt/sources.list.d/docker.sources，内容见下方。
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
```

`docker.sources` 内容（本机用户确认的 Ubuntu 24.04/x86-64）：

```text
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
```

不要开放 Docker Remote API。加入 docker 组近似授予 root 权限，手册默认使用 sudo。审查 GCP VPC 已有规则：应用仅 TCP 80/443；SSH 仅管理来源或 IAP。不要只依赖 UFW，Docker 端口发布可能绕过它。没有 Swap 先不擅自修改系统；通过资源限制和监控验证是否需要另行批准。

## 6. 服务器目录与环境（未执行）

建议：

```text
/opt/travelmind/
  releases/<发布标签>/deploy/   # 无密钥配置、脚本
  shared/.env                  # 服务器唯一真实环境文件，600 权限
  shared/backups/              # 700 权限；备份含个人数据
  current -> releases/<发布标签>
```

由管理员创建目录，限制所有权和权限；使用 sudoedit 填写环境，不在命令历史里直接输入 Key。密码建议分别用 `openssl rand -hex 24` 生成，只在私有终端使用。配置检查只运行 `config -q`，不要把渲染后的 `config` 或 `docker inspect` 环境内容粘贴到聊天。

必须填写：真实证书邮箱、获准使用的域名、发布标签、独立数据库名、应用用户、两个不同的数据库密码、绝对备份目录。

- 高德：服务器设置 `AMAP_ENABLED=true`、Web 服务 `AMAP_KEY`，检查固定出口 IP/账号权限和配额；不进入前端。
- AI：默认服务端 AI 和 BYOK 都关闭，不悄悄替公众付费；需要自然语言体验时，经确认启用服务端 Key 或 BYOK，验证额度、限流与隐私。此开关不改变推荐算法。
- 真实 .env 使用单引号包围可能含 `$` 的敏感值，防止 Compose 插值；不能写入仓库。
- `.env.example` 不是可直接启动的配置：必填项为空会明确失败。

全新卷才执行初始化脚本。修改 `.env` 中数据库密码不会自动修改已有 PostgreSQL 角色密码；密码轮换要独立操作并验证，不能靠删除卷解决。

## 7. 启动顺序（未执行）

把镜像包放在服务器后校验 SHA256；不一致就停止。加载应用镜像，然后在已批准的发布目录运行：

```bash
sudo docker image load -i <镜像包绝对路径>
cd /opt/travelmind/current/deploy
sudo docker compose --env-file /opt/travelmind/shared/.env -f compose.production.yml config -q
sudo docker compose --env-file /opt/travelmind/shared/.env -f compose.production.yml pull postgres caddy
sudo docker compose --env-file /opt/travelmind/shared/.env -f compose.production.yml up -d postgres
sudo docker compose --env-file /opt/travelmind/shared/.env -f compose.production.yml up -d backend frontend
# 检查后端健康、Flyway V1–V16 校验，再启动入口。
sudo docker compose --env-file /opt/travelmind/shared/.env -f compose.production.yml up -d caddy
sudo docker compose --env-file /opt/travelmind/shared/.env -f compose.production.yml ps
```

不在生产跑测试、不使用根目录开发 Compose、不使用 Vite、不执行 Flyway repair，不对未就绪服务开放流量。数据库故障时入口健康检查会收敛错误正文；用户敏感请求正文和上游原文不写访问日志。Docker 每容器日志最多约 30 MB。部分旧日志类在 prod 中关闭，这是隐私优先的取舍，不等于已经建立完整监控平台。

## 8. 域名和 HTTPS（单独授权）

2026-10-12 公共 DNS 显示 Cloudflare NS、已有根域名 A/AAAA，以及 `www` 指向 `aviatote-site.pages.dev`。公共 DNS 看不到橙云后的源站，不能据此覆盖配置。

1. 用户先确认 Cloudflare DNS 记录、Pages 自定义域名和现有网站用途；保存记录备份。
2. 确认无冲突后先批准一个测试子域名；未确认前不写 DNS。
3. 建议先把临时公网 IPv4 升级为固定地址，再绑定正式域名。
4. 第一轮使用 DNS only，Caddy 申请公开受信证书；确认 CAA 允许签发、80/443 可达，不添加指向不可达服务器的 AAAA。
5. 之后如启用橙云，必须 Full (strict)，API 不缓存；独立验证 Cloudflare 可信代理、真实 IP 和限流，不能直接信任任何 CF-Connecting-IP。未经验证不启用橙云代理。
6. 测试域名通过后再经批准切换 aviatote.com；不擅自解绑 Pages 或添加 www 跳转。

Caddy `/data`、`/config` 是持久化卷，不应随更新删除。生产不用本地 CA，不用 `curl -k`，不能关闭手机证书检查。HTTPS 是 GPS 的必要条件之一，手机权限、系统定位和真实网络仍需实测。

## 9. 备份、恢复与回滚

备份命令：

```bash
cd /opt/travelmind/current/deploy
sudo docker compose --env-file /opt/travelmind/shared/.env -f compose.production.yml exec -T postgres bash /maintenance/backup-db.sh
```

输出只有备份文件名；文件 600 权限，先写 partial、通过目录清单检查后原子改名。该检查不能代替恢复演练。不自动删旧备份、不自动复制离机；上线前配置每日定时备份、至少一份加密离机副本和失败告警。可在 root cron 配置每日运行上述完整命令，使用绝对路径；先人工执行成功再安装定时任务。备份所在磁盘故障仍会丢失同盘文件。

恢复命令只允许新名称且显式确认：

```bash
sudo docker compose --env-file /opt/travelmind/shared/.env -f compose.production.yml exec -T postgres bash /maintenance/restore-db.sh <备份文件名> travelmind_restore_20261012 --confirm-new-database
```

必须确认该名称没有被其他应用使用。脚本拒绝已有库、生产库名和路径穿越；单事务恢复，失败保留新库，不删除或覆盖任何旧库。核对 V16、关键表计数和业务后，数据库连接切换仍需独立批准。建议每次升级前备份，平时至少保留 7 份日备份并定期演练；容量和备份隐私保留需运营确认，当前脚本不自动清理。

更新：新标签镜像先在本机验证 → 传输并校验 → 备份 → 确认迁移兼容 → 暂停写入窗口 → 修改共享 .env 的 RELEASE_TAG → 仅重建 backend/frontend → 验收。保留上一版镜像和发布配置，不使用 latest 覆盖发布标签。

应用回滚只在旧镜像兼容新表结构时切回旧标签，不自动回滚 Flyway。数据库回滚是从备份恢复到新库，会涉及备份后的数据损失和写入冻结，需要单独审批。`down -v`、Factory Reset、清库和 repair 不是回滚方案。

## 10. 留存和当前限制

- 行为事件默认 90 天、餐饮快照 7 天；现有清理服务按小事务处理。备份可能保留被清理数据，离机副本也必须纳入隐私留存与删除政策。
- 事件只表示明确动作；NAVIGATION_REQUESTED 不等于到访。没有 TravelMemory、曝光学习或自动画像更新。
- Session、餐饮搜索上下文和前端重试队列仍有内存边界；后端重启可能要求登录/重新搜索，刷新/关页可能丢失未确认事件。
- 用户自助删除尚未实现，只有受控内部删除能力；正式公开上线前补齐用户删除流程、隐私说明和付费接口防滥用。
- 当前建议受控 MVP 试用，不保证中国大陆所有运营商到香港 GCP 的连通质量或高并发能力。
- 服务器费用包含 VM、磁盘、外部 IP、流量等，设置预算告警，不在本轮购买新资源。

## 官方参考

- [Docker Ubuntu 安装](https://docs.docker.com/engine/install/ubuntu/)
- [Caddy 自动 HTTPS](https://caddyserver.com/docs/automatic-https)
- [Spring Boot 反向代理](https://docs.spring.io/spring-boot/how-to/webserver.html)
- [Cloudflare Full strict](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/)
- [GCP 固定外部地址](https://docs.cloud.google.com/vpc/docs/reserve-static-external-ip-address)
