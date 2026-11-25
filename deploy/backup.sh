#!/usr/bin/env bash
# ============================================================================
# 尘叙阁 · 数据备份
#
# 备份什么：MySQL 全库 + 用户上传的图片
# 不备份什么：代码（在 git 里）、前端 dist（可重新构建）、Redis（纯缓存，
#             丢了最坏结果是所有人重新登录一次，不值得为它占备份空间）
#
# 用法：
#   ./backup.sh                     # 预检 + 打印将要做什么，不实际执行
#   ./backup.sh --yes               # 真正执行
#   ./backup.sh --yes --no-remote   # 跳过异地同步，只做本地
#
# 为什么默认 dry-run：
#   备份是唯一「看起来天天在做、出事那天才发现是坏的」东西。
#   先看清楚它要动哪些目录，再决定执行 —— 尤其这个脚本里有删除操作（清理旧 dump）。
#
# 顺序：先 dump 库，再同步图。反过来的后果见「为什么顺序重要」那节。
# ============================================================================
set -uo pipefail

# ---------- 配置（全部可用环境变量覆盖）----------
BACKUP_DIR="${BACKUP_DIR:-/data/backup}"
UPLOAD_DIR="${UPLOAD_DIR:-/data/uploads}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-sharing}"
# 兼容 env 文件里的 DB_USERNAME / DB_PASSWORD 命名，source 一下就能用
DB_USER="${DB_USER:-${DB_USERNAME:-share}}"
DB_PASS="${DB_PASS:-${DB_PASSWORD:-}}"

# MySQL 跑在容器里时优先用 docker exec，宿主机就不用装 mysql 客户端
MYSQL_CONTAINER="${MYSQL_CONTAINER:-share-mysql}"

# 异地目标，例如 cos:share-backup 或 oss:share-backup。留空则只做本地备份。
RCLONE_REMOTE="${RCLONE_REMOTE:-}"
# 失败告警 webhook（可选）。钉钉/飞书的自定义机器人可直接用这个 payload；
# 企业微信要改成 {"msgtype":"text","text":{"content":"..."}}。
ALERT_WEBHOOK="${ALERT_WEBHOOK:-}"

# ---------- 参数 ----------
DRY_RUN=1
SKIP_REMOTE=0
for arg in "$@"; do
  case "$arg" in
    --yes|-y)     DRY_RUN=0 ;;
    --no-remote)  SKIP_REMOTE=1 ;;
    -h|--help)    sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            echo "未知参数：$arg（用 --help 看用法）" >&2; exit 2 ;;
  esac
done

LOG_FILE=""
log() {
  local line
  line="[$(date '+%F %T')] $*"
  echo "$line"
  if [[ -n "$LOG_FILE" && -d "$(dirname "$LOG_FILE")" ]]; then
    echo "$line" >> "$LOG_FILE"
  fi
}
warn() { log "警告：$*"; }

notify() {
  [[ -z "$ALERT_WEBHOOK" ]] && return 0
  curl -fsS -X POST -H 'Content-Type: application/json' \
    -d "{\"text\":\"[尘叙阁备份] $1\"}" "$ALERT_WEBHOOK" >/dev/null 2>&1 || true
}

fail() {
  log "失败：$*"
  notify "$*"
  exit 1
}

# 写文件前统一拦一道：dry-run 时只打印不落地
run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    log "  [dry-run] $*"
    return 0
  fi
  log "  $*"
  "$@"
}

# ============================================================================
# 1. 预检
# ============================================================================
log "================ 备份开始 ================"
[[ $DRY_RUN -eq 1 ]] && log "模式：dry-run（只预检不执行，加 --yes 才真正备份）"

[[ -d "$UPLOAD_DIR" ]] || fail "上传目录不存在：$UPLOAD_DIR"
[[ $EUID -eq 0 ]] || warn "当前不是 root，如果 $BACKUP_DIR 需要提权会失败"

# 备份目录：dry-run 时不创建，避免「只是看看」就在磁盘上留东西
if [[ $DRY_RUN -eq 0 ]]; then
  mkdir -p "$BACKUP_DIR/db" || fail "无法创建备份目录：$BACKUP_DIR"
fi
LOG_FILE="$BACKUP_DIR/backup.log"

# 选 dump 方式：容器优先，宿主机客户端兜底
if command -v docker >/dev/null 2>&1 \
   && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$MYSQL_CONTAINER"; then
  DUMP_AS=container
  log "MySQL：走 docker exec $MYSQL_CONTAINER"
elif command -v mysqldump >/dev/null 2>&1; then
  DUMP_AS=local
  log "MySQL：走宿主机 mysqldump"
else
  fail "找不到 mysqldump，也没有运行中的 $MYSQL_CONTAINER 容器"
fi

# 文件同步：rsync 优先（增量、快），没有就退到 cp -au（只拷更新的，慢但等价）
if command -v rsync >/dev/null 2>&1; then
  SYNC_AS=rsync
  log "文件同步：rsync"
else
  SYNC_AS=cp
  warn "没有 rsync，退到 cp -au：功能一样（只增不删），但每次要 stat 全部文件，图多时会慢"
fi

# 空间检查：首次要全量，之后按增量估 10%
src_mb=$(du -sm "$UPLOAD_DIR" 2>/dev/null | cut -f1); src_mb=${src_mb:-0}
if [[ -d "$BACKUP_DIR/uploads" ]]; then
  need_mb=$(( src_mb / 10 + 100 ))
else
  need_mb=$(( src_mb + 100 ))
fi
avail_mb=$(df -Pm "$UPLOAD_DIR" 2>/dev/null | awk 'NR==2 {print $4}'); avail_mb=${avail_mb:-0}
log "图片 ${src_mb}MB，备份需要约 ${need_mb}MB，所在分区剩余 ${avail_mb}MB"
if [[ $avail_mb -lt $need_mb ]]; then
  fail "剩余空间不足（需要约 ${need_mb}MB，只剩 ${avail_mb}MB）。先清理或扩容，别让备份把盘写满 —— 盘满连 MySQL 都会挂"
fi

# ============================================================================
# 2. 先 dump 库
# ============================================================================
# 为什么先库后图：
#   dump 在 T0，同步图片到 T1（T1 > T0）。图片只增不改，所以 T1 一定覆盖 T0
#   引用到的所有文件，恢复后不会破图。
#   反过来做（先图后库），T0~T1 之间上传的图片会被 T1 的库引用、但备份里没有。
#   两种错误的表现差别很大：多几个孤儿文件几乎无害，满站破图是灾难。
STAMP="$(date '+%F')"
DB_FILE="$BACKUP_DIR/db/db-$STAMP.sql.gz"
log "---- 1/3 导出数据库 → $DB_FILE"

dump_db() {
  local args=(--single-transaction --quick --no-tablespaces
              --default-character-set=utf8mb4
              -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME")
  # 密码走 MYSQL_PWD 而不是 -p 参数：命令行上的密码会进 shell history 和 ps 输出
  if [[ "$DUMP_AS" == container ]]; then
    docker exec -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" mysqldump "${args[@]}"
  else
    MYSQL_PWD="$DB_PASS" mysqldump "${args[@]}"
  fi
}

if [[ $DRY_RUN -eq 1 ]]; then
  log "  [dry-run] $DUMP_AS mysqldump $DB_NAME | gzip > $DB_FILE.tmp → mv 到 $DB_FILE"
else
  # 先写临时文件，成功后再改名。直接写目标文件的话，一旦这次失败，
  # 会把「今天早些时候成功的那份」一起覆盖掉又删掉，等于两次都白做。
  TMP_DB="$DB_FILE.tmp"
  # pipefail 已开：管道里任何一段失败都不会被后面的成功掩盖
  dump_db | gzip > "$TMP_DB"
  # 必须立刻存下来：后面任何一条命令（包括 rm）都会重置 PIPESTATUS，
  # 直接在 fail 里展开会拿到 rm 的 0，日志就成了「dump 失败，退出码 0」
  rc=${PIPESTATUS[0]}
  if [[ $rc -ne 0 || ! -s "$TMP_DB" ]]; then
    rm -f "$TMP_DB"
    fail "数据库导出失败（dump 退出码 $rc），已保留上一次成功的备份"
  fi
  # 空库的 dump 压缩后也有几百字节，只有几十字节基本就是出错了
  size=$(stat -c '%s' "$TMP_DB" 2>/dev/null || echo 0)
  if [[ $size -lt 200 ]]; then
    rm -f "$TMP_DB"
    fail "数据库备份文件异常小（${size} 字节），视为失败，已保留上一次成功的备份"
  fi
  mv -f "$TMP_DB" "$DB_FILE"
  log "  完成：$(du -h "$DB_FILE" | cut -f1)"
fi

# ============================================================================
# 3. 再同步图片
# ============================================================================
# 用「只增不删」而不是镜像同步：源目录被误删时，备份里还留着，
# 这正是本地备份存在的意义。代价是已删除的图会一直占着备份空间，可以接受。
log "---- 2/3 同步图片 → $BACKUP_DIR/uploads"

if [[ $SYNC_AS == rsync ]]; then
  # 注意这里没有 --delete，是有意的
  run rsync -a --stats "$UPLOAD_DIR/" "$BACKUP_DIR/uploads/" 2>&1 | tail -5
else
  # cp -a 会在目标不存在时把源目录整个嵌进去（uploads/uploads），
  # 所以先建好目标，再拷 . 的内容
  [[ $DRY_RUN -eq 0 ]] && mkdir -p "$BACKUP_DIR/uploads"
  run cp -a --update "$UPLOAD_DIR/." "$BACKUP_DIR/uploads/"
fi

# ============================================================================
# 4. 清理过期 dump
# ============================================================================
log "---- 3/3 清理 ${RETENTION_DAYS} 天前的数据库备份"
old=$(find "$BACKUP_DIR/db" -maxdepth 1 -type f -name 'db-*.sql.gz' -mtime +"$RETENTION_DAYS" 2>/dev/null)
if [[ -z "$old" ]]; then
  log "  没有需要清理的文件"
else
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    run rm -f "$f"
  done <<< "$old"
fi

# ============================================================================
# 5. 同步到异地
# ============================================================================
# 本地备份和图片在同一块盘上，防得了手滑、防不了盘坏和整机释放。
# 所以「异地」这一层不是可选项 —— 没有它，这套备份只能解决一半问题。
if [[ $SKIP_REMOTE -eq 1 ]]; then
  log "---- 跳过异地同步（--no-remote）"
elif [[ -z "$RCLONE_REMOTE" ]]; then
  warn "未配置 RCLONE_REMOTE，本次只做了本地备份。"
  warn "本地备份所在磁盘坏掉就等于没备份，建议尽快配置异地（见 DEPLOY.md 第 9 节）"
elif ! command -v rclone >/dev/null 2>&1; then
  warn "配置了 RCLONE_REMOTE 但宿主机没有 rclone，跳过异地同步"
else
  log "---- 4/4 同步到异地 → $RCLONE_REMOTE"
  # 源目录本身「只增不删」，所以这里用 sync 也不会误删备份；
  # 加 --transfers 4 是因为小文件多，单线程传起来很慢
  run rclone sync "$BACKUP_DIR" "$RCLONE_REMOTE" \
      --transfers 4 --checkers 8 --stats-one-line --stats 30s \
    || fail "异地同步失败（本地备份仍在，但异地那份没更新）"
fi

# ============================================================================
# 汇总
# ============================================================================
total=$(du -sh "$BACKUP_DIR" 2>/dev/null | cut -f1)
dumps=$(find "$BACKUP_DIR/db" -maxdepth 1 -name 'db-*.sql.gz' 2>/dev/null | wc -l)
if [[ $DRY_RUN -eq 1 ]]; then
  log "================ 预检完成 ================"
  log "以上为 dry-run 结果，没有写入任何文件（加 --yes 才真正备份）"
else
  log "================ 备份完成 ================"
fi
log "备份目录：$BACKUP_DIR（${total:-尚未创建}）"
log "数据库备份：$dumps 份，保留 $RETENTION_DAYS 天"
log "图片：$(du -sh "$BACKUP_DIR/uploads" 2>/dev/null | cut -f1 || echo '-')"
exit 0
