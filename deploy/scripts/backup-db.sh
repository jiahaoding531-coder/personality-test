#!/usr/bin/env bash
# 在本部署 postgres 容器内运行；不读取宿主机开发配置。
set -euo pipefail
umask 077
: "${POSTGRES_DB:?缺少数据库名}"
: "${DB_APP_USER:?缺少应用用户}"
[[ -d /backups ]] || { echo '备份目录未挂载' >&2; exit 1; }
temporary=$(mktemp "/backups/travelmind-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXXXXXX.partial")
backup_name=$(basename "${temporary%.partial}.dump")
pg_dump --username "$DB_APP_USER" --dbname "$POSTGRES_DB" --format custom --no-owner --file "$temporary"
pg_restore --list "$temporary" >/dev/null
# 同目录原子改名；失败的 partial 不算备份，不自动删除历史备份。
mv --no-clobber -- "$temporary" "/backups/$backup_name"
[[ ! -e "$temporary" ]] || { echo '备份名称冲突，保留半成品，拒绝覆盖历史文件' >&2; exit 1; }
echo "$backup_name"
