#!/usr/bin/env bash
# ============================================================================
# 尘叙阁 · 数据恢复
#
# 为什么要有这个脚本：没验证过的备份等于没有备份。
#   「每天跑 mysqldump」和「真能恢复出来」之间隔着好几件事：字符集、
#   表顺序、外键、压缩包是否完整。等真丢数据那天才发现备份是坏的，就晚了。
#
# 默认行为是安全的：恢复到临时库 / 临时目录，绝不碰生产。
# 要覆盖生产必须显式加 --force，并且会要求你输入二次确认。
#
# 用法：
#   ./restore.sh list                              # 看有哪些备份
#   ./restore.sh db                                # 恢复最新一份库到 sharing_restore
#   ./restore.sh db backup/db/db-2026-11-25.sql.gz # 恢复指定文件
#   ./restore.sh db --database sharing --force     # 真恢复到生产库（危险）
#   ./restore.sh files                             # 恢复图片到 /tmp/restore-uploads
#   ./restore.sh files --to /data/uploads --force  # 真覆盖线上图片（危险）
#
# 演练正确姿势：恢复到一个临时位置 → 用 nginx 指过去或直接查库 → 点开看图
# ============================================================================
set -uo pipefail

BACKUP_DIR="${BACKUP_DIR:-/data/backup}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-${DB_USERNAME:-share}}"
DB_PASS="${DB_PASS:-${DB_PASSWORD:-}}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-share-mysql}"

# 默认恢复到这两个临时位置，出了事也不影响线上
RESTORE_DB_DEFAULT="${RESTORE_DB_DEFAULT:-sharing_restore}"
RESTORE_DIR_DEFAULT="${RESTORE_DIR_DEFAULT:-/tmp/restore-uploads}"

log() { echo "[$(date '+%F %T')] $*"; }
die() { echo "错误：$*" >&2; exit 1; }

usage() { sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

# 选 MySQL 客户端：容器优先，宿主机兜底
if command -v docker >/dev/null 2>&1 \
   && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$MYSQL_CONTAINER"; then
  MYSQL_AS=container
else
  MYSQL_AS=local
  command -v mysql >/dev/null 2>&1 || die "找不到 mysql 客户端，也没有运行中的 $MYSQL_CONTAINER"
fi

mysql_run() {
  if [[ "$MYSQL_AS" == container ]]; then
    docker exec -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" mysql -h 127.0.0.1 -P 3306 -u "$DB_USER" "$@"
  else
    MYSQL_PWD="$DB_PASS" mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$@"
  fi
}

# ============================================================================
# list
# ============================================================================
cmd_list() {
  log "备份目录：$BACKUP_DIR"
  echo
  echo "── 数据库备份 ──"
  if compgen -G "$BACKUP_DIR/db/db-*.sql.gz" > /dev/null; then
    ls -lht "$BACKUP_DIR"/db/db-*.sql.gz | awk '{printf "  %-30s %8s  %s %s %s\n", $9, $5, $6, $7, $8}'
  else
    echo "  （无）"
  fi
  echo
  echo "── 图片备份 ──"
  if [[ -d "$BACKUP_DIR/uploads" ]]; then
    echo "  $BACKUP_DIR/uploads  $(du -sh "$BACKUP_DIR/uploads" | cut -f1)  $(find "$BACKUP_DIR/uploads" -maxdepth 1 -type f | wc -l) 个文件"
  else
    echo "  （无）"
  fi
}

# ============================================================================
# db
# ============================================================================
cmd_db() {
  local target="$RESTORE_DB_DEFAULT" force=0 file=""
  # 第一个位置参数只有当它不是 -- 开头时才当文件名，
  # 否则 `./restore.sh db --database xxx` 会把 --database 当成文件
  if [[ $# -gt 0 && "$1" != --* ]]; then
    file="$1"; shift
  fi
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --database) target="${2:-}"; shift 2 ;;
      --force)    force=1; shift ;;
      *)          die "未知参数：$1" ;;
    esac
  done

  # 没指定文件就用最新的一份
  if [[ -z "$file" ]]; then
    file=$(ls -t "$BACKUP_DIR"/db/db-*.sql.gz 2>/dev/null | head -1)
    [[ -n "$file" ]] || die "在 $BACKUP_DIR/db 下找不到任何备份"
    log "未指定文件，使用最新一份：$file"
  fi
  [[ -f "$file" ]] || die "文件不存在：$file"

  # 覆盖生产库必须显式 --force + 手输确认
  if [[ "$target" != "$RESTORE_DB_DEFAULT" && $force -ne 1 ]]; then
    die "目标库是 $target（不是默认的 $RESTORE_DB_DEFAULT）。确认真要覆盖请加 --force"
  fi
  if [[ $force -eq 1 ]]; then
    echo
    echo "!!! 即将把备份导入库「$target」，该库现有数据会被覆盖 !!!"
    read -r -p "确认请输入库名 $target：" ans
    [[ "$ans" == "$target" ]] || die "输入不匹配，已取消"
  fi

  # 先验证压缩包完整，避免导到一半才发现文件坏了
  gzip -t "$file" 2>/dev/null || die "备份文件损坏（gzip 校验失败）：$file"

  local size; size=$(du -h "$file" | cut -f1)
  log "源文件：$file（$size）"
  log "目标库：$target"

  mysql_run -e "CREATE DATABASE IF NOT EXISTS \`$target\` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" \
    || die "无法创建库 $target（权限不足？需要 CREATE 权限）"

  log "导入中…"
  if [[ "$MYSQL_AS" == container ]]; then
    zcat "$file" | docker exec -i -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" \
      mysql -h 127.0.0.1 -u "$DB_USER" "$target" || die "导入失败"
  else
    zcat "$file" | MYSQL_PWD="$DB_PASS" mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$target" \
      || die "导入失败"
  fi

  # 导入后立刻做几项体检，把「备份是空的」这类问题当场暴露出来
  # tr -d '\r'：从 Windows 客户端跑时 mysql 输出带 CR，会让数值比较和文件路径判断出错
  log "导入完成，开始校验："
  local tables moments imgs
  tables=$(mysql_run -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$target';" | tr -d '\r')
  moments=$(mysql_run -N -B -e "SELECT COUNT(*) FROM \`$target\`.moment;" 2>/dev/null | tr -d '\r' || echo "?")
  imgs=$(mysql_run -N -B -e "SELECT COUNT(*) FROM \`$target\`.moment_image;" 2>/dev/null | tr -d '\r' || echo "?")
  echo "  表数量        : $tables（含 flyway_schema_history，业务表应 11 张）"
  echo "  动态条数      : $moments"
  echo "  图片记录      : $imgs"

  if [[ "$tables" -lt 11 ]]; then
    log "警告：表数量少于 11，这份备份可能不完整"
  fi

  cat <<EOF

下一步请你手动确认（这几项脚本没法替你判断）：
  1. 动态和评论的正文是中文，不是乱码
     mysql -e "SELECT id,LEFT(content,20) FROM \`$target\`.moment LIMIT 5;"
  2. 图片引用是否都能在备份里找到 —— 直接复用巡检脚本，不用另写一套：
     DB_NAME=$target UPLOAD_DIR=$BACKUP_DIR/uploads $(dirname "$0")/photo-maintenance.sh missing
     输出为空才说明「库里有引用的图，备份里都有」，恢复后不会破图
  3. 真要切到线上：停后端 → 把 \`$target\` 改名成生产库 → 起后端

验证完删掉临时库：
  DROP DATABASE \`$target\`;
EOF
}

# ============================================================================
# files
# ============================================================================
cmd_files() {
  local to="$RESTORE_DIR_DEFAULT" force=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --to)    to="${2:-}"; shift 2 ;;
      --force) force=1; shift ;;
      *)       die "未知参数：$1" ;;
    esac
  done

  local src="$BACKUP_DIR/uploads"
  [[ -d "$src" ]] || die "图片备份不存在：$src"

  if [[ "$to" != "$RESTORE_DIR_DEFAULT" && $force -ne 1 ]]; then
    die "目标目录是 $to（不是默认的 $RESTORE_DIR_DEFAULT）。确认真要写入请加 --force"
  fi

  local n_src n_dst
  n_src=$(find "$src" -maxdepth 1 -type f | wc -l)
  log "源：$src（$n_src 个文件，$(du -sh "$src" | cut -f1)）"
  log "目标：$to"

  mkdir -p "$to" || die "无法创建目录：$to"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a "$src/" "$to/" || die "复制失败"
  else
    cp -a "$src/." "$to/" || die "复制失败"
  fi

  n_dst=$(find "$to" -maxdepth 1 -type f | wc -l)
  log "完成：目标目录 $n_dst 个文件"
  [[ "$n_dst" -eq "$n_src" ]] && log "文件数与源一致" || log "警告：文件数不一致（源 $n_src / 目标 $n_dst）"

  cat <<EOF

验证图片能不能正常打开：
  1. 随便挑一张看文件头和大小：file $to/* | head -5
  2. 用 nginx 临时指过去，或直接复制到 /data/uploads 后在页面上点开
  3. 重点看：不是 0 字节、能显示出来（缩略图 _t.jpg 也要在）
EOF
}

# ============================================================================
# 入口
# ============================================================================
cmd="${1:-}"
[[ -z "$cmd" ]] && usage
shift || true

case "$cmd" in
  list)  cmd_list "$@" ;;
  db)    cmd_db "$@" ;;
  files) cmd_files "$@" ;;
  -h|--help|help) usage ;;
  *)     die "未知子命令：$cmd（用 --help 看用法）" ;;
esac
