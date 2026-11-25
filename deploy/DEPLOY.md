# 尘叙阁 · 上线部署手册（Docker Compose + 宿主机 nginx）

适用：Ubuntu 22.04 / Debian 12，2核4G 起。第一次部署按顺序做完即可。

## 目标结构

```
/opt/share/                    可重建：代码、编排、前端包（进 git，随时可以推倒重来）
├── docker-compose.yml
├── Dockerfile
├── .dockerignore
├── target/*.jar               后端包，本地或 CI 打包后上传
├── deploy/
│   ├── share-system.env       真实密码与密钥（600 权限，不进 git）
│   ├── nginx-share-system.conf
│   └── photo-maintenance.sh
└── frontend/dist/             前端构建产物

/data/                         不可重建：只进备份，永不随便动
├── mysql/                     数据库文件
├── uploads/                   图片（nginx 直接读这里）
└── redis/                     Redis AOF
```

**一条必须守住的规矩**：任何 `rsync --delete`、`rm -rf`、清理脚本，作用范围只能是 `/opt/share`。
这条守住了，就不会因为一次发版把几年的照片删光。

### 为什么 nginx 不在容器里

nginx 在这套部署里只做两件事：托管静态文件、直接从磁盘发图片。都是宿主机上的静态行为，
装进容器只多一层网络和一层排查，收益接近零。而 HTTPS 证书（certbot）在宿主机上申请、
续期、配 cron 都最直接。

所以 nginx 留在宿主机，backend 把端口发布到 `127.0.0.1:8080` 供它反代。

---

## 0. 本地打包

```bash
mvn -o clean package -DskipTests
# 产物：target/share-system-0.0.1-SNAPSHOT.jar
```

打包同时会构建前端（另一份仓库）：

```bash
cd ../share-frontend && npm ci && npm run build   # 产物 dist/
```

---

## 1. 服务器初始化

```bash
apt update && apt upgrade -y

# Docker（官方一键脚本；介意的话按 docs.docker.com 用 apt 源装）
curl -fsSL https://get.docker.com | sh
docker --version && docker compose version

# nginx 与证书工具
apt install -y nginx certbot python3-certbot-nginx

# 目录：可重建的放 /opt，不可重建的放 /data
mkdir -p /opt/share/{frontend,deploy}
mkdir -p /data/{mysql,uploads,redis}

# 属主必须对，否则容器里的进程写不进去，报 Permission denied：
#   uploads  → 后端容器内的 share 用户（UID 固定在 Dockerfile 里为 10001）
#   mysql    → mysql 镜像的 mysql 用户（999）
#   redis    → redis 镜像的 redis 用户（999）
chown -R 10001:10001 /data/uploads
chown -R 999:999 /data/mysql /data/redis
chmod 755 /data/uploads          # nginx 要能读进这个目录
```

> 如果买的是只有一块系统盘的入门机型，`/data` 就是普通目录，照上面做就行。
> 将来加了数据盘，把它挂到 `/data` 上即可，应用和脚本一行都不用改 —— 这是这套目录规划
> 最大的价值：**先用目录当边界，以后把目录换成挂载点**。

---

## 2. 配置环境变量

```bash
cd /opt/share

# 代码：git clone 或 rsync 上去，二选一
git clone https://github.com/76we/share-system.git .

# 环境变量：从模板复制，然后逐项替换「换成xxx」
cp deploy/share-system.env.example deploy/share-system.env
chmod 600 deploy/share-system.env
vi deploy/share-system.env
```

必填的五项，其余保持默认即可：

| 变量 | 说明 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | 数据库 root 密码，只在容器内用 |
| `MYSQL_PASSWORD` | 业务账号密码 |
| `DB_PASSWORD` | **必须和 `MYSQL_PASSWORD` 一模一样** |
| `JWT_SECRET` | `openssl rand -base64 48` 生成，≥32 字符 |
| `MAIL_USERNAME` / `MAIL_AUTH_CODE` | QQ 邮箱 + SMTP 授权码（不是登录密码） |
| `SHARE_BASE_URL` | `https://你的域名`，分享卡片要用绝对地址 |

> **两个容易踩的格式问题**：
> 1. 值不要加引号。`env_file` 不是 shell，某些 compose 版本会把引号当成值的一部分，
>    于是 `PHOTO_PURGE_CRON` 变成带引号的字符串，cron 解析失败、定时任务静默不跑。
> 2. `DB_USERNAME`/`DB_PASSWORD` 必须与 `MYSQL_USER`/`MYSQL_PASSWORD` 一致。
>    不一致时容器本身是健康的，只有应用连不上 —— 很容易误判成代码问题。

---

## 3. 启动

```bash
cd /opt/share

# 传 jar（本地执行）：
#   scp target/share-system-0.0.1-SNAPSHOT.jar root@your.server:/opt/share/target/

docker compose up -d --build
docker compose ps                # 三个容器都应该是 Up (healthy)
docker compose logs -f backend   # 看到 "Started ShareSystemApplication" 即成功
```

**首次启动会慢**：MySQL 要初始化数据目录（40–60 秒），backend 会等它健康后才启动
（`depends_on: condition: service_healthy`）。这段时间 `docker compose ps` 里 backend
显示 `Created` 或 `Starting` 是正常的。

启动过程中 backend 日志里会出现这段，说明表结构已由 Flyway 自动建好：

```
Successfully baselined schema with version: 1        ← 已有数据的库
Migrating schema `sharing` to version "1 - init schema"
Successfully applied 1 migration to schema `sharing`, now at version v1   ← 空库首次
```

验证：

```bash
# 表数量应为 12（11 张业务表 + flyway_schema_history）
docker compose exec mysql sh -c \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "use sharing; show tables;"'

# 迁移记录
docker compose exec mysql sh -c \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "use sharing; select version,type,success from flyway_schema_history;"'

# 接口
curl -i http://127.0.0.1:8080/topics      # 公开接口，200 + JSON
curl -i http://127.0.0.1:8080/profile     # 需要登录，401
curl -s http://127.0.0.1:8080/actuator/health   # {"status":"UP"}
```

> 用 `-u"$MYSQL_USER" -p"$MYSQL_PASSWORD"` 而不是明文密码，是因为这两个变量已经在
> 容器环境里，命令进 shell history 也不会泄露密码。

---

## 4. 表结构变更（Flyway）

新增一个迁移文件，跟着应用一起发布，启动时自动执行。例：给动态加一个「是否置顶」：

```sql
-- 文件名：src/main/resources/db/migration/V2__add_moment_pinned.sql
ALTER TABLE `moment`
  ADD COLUMN `pinned` tinyint NOT NULL DEFAULT '0' COMMENT '是否置顶' AFTER `mood`;
```

四条规则：

1. **文件名必须是 `V<数字>__<说明>.sql`**（两个下划线），版本号递增。别跳号后又回头补。
2. **已执行过的脚本一个字都不能改。** Flyway 会比对 checksum，改了直接启动失败
   （`Migration checksum mismatch`）。要修正就新增下一个版本去改它。
3. **MySQL 的 DDL 不支持事务回滚。** 迁移执行到一半失败，已生效的部分会留在库里，
   要手工修好再删掉历史表里那条 `success=0` 的记录，然后重启重跑。所以改表前先备份。
4. **上线前先看会跑什么。** 启动日志里的 `Migrating schema ... to version "2 - xxx"`
   就是本次要执行的内容，不确定先在测试库跑一遍。

> 唯一的例外：某个脚本刚发出去就发现写错了，且**只有你一个人用过这套库**，
> 可以改脚本后让 Flyway 重算 checksum。有其他人在用时禁止 —— 会篡改历史。

---

## 5. 前端构建与发布

```bash
# 本地
cd ../share-frontend && npm ci && npm run build

# 上传：--delete 只作用于 dist 目录，绝不要对整个 /opt/share 执行
rsync -av --delete dist/ root@your.server:/opt/share/frontend/dist/
```

前端不用重启任何服务 —— nginx 直接读磁盘上的新文件。

> 接口地址是相对路径 `/api`，靠 nginx 的 `proxy_pass .../` 去掉前缀。
> 后端换端口只改 nginx，不用重新构建前端。

---

## 6. nginx

```bash
cp /opt/share/deploy/nginx-share-system.conf /etc/nginx/conf.d/share-system.conf
vi /etc/nginx/conf.d/share-system.conf    # 改 server_name 为你的域名
nginx -t && systemctl reload nginx
```

配置文件里三处需要对齐，改路径时一起改：

```nginx
location /images/ {
    alias /data/uploads/;      # ① 必须是绝对路径且带结尾斜杠
}
location /api/ {
    proxy_pass http://127.0.0.1:8080/;   # ② backend 发布的回环端口，末尾斜杠去前缀
}
root /opt/share/frontend/dist;           # ③ 前端产物
```

HTTPS：

```bash
certbot --nginx -d your.domain.com       # 自动改配置并配好续期
systemctl list-timers | grep certbot     # 确认续期任务存在
```

---

## 7. 验证清单

| 检查项 | 做法 | 期望 |
|---|---|---|
| 容器状态 | `docker compose ps` | 三个都是 Up (healthy) |
| 表结构 | 见 §3 的 `show tables` | 12 张表 |
| 迁移记录 | `flyway_schema_history` | 1 条，`success=1` |
| 前端能打开 | 浏览器访问域名 | 首页正常，不是 502 |
| 接口连通 | 登录一次 | 拿到 token，首页有动态 |
| 图片能加载 | 打开任意带图动态 | 200，不是 404 |
| 上传写入 | 发一条带图动态 | `/data/uploads/` 出现新文件 |
| 越权拦截 | 对别人的动态点删除 | 「无权限修改该动态」 |
| 分页上限 | `?size=10000` | 最多返回 50 条 |
| 异常有日志 | 请求不存在的接口 | `docker compose logs backend` 有 ERROR 堆栈 |
| 重启自愈 | `docker compose restart backend` | 10 秒内恢复 |
| 开机自启 | `reboot` 后等 2 分钟 | 三个容器自动起来 |

> 健康检查只暴露 `/actuator/health` 且 `show-details: never`（不吐数据库地址）。
> nginx 只反代 `/api/`，所以它不出现在公网。邮件探针已关闭 —— SMTP 抖动不该让服务被判为不健康。

---

## 8. 日常操作与回滚

```bash
cd /opt/share

docker compose ps                      # 状态
docker compose logs -f --tail=100 backend   # 实时日志
docker compose restart backend         # 只重启后端
docker compose up -d                   # 按当前配置重新拉起（改了 compose 或 env 后用）

# 发版：重建 backend，mysql/redis 不受影响（这是 compose 相比手工脚本最实际的好处）
docker compose up -d --build backend

# 回滚：切到某个历史版本
BACKEND_TAG=1.2.0 docker compose up -d backend
```

镜像带版本标签就是为了回滚。只用 `latest` 的话，出问题时只能靠 `git checkout` + 重新
build，而那一刻你往往正慌，记不清上次好的版本是哪个 commit。

**容器日志有上限**：compose 里配了 `max-size: 20m` / `max-file: 5`，不会把
`/var/lib/docker` 撑爆（默认的 json-file 是无限增长的）。

---

## 9. 图片存储与备份

### 存储位置

图片在 `数据盘/uploads`，由 nginx 直接从磁盘发给浏览器，**不经过后端**：

```nginx
location /images/ {
    alias /data/uploads/;      # 与 UPLOAD_DIR 必须逐字符一致
    expires 30d;
}
```

这样做的收益是快（零拷贝，比走一遍 Spring 拦截器快一个数量级）且省线程
（九宫格一屏 9 张图，走后端就是 9 个线程在传文件）。

数据库里只存 `/images/xxx.jpg` 这样的相对路径，所以换机器、换域名时数据不用改。

### 备份：三层，缺一层都不算安全

| 层 | 内容 | 频率 | 保留 | 防什么 |
|---|---|---|---|---|
| 第一份 | `/data/mysql`、`/data/uploads` | 实时 | - | 这是正在用的数据，不是备份 |
| 第二份 | `/data/backup` 同机 | 每天 03:00 | 7 天 | 误删、手滑 |
| 第三份 | 对象存储异地 | 每天 | 30 天 | 磁盘坏、整机丢失、欠费释放 |

后三种故障只有第三层能救。同机备份防的是手滑，异地备份防的是灭机 —— 只做前者等于没备份。

命令已经封进 `deploy/backup.sh`，不用自己拼：

```bash
cd /opt/share

./deploy/backup.sh              # 预检：打印要做什么，一个文件都不写
./deploy/backup.sh --yes        # 真正执行
./deploy/backup.sh --yes --no-remote   # 跳过异地同步
```

它做四件事：`mysqldump` 导出全库 → 同步图片 → 清理 7 天前的 dump → `rclone` 传异地。

几个刻意的设计：

- **默认 dry-run**。备份是唯一「看起来天天在做、出事才发现是坏的」东西，先看清楚再执行。
- **先库后图**。反过来的结果是「库里有引用、备份里没文件」，恢复后满站破图；
  先库后图最多多几个孤儿文件，`photo-maintenance.sh orphans` 还能清掉。
- **图片只增不删**（`rsync` 不带 `--delete`）。源目录被误删时备份里还留着，
  这正是本地备份存在的意义。代价是已删除的图会一直占空间，可以接受。
- **先写临时文件再原子改名**。否则某次 dump 失败会把当天早先成功的那份一起覆盖掉。
- **失败会 exit 1 并可选告警**。静默失败的备份最危险 —— 你以为有，其实早就断了。

环境变量（写在 cron 里，或 source 一份）：

```bash
BACKUP_DIR=/data/backup
BACKUP_RETENTION_DAYS=7
RCLONE_REMOTE=cos:share-backup        # 留空则只做本地备份（会告警提醒）
ALERT_WEBHOOK=https://example.com/hook # 失败时 POST {"text":"..."}，钉钉/飞书可直接用
```

cron（`/etc/cron.d/share-backup`）：

```bash
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 3 * * * root set -a; . /opt/share/deploy/share-system.env; set +a; \
          BACKUP_DIR=/data/backup RCLONE_REMOTE=cos:share-backup \
          /opt/share/deploy/backup.sh --yes >> /var/log/share-backup.log 2>&1
```

`set -a` 让 source 进来的变量自动 export，脚本里调起的 `mysqldump`/`rclone` 子进程也能读到。

`rclone` 配置一次（`rclone config`），远端名字就是 `RCLONE_REMOTE` 里 `:` 前面那段。
异地用 `rclone sync` 是安全的，因为本地备份目录本身「只增不删」，不会把源端的误删同步出去。

### 恢复演练：上线第一周必须做一次

没验证过的备份等于没有备份。「每天跑 mysqldump」和「真能恢复出来」之间隔着字符集、
表顺序、压缩包是否完整好几件事，等真丢数据那天才发现备份是坏的，就晚了。

```bash
./deploy/restore.sh list          # 看有哪些备份
./deploy/restore.sh db            # 恢复最新一份到 sharing_restore（临时库）
./deploy/restore.sh files         # 恢复图片到 /tmp/restore-uploads
```

两个命令**默认都恢复到临时位置**，要覆盖生产必须加 `--force` 并手输库名二次确认。

恢复后做三项检查（脚本会打印命令，照抄即可）：

1. 动态正文是中文不是乱码
2. `DB_NAME=sharing_restore UPLOAD_DIR=/tmp/restore-uploads ./deploy/photo-maintenance.sh missing`
   **输出为空** —— 说明「库里有引用的图，备份里都有」，恢复后不会破图。这一条是演练的核心。
3. 随机点开几张图，能显示、不是 0 字节

演练完记得 `DROP DATABASE sharing_restore;` 和删掉临时目录。

### 回收与巡检

删除动态是软删（`status=0`），图片行与文件都还在。`PhotoPurgeTask` 每天 03:40 把
「已删除满 `PHOTO_PURGE_DAYS`（默认 30）天」的图片真正清掉，并置 `status=-1`。

```bash
# 0 3 * * *  UPLOAD_DIR=/data/uploads DB_PASS=xxx /opt/share/deploy/photo-maintenance.sh check \
#            || 发告警
```

`check` 在磁盘 ≥80% 或有破图时 `exit 1`，可以直接当告警条件。
`orphans --delete` 清理「>30 天且全库无引用」的文件（只处理 uploads 第一层，默认只列不删）。

### 容量与带宽

一张图 ≈ 原图 1600px 350KB + 缩略图 720px 80KB ≈ **0.43MB**。100G 约 23 万张。
日活 1000、人均每天 3 图时约 77 天写满，70% 水位就该扩容。

但**带宽会比磁盘先撑不住**：首屏 10 篇带图约 15 张缩略图 ≈ 1.2MB，
5Mbps 固定带宽 = 625KB/s，两个人同时开首页就打满。香港机型给 30M（3.75MB/s），
能撑住十几个人同时浏览，但真要做大就该上 CDN 了。

---

## 10. 常见坑

按「排查顺序」排列，前三条覆盖了 80% 的情况。

1. **502 Bad Gateway**：backend 没起来。`docker compose ps` 看状态 → `docker compose logs backend`。
   最常见的是 env 文件里 `DB_PASSWORD` 与 `MYSQL_PASSWORD` 不一致，日志是 `Access denied`。
2. **接口 404**：nginx 的 `proxy_pass` 末尾少了 `/`，或者前端 baseURL 不是 `/api`。
3. **图片全 404**：`UPLOAD_DIR` 与 nginx `alias` 不一致，或 `/data/uploads` 权限不是 755。
   SELinux 开启的系统还要 `chcon -R -t httpd_sys_content_t /data/uploads`，否则看着权限正常但全 403。
4. **启动报 `UPLOAD_DIR 必须是绝对路径`**：这是启动自检主动拦下的。在 Linux 上写
   `D:/uploads/` 会被当成相对路径，图片会写进工作目录下名为 `D:` 的文件夹，
   nginx 找不到文件 —— 全站破图且没有任何报错。改成 `/data/uploads`。
5. **启动报 `Could not resolve placeholder 'JWT_SECRET'`**：`deploy/share-system.env` 里没填，
   或文件名不是这个（compose 里写死了 `env_file: deploy/share-system.env`）。
6. **容器不断重启**：`docker compose logs backend` 看最后几行。常见是邮件两项没填
   （生产自检会拒绝启动，这是故意的：注册功能不可用比启动失败更糟）。
7. **MySQL 容器一直 unhealthy**：`docker compose logs mysql`。如果看到
   `Permission denied` 或 `Can't create/write to file`，是 `/data/mysql` 属主不对，
   重新 `chown -R 999:999 /data/mysql`。
8. **上传大图失败 413**：nginx `client_max_body_size` 调成 48m（后端已放宽到 48MB，
   对应 9 张 × 5MB 的最坏情况）。
9. **时间差 8 小时**：MySQL 容器没设时区。compose 里已经写了
   `--default-time-zone=+08:00`，如果你改过这段，CURRENT_TIMESTAMP 会按 UTC 写入。

---

## 11. 后续

已完成：
- 健康检查 `/actuator/health`（DB、Redis 探针，邮件探针已关）
- 表结构 Flyway 版本化管理
- 启动自检（邮件缺失、JWT 过短、上传目录非绝对路径 → 直接失败）
- 图片回收任务 `PhotoPurgeTask`（软删满 30 天物理删图）
- 图片巡检脚本 `deploy/photo-maintenance.sh`
- 容器日志大小限制（防撑爆磁盘）

还没做：
- 自动备份脚本（`deploy/backup.sh`）+ cron，见 §9
- GitHub Actions 自动部署（push 即上线）
- Spring Boot 3.2.10 已过开源支持期（2024-11 结束），不再收安全补丁。
  升 3.3/3.4 需要一轮回归，建议单独排期。
- 自托管字体：前端现在用 Google Fonts，国内基本连不上，会退化成系统字体。
