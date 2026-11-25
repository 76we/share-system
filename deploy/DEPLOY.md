# 尘叙阁 · 上线部署手册（阿里云 ECS + jar + systemd + nginx）

目标结构：一台 ECS 上跑 MySQL、Redis、后端 jar（systemd 托管）、nginx 托管前端静态包。

```
/opt/share/
├── share-system.jar         后端包
├── share-system.env         环境变量（600 权限，不进 git）
├── frontend/dist/           前端构建产物
├── uploads/                 用户上传的图片（UPLOAD_DIR）
└── logs/                    运行日志
```

---

## 0. 前置检查（本地打包前）

```bash
# 确认迁移脚本存在（表结构由 Flyway 管理，应用启动时自动执行）
ls src/main/resources/db/migration/

# 打包（跳过测试）
mvn -o clean package -DskipTests
# 产物：target/share-system-0.0.1-SNAPSHOT.jar
```

---

## 1. 服务器基础环境

```bash
# 建用户与目录
useradd -r -m -s /bin/bash share
mkdir -p /opt/share/{uploads,logs,frontend}
chown -R share:share /opt/share
chmod 755 /opt/share/uploads   # nginx 要能读图片

# 检查 Java 17（Spring Boot 3.2 最低要求）
java -version

# MySQL 与 Redis
systemctl enable --now mysqld
systemctl enable --now redis
```

---

## 2. 建库与账号（表结构不用手工导）

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS sharing DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 建一个专用账号，不要用 root 跑业务。
# 注意：必须给 DDL 权限（CREATE/ALTER/DROP/INDEX/REFERENCES），
# 否则应用启动时 Flyway 建 flyway_schema_history、建表会直接报 Access denied。
mysql -uroot -p -e "
CREATE USER 'share'@'127.0.0.1' IDENTIFIED BY '换成真实密码';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON sharing.* TO 'share'@'127.0.0.1';
FLUSH PRIVILEGES;"
```

**表结构由 Flyway 在应用启动时自动建，不需要再手工执行 SQL。** 空库首次启动会执行
`db/migration/V1__init_schema.sql`，建出 11 张表 + 5 条话题种子数据，并在
`flyway_schema_history` 里记录版本。

启动后这样验证：

```bash
mysql -ushare -p -e "USE sharing; SHOW TABLES;"            # 应有 12 张（11 业务 + 1 历史表）
mysql -ushare -p -e "USE sharing; SELECT version,description,success FROM flyway_schema_history;"
mysql -ushare -p -e "USE sharing; SELECT id,name FROM topic;"   # 应有 5 条
```

> **不要再用 mysql 手工执行建表/改表 SQL。** 绕过 Flyway 改结构会让历史记录和实际库对不上，
> 下次部署时很难判断「线上到底缺哪个字段」。要改结构就新增 `V2__xxx.sql`，跟着应用一起发。
>
> 已有的库（比如本地开发库）第一次接进来时，Flyway 会把当前结构登记为 baseline=1、
> 不重复执行 V1，日志里是 `Successfully baselined schema with version: 1`。这是正常的。
>
> 单账号给足 DDL 权限是为了省事。若以后要更严格的最小权限，可拆成两个账号：
> 迁移账号（DDL 全权限，只在启动时用）+ 业务账号（仅 CRUD）。

---

### 以后怎么改表

新增一个迁移文件，跟着应用一起发布，启动时自动执行。例：给动态加一个「是否置顶」字段：

```sql
-- 文件名：src/main/resources/db/migration/V2__add_moment_pinned.sql
ALTER TABLE `moment`
  ADD COLUMN `pinned` tinyint NOT NULL DEFAULT '0' COMMENT '是否置顶' AFTER `mood`;
```

四条规则：

1. **文件名必须是 `V<数字>__<说明>.sql`**（两个下划线），版本号递增。别跳号后又回头补。
2. **已执行过的脚本一个字都不能改。** Flyway 会比对文件 checksum，改了直接启动失败
   （`Migration checksum mismatch`）。要修正就新增下一个版本去改它。
3. **MySQL 的 DDL 不支持事务回滚。** 迁移执行到一半失败，已生效的部分会留在库里，
   要手工修好再删掉历史表里那条 `success=0` 的记录（然后重启重跑）。所以改表前先备份。
4. **上线前先看会跑什么。** 启动日志里的 `Migrating schema ... to version "2 - xxx"`
   就是本次要执行的内容，不确定就先在测试库跑一遍。

> 唯一的例外：如果某个脚本刚发出去就发现写错了，且**只有你一个人用过这套库**，
> 可以改脚本后让 Flyway 重算 checksum（`mvn flyway:repair` 或临时把
> `validate-on-migrate` 关掉一次）。有其他人在用时禁止这么做 —— 会篡改历史。

---

## 3. 上传 jar 与环境变量

```bash
# 本地执行
scp target/share-system-0.0.1-SNAPSHOT.jar root@your.server:/opt/share/share-system.jar
scp deploy/share-system.env.example root@your.server:/opt/share/share-system.env

# 服务器上
vi /opt/share/share-system.env        # 填真实密码与 JWT_SECRET（openssl rand -base64 48）
chown share:share /opt/share/share-system.env
chmod 600 /opt/share/share-system.env
```

> 关键点：`application-prod.yml` 里所有敏感项都是 `${...}` 占位，靠这个 env 文件注入。
> `JWT_SECRET` 没设会导致启动失败，这是故意的（失败得早，比带上默认密钥上线好）。
>
> `MAIL_USERNAME` / `MAIL_AUTH_CODE` 同样必填：生产启动自检会检查，缺失直接启动失败。
> 原因很直接 —— 注册与找回密码都靠邮件验证码，配不上就等于「注册功能不可用」；
> 而「没配就退化成把验证码打到日志」只在本地联调时有用，在生产上等于验证码泄露 + 用户永远注册不了。

---

## 4. 托管成系统服务

```bash
cp deploy/share-system.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now share-system

systemctl status share-system -l
tail -f /opt/share/logs/stdout.log     # 看到 "Started ShareSystemApplication" 即成功
```

自检：

```bash
curl -i http://127.0.0.1:8080/topics        # 公开接口，应返回 200 + JSON
curl -i http://127.0.0.1:8080/profile       # 需要登录，应返回 401
```

---

## 5. 前端构建与发布

```bash
# 本地
cd ../share-frontend
npm ci
npm run build          # 产物在 dist/

# 上传
scp -r dist/* root@your.server:/opt/share/frontend/dist/
```

> 前端代码里接口地址是相对路径 `/api`，靠 nginx 的 `proxy_pass .../` 去掉前缀。
> 如果后端换端口，只改 nginx，不用重新构建前端。

---

## 6. nginx

```bash
cp deploy/nginx-share-system.conf /etc/nginx/conf.d/
vi /etc/nginx/conf.d/share-system.conf    # 改 server_name
nginx -t && systemctl reload nginx
```

---

## 7. 验证清单

| 检查项 | 做法 | 期望 |
|---|---|---|
| 前端能打开 | 浏览器访问域名 | 首页正常，不是 502 |
| 接口连通 | 登录一次 | 拿到 token，首页有动态 |
| 图片能加载 | 打开任意带图动态 | 图片 200，不是 404 |
| 上传写入 | 发一条带图动态 | `/opt/share/uploads/` 出现新文件 |
| 越权拦截 | 对别人的动态点删除 | 提示「无权限修改该动态」 |
| 分页上限 | `?size=10000` | 最多返回 50 条 |
| 异常有日志 | 请求一个不存在的接口 | `logs/stdout.log` 里有 ERROR 堆栈 |
| 重启自愈 | `systemctl restart share-system` | 10 秒内恢复 |
| 健康检查 | `curl -s 127.0.0.1:8080/actuator/health` | `{"status":"UP"}` 且 HTTP 200（DOWN 是 503） |

> 健康检查只暴露 `/actuator/health`，且 `show-details: never`（只看 UP/DOWN，不吐数据库地址）。
> nginx 只反代 `/api/`，所以它不会出现在公网，只给本机 systemd / 监控用。
> 邮件探针已关闭：SMTP 抖动不该让整个服务被判定为不健康。

## 7.5 图片存储（上线后必须每天盯）

### ① 放独立数据盘
系统盘写满会连带 MySQL 与日志一起挂，图片是唯一会持续增长的东西。

```bash
mkfs.ext4 /dev/vdb
mkdir -p /data && blkid /dev/vdb            # 记下 UUID
echo 'UUID=xxxx /data ext4 defaults,noatime 0 2' >> /etc/fstab
mount -a
mkdir -p /data/uploads && chown share:share /data/uploads && chmod 755 /data/uploads
# share-system.env: UPLOAD_DIR=/data/uploads/
# nginx:            alias /data/uploads/;
```
两处必须逐字符一致，否则图片全 404。CentOS 开了 SELinux 还要
`chcon -R -t httpd_sys_content_t /data/uploads`，否则全 403。

### ② 备份顺序：先 dump 库，再同步图

```bash
# /etc/cron.daily/share-backup
mysqldump --single-transaction sharing | gzip > /tmp/db-$(date +%F).sql.gz
rclone copy /tmp/db-*.sql.gz      oss:share-backup/db/
rclone copy /data/uploads         oss:share-backup/uploads/    # 只增不删，用 copy 不用 sync
find /tmp -name 'db-*.sql.gz' -mtime +3 -delete
```

顺序反了会恢复出破图：先 dump 库（T0）再同步图（T1>T0），图一定覆盖 T0 的所有引用；
反过来做，T0~T1 之间上传的图会被库引用但备份里没有。用 `copy` 不用 `sync`：
uploads 是只增不改的，`sync` 一旦源目录出事会把备份一起删。

上线第一周做一次恢复演练，别等真丢数据时才发现备份是空的。

### ③ 回收与巡检
删除动态是软删（`status=0`），图片行与文件都还在。`PhotoPurgeTask` 每天 03:40 把
「已删除满 `PHOTO_PURGE_DAYS`（默认 30）天」的图片真正清掉，并把 `status` 置为 -1。

```bash
# 0 3 * * *  UPLOAD_DIR=/data/uploads DB_PASS=xxx /opt/share/deploy/photo-maintenance.sh check \
#            || 发告警
```

`check` 在磁盘 ≥80% 或有破图时 `exit 1`，可直接当告警条件。
`orphans --delete` 清理「>30 天且全库无引用」的文件（只处理 uploads 第一层，默认只列不删）。

### 容量参考
一张图 ≈ 原图 1600px 350KB + 缩略图 720px 80KB ≈ **0.43MB**。
100G 约 23 万张；日活 1000、人均每天 3 图时约 77 天写满。到 70% 水位就该动手扩容：
加盘（LVM 最省事）→ 迁 OSS（只改 nginx 的 `/images/`，数据库里存的是相对路径，代码不用动）→ 上 CDN。

### 带宽比磁盘先撑不住
首屏 10 篇带图约 15 张缩略图 ≈ 1.2MB。5Mbps 固定带宽 = 625KB/s，两个人同时开首页就打满。
该开 CDN。防盗链放 CDN 上做，别在源站加 `valid_referers`（微信内置浏览器、直接分享会被误伤）。


---

## 8. 常见坑

1. **502 Bad Gateway**：后端没起来，先看 `systemctl status share-system` 和 stdout.log。
2. **接口 404**：nginx 的 `proxy_pass` 末尾少了 `/`，或前端 baseURL 不是 `/api`。
3. **图片 404**：`UPLOAD_DIR` 与 nginx `alias` 不一致，或 uploads 目录权限不是 755。
4. **启动报错 `Could not resolve placeholder 'JWT_SECRET'`**：env 文件没被 systemd 读到，检查 `EnvironmentFile` 路径与权限。
5. **Redis 连接报 `ERR Client sent AUTH`**：Redis 无密码但配置里传了空密码，注释掉 `password:` 那行。
6. **上传大图失败**：nginx `client_max_body_size` 调成 20m 以上，同时注意后端单张 5MB 限制。

---

## 9. 后续建议

已完成（本版已含）：
- 健康检查 `/actuator/health`（DB、Redis 探针）
- 图片回收任务 `PhotoPurgeTask`（软删满 30 天物理删图）
- 图片巡检脚本 `deploy/photo-maintenance.sh`

还没做：
- 加 HTTPS（`certbot --nginx`），前端就别再用 http 打开。
- 数据库与上传目录纳入定时备份（`mysqldump` + `rclone` 到 OSS），见 7.5。
- 日志轮转：`logging.file.name` 那份有 Spring 默认的 10MB/7 天滚动，
  但 systemd 的 `StandardOutput=append:stdout.log` 没有轮转、会无限增长。
  给 stdout.log / stderr.log 配 logrotate，或把 `StandardOutput` 改成 `journal`。
- Spring Boot 3.2.10 已过开源支持期（2024-11 结束），不再收安全补丁；
  升 3.3/3.4 需要一轮回归测试，建议单独排期。
