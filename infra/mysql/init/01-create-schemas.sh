#!/bin/bash
# MySQL 首次初始化（資料目錄為空時）執行一次：建立各服務自己的 schema，並只授權給應用程式帳號。
# 新增服務 schema 時加在 SCHEMAS。改這個檔案後需 `docker compose down -v` 才會重新執行。
set -euo pipefail

SCHEMAS="campaign record"

for schema in $SCHEMAS; do
  mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS \`${schema}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON \`${schema}\`.* TO '${MYSQL_USER}'@'%';
SQL
done
mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" -e "FLUSH PRIVILEGES;"
