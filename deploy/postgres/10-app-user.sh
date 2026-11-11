#!/usr/bin/env bash
# 仅全新生产卷初始化时执行；应用用户可管理本库迁移，但不是超级用户。
set -euo pipefail
: "${DB_APP_USER:?缺少应用用户}"
: "${DB_APP_PASSWORD:?缺少应用口令}"
if [[ ! "$DB_APP_USER" =~ ^[a-z][a-z0-9_]{2,40}$ || "$DB_APP_USER" == postgres ]]; then
    echo '应用用户名称不合法' >&2; exit 1
fi
psql --username postgres --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
    --set app_user="$DB_APP_USER" --set app_password="$DB_APP_PASSWORD" --set app_db="$POSTGRES_DB" <<'SQL'
CREATE ROLE :"app_user" WITH LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER DATABASE :"app_db" OWNER TO :"app_user";
SQL
