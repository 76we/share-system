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
# 确认 schema.sql 存在（本次已补）
ls src/main/resources/scripts/schema.sql

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

## 2. 建库并导入结构

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS sharing DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"

# 建一个专用账号，不要用 root 跑业务
mysql -uroot -p -e "
CREATE USER 'share'@'127.0.0.1' IDENTIFIED BY '换成真实密码';
GRANT SELECT,INSERT,UPDATE,DELETE ON sharing.* TO 'share'@'127.0.0.1';
FLUSH PRIVILEGES;"

mysql -ushare -p sharing < schema.sql
mysql -ushare -p -e "USE sharing; SHOW TABLES;"   # 应看到 11 张表
```

> `schema.sql` 现在可重复执行：全部是 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，不会再 `DROP TABLE`。
> 早期版本由 mysqldump 导出，每张表前带 `DROP TABLE IF EXISTS`，在已有数据的库上再跑一次就会清库 —— 老脚本不要再用。

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

---

## 8. 常见坑

1. **502 Bad Gateway**：后端没起来，先看 `systemctl status share-system` 和 stdout.log。
2. **接口 404**：nginx 的 `proxy_pass` 末尾少了 `/`，或前端 baseURL 不是 `/api`。
3. **图片 404**：`UPLOAD_DIR` 与 nginx `alias` 不一致，或 uploads 目录权限不是 755。
4. **启动报错 `Could not resolve placeholder 'JWT_SECRET'`**：env 文件没被 systemd 读到，检查 `EnvironmentFile` 路径与权限。
5. **Redis 连接报 `ERR Client sent AUTH`**：Redis 无密码但配置里传了空密码，注释掉 `password:` 那行。
6. **上传大图失败**：nginx `client_max_body_size` 调成 20m 以上，同时注意后端单张 5MB 限制。

---

## 9. 后续建议（本次未做）

- 加 HTTPS（`certbot --nginx`），前端就别再用 http 打开。
- 数据库与上传目录纳入定时备份（`mysqldump` + `rsync` 到 OSS）。
- 接入健康检查：`/actuator/health`（需要加 spring-boot-starter-actuator 依赖）。
