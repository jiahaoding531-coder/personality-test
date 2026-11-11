#!/usr/bin/env bash
# 只恢复到新库，不清空、不覆盖、不删除任何已有数据库。
set -euo pipefail
if [[ "$#" != 3 || "$3" != --confirm-new-database ]]; then
    echo '用法：restore-db.sh 备份文件名 travelmind_restore_名称 --confirm-new-database' >&2; exit 2
fi
backup_name="$1"
target_db="$2"
[[ "$backup_name" =~ ^travelmind-[A-Za-z0-9_-]+\.dump$ ]] || { echo '备份文件名不合法' >&2; exit 2; }
[[ "$target_db" =~ ^travelmind_restore_[a-z0-9_]{1,30}$ ]] || { echo '只能恢复到新的 travelmind_restore_ 数据库' >&2; exit 2; }
[[ "$target_db" != "$POSTGRES_DB" && -f "/backups/$backup_name" ]] || { echo '目标或备份不合法' >&2; exit 2; }
exists=$(psql --username postgres --dbname postgres -At --set ON_ERROR_STOP=1 \
    --set restore_db="$target_db" <<'SQL'
SELECT count(*) FROM pg_database WHERE datname = :'restore_db';
SQL
)
[[ "$exists" == 0 ]] || { echo '目标数据库已经存在，拒绝覆盖' >&2; exit 3; }
pg_restore --list "/backups/$backup_name" >/dev/null
createdb --username postgres --owner "$DB_APP_USER" "$target_db"
# 单事务恢复；失败保留新库供排查，不自动删库重试。
pg_restore --username "$DB_APP_USER" --dbname "$target_db" --no-owner \
    --single-transaction --exit-on-error "/backups/$backup_name"
echo "已恢复到独立新库：$target_db；尚未切换应用连接"
