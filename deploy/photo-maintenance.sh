#!/usr/bin/env bash
# ============================================================================
# 尘叙阁 · 图片存储巡检与孤儿清理
#
# 为什么需要它：
#   moment_image / photo / album / user 里存的是 /images/xxx.jpg 相对路径。
#   一旦出现「库里有、盘上没有」就是破图，「盘上有、库里没有」就是白占空间。
#   日常唯一必须盯的三件事：磁盘水位、破图、孤儿文件。
#
# 用法：
#   ./photo-maintenance.sh check                 # 只读体检（建议每天 cron 跑）
#   ./photo-maintenance.sh orphans               # 列出孤儿文件（只列，不删）
#   ./photo-maintenance.sh orphans --delete      # 真正删除（默认只删 30 天前的）
#   ./photo-maintenance.sh missing               # 列出库里有、盘上没有的破图
#
# 环境变量（一般写在 /opt/share/share-system.env 里，再 source 进来）：
#   UPLOAD_DIR   默认 /opt/share/uploads
#   URL_PREFIX   默认 /images/
#   DB_HOST / DB_NAME / DB_USER / DB_PASS
#   ORPHAN_MIN_AGE  孤儿文件最小年龄（天），默认 30
#
# 安全约束：
#   1. 只处理 UPLOAD_DIR 下 maxdepth=1 的文件，不递归、不碰子目录
#   2. 只删「库里所有字段都未引用」且「超过 ORPHAN_MIN_AGE 天」的文件
#   3. 删之前一定会先打印清单；不带 --delete 时永远是只读
# ============================================================================
set -uo pipefail

UPLOAD_DIR="${UPLOAD_DIR:-/opt/share/uploads}"
URL_PREFIX="${URL_PREFIX:-/images/}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_NAME="${DB_NAME:-sharing}"
DB_USER="${DB_USER:-share}"
DB_PASS="${DB_PASS:-}"
ORPHAN_MIN_AGE="${ORPHAN_MIN_AGE:-30}"

# 去掉结尾多余的 /
UPLOAD_DIR="${UPLOAD_DIR%/}"
[[ "$URL_PREFIX" != */ ]] && URL_PREFIX="${URL_PREFIX}/"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

die() { echo "错误：$*" >&2; exit 1; }

MYSQL=(mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$DB_NAME")
[[ -n "$DB_PASS" ]] && MYSQL+=("-p${DB_PASS}")

[[ -d "$UPLOAD_DIR" ]] || die "上传目录不存在：$UPLOAD_DIR"

# 库里所有对 /images/ 的引用（主图，不含缩略图）
collect_refs() {
  "${MYSQL[@]}" -e "
    SELECT url FROM moment_image WHERE url LIKE '${URL_PREFIX}%'
    UNION SELECT url FROM photo      WHERE url LIKE '${URL_PREFIX}%'
    UNION SELECT cover FROM album    WHERE cover LIKE '${URL_PREFIX}%'
    UNION SELECT avatar FROM user    WHERE avatar LIKE '${URL_PREFIX}%';
  " 2>/dev/null | sed "s#^${URL_PREFIX}##" | grep -v '^$' | sort -u
}

# abc.png -> abc_t.jpg（与 FileUtil.thumbName 完全一致）
thumb_name() {
  local n="$1" base ext
  base="${n%.*}"; ext="${n##*.}"
  [[ "$base" == "$n" ]] && return          # 没有扩展名就没有缩略图
  echo "${base}_t.jpg"
}

# 白名单 = 所有被引用的主图 + 它们对应的缩略图
build_whitelist() {
  collect_refs > "$TMP/refs.txt"
  : > "$TMP/wl.txt"
  while IFS= read -r name; do
    echo "$name" >> "$TMP/wl.txt"
    thumb_name "$name" >> "$TMP/wl.txt"
  done < "$TMP/refs.txt"
  sort -u "$TMP/wl.txt" -o "$TMP/wl.txt"
}

human() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1 B"; }

cmd_check() {
  echo "========== 图片存储体检 $(date '+%F %T') =========="
  echo "目录      : $UPLOAD_DIR"

  # 磁盘
  local avail_pct
  avail_pct="$(df -P "$UPLOAD_DIR" | awk 'NR==2 {gsub("%","",$5); print $5}')"
  df -h "$UPLOAD_DIR" | awk 'NR==1 || NR==2'
  echo "磁盘使用率: ${avail_pct}%"

  # 体量
  local files total avg
  files="$(find "$UPLOAD_DIR" -maxdepth 1 -type f | wc -l)"
  total="$(du -sb "$UPLOAD_DIR" 2>/dev/null | cut -f1)"
  [[ "$files" -gt 0 ]] && avg=$(( total / files )) || avg=0
  echo "文件数    : $files"
  echo "占用      : $(human "$total")，平均 $(human "$avg")/个"
  echo "图片记录  : $(collect_refs | wc -l) 条（主图，不含缩略图）"

  build_whitelist

  # 孤儿
  find "$UPLOAD_DIR" -maxdepth 1 -type f -mtime +"$ORPHAN_MIN_AGE" -printf '%f\n' \
    | sort > "$TMP/old.txt"
  comm -23 "$TMP/old.txt" "$TMP/wl.txt" > "$TMP/orphans.txt"
  local on osum
  on="$(wc -l < "$TMP/orphans.txt")"
  osum=0
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    osum=$(( osum + $(stat -c '%s' "$UPLOAD_DIR/$f" 2>/dev/null || echo 0) ))
  done < "$TMP/orphans.txt"
  echo "孤儿文件  : $on 个（>${ORPHAN_MIN_AGE} 天未引用），可回收 $(human "$osum")"

  # 破图
  local mn=0
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    [[ -f "$UPLOAD_DIR/$name" ]] || mn=$(( mn + 1 ))
  done < "$TMP/refs.txt"
  echo "破图      : $mn 条引用找不到文件"

  echo
  if [[ "$avail_pct" -ge 80 || "$mn" -gt 0 ]]; then
    echo "结论：需要处理（磁盘 ≥80% 或存在破图）"
    exit 1
  fi
  echo "结论：正常"
}

cmd_orphans() {
  build_whitelist
  find "$UPLOAD_DIR" -maxdepth 1 -type f -mtime +"$ORPHAN_MIN_AGE" -printf '%f\n' \
    | sort > "$TMP/old.txt"
  comm -23 "$TMP/old.txt" "$TMP/wl.txt" > "$TMP/orphans.txt"

  local n sum
  n="$(grep -c . "$TMP/orphans.txt" || true)"
  sum=0
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    sum=$(( sum + $(stat -c '%s' "$UPLOAD_DIR/$f" 2>/dev/null || echo 0) ))
  done < "$TMP/orphans.txt"

  if [[ "$n" -eq 0 ]]; then
    echo "没有孤儿文件。"
    return 0
  fi

  echo "孤儿文件 $n 个，合计 $(human "$sum")（>${ORPHAN_MIN_AGE} 天且未被任何表引用）："
  head -50 "$TMP/orphans.txt" | sed 's/^/  /'
  [[ "$n" -gt 50 ]] && echo "  … 其余 $(( n - 50 )) 个省略"

  if [[ "${2:-}" != "--delete" ]]; then
    echo
    echo "这只是清单，什么都没删。确认无误后执行：$0 orphans --delete"
    return 0
  fi

  echo
  printf '即将删除以上 %s 个文件，5 秒内可 Ctrl-C 取消…\n' "$n"
  sleep 5
  local deleted=0
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    rm -f -- "$UPLOAD_DIR/$f" && deleted=$(( deleted + 1 ))
  done < "$TMP/orphans.txt"
  echo "已删除 $deleted 个文件，释放约 $(human "$sum")。"
}

cmd_missing() {
  collect_refs > "$TMP/refs.txt"
  local mn=0
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    if [[ ! -f "$UPLOAD_DIR/$name" ]]; then
      echo "  缺失: $URL_PREFIX$name"
      mn=$(( mn + 1 ))
    fi
  done < "$TMP/refs.txt"
  [[ "$mn" -eq 0 ]] && echo "没有破图，全部引用都能找到文件。" || echo "共 $mn 条破图。"
  [[ "$mn" -gt 0 ]] && return 1 || return 0
}

case "${1:-check}" in
  check)    cmd_check ;;
  orphans)  cmd_orphans "$@" ;;
  missing)  cmd_missing ;;
  *)        echo "用法：$0 {check|orphans [--delete]|missing}"; exit 2 ;;
esac
