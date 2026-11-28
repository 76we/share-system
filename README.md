# 尘叙阁 · share-system

一个图文分享社区的后端服务：用户注册登录、发布图文动态、话题、评论、点赞、收藏、好友、通知、相册与照片墙，配套一套可上线的部署方案（Docker Compose + nginx + 自动备份）。

前端仓库：`share-frontend`（Vue 3 + Vite）。本项目只提供 REST API 与一个服务端渲染的分享页。

---

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.2.10（Web、Validation、Actuator、Mail） |
| 持久层 | MyBatis-Plus 3.5.18 + MySQL 8.0 |
| 缓存 | Redis 7（登录限流、接口限流、关系缓存、验证码） |
| 数据库迁移 | Flyway（`V1` → `V2`，禁止改历史脚本） |
| 认证 | JWT（jjwt 0.11.5，无状态）+ Spring Security Crypto（BCrypt） |
| 图片处理 | ImageIO + metadata-extractor（EXIF 方向转正、重编码、双尺寸） |
| 接口文档 | springdoc-openapi 2.6.0（OpenAPI 3 + Swagger UI） |
| 运维 | Docker Compose、nginx 反代、GitHub Actions、actuator health |

---

## 功能模块

| 控制器 | 路径 | 能力 |
|---|---|---|
| `UserController` | `/users` | 注册、登录、邮箱验证码、找回密码、资料查询与修改 |
| `MomentController` | `/moments` | 发布（多图上传）、列表、详情、删除、可见性 |
| `CommentController` | `/comments` | 评论、回复、楼中楼查询、删除 |
| `LikeController` | `/likes` | 点赞 / 取消（关系与计数分离） |
| `CollectController` | `/collects` | 收藏 / 取消、我的收藏 |
| `FriendController` | `/friendships` | 好友申请、接受、拒绝、好友列表 |
| `NotificationController` | `/notifications` | 未读数、标记已读、全部已读 |
| `AlbumController` | `/albums` | 相册增删改、往相册加照片、删除照片 |
| `PhotoWallController` | `/profile/photos` | 照片墙按年 / 月分组、周年分组 |
| `TopicController` | `/topics` | 话题创建、列表、话题下动态 |
| `ProfileController` | `/profile` | 个人中心聚合、修改密码 |
| `ShareController` | `/share` | 动态分享页（服务端拼 OG 标签，给微信 / 搜索引擎） |

对外共 60 个端点。

---

## 架构

```
                       ┌──────────────────────────────┐
  浏览器（Vue SPA） ──▶ │  nginx                       │
                       │  ├─ /            → dist/     │  静态文件
                       │  ├─ /api/**      → :8080     │  剥掉 /api 前缀后反代
                       │  └─ /images/**   → uploads/  │  图片直读磁盘
                       └──────────────┬───────────────┘
                                      │
                       ┌──────────────▼───────────────┐
                       │  Spring Boot（Docker 容器）   │
                       │  Controller → Service → Mapper│
                       └───┬──────────┬───────────┬───┘
                           │          │           │
                     ┌─────▼───┐ ┌────▼────┐ ┌────▼─────┐
                     │ MySQL 8 │ │ Redis 7 │ │ 磁盘图片  │
                     └─────────┘ └─────────┘ └──────────┘
```

一次请求的完整链路：

```
nginx 剥 /api → JWT 拦截器校验 → Controller 参数校验
  → Service 事务 → MyBatis-Plus → 事务提交
  → 提交后失效缓存（TxHooks.afterCommit）→ Result 统一响应
```

异常统一由 `GlobalExceptionHandler` 转成 `Result`，业务异常抛 `BusinessException`，不往外抛堆栈。

---

## 核心设计

这一节是项目里最值得看的部分，每一条都对应一个真实的坑。

### 1. 缓存失效必须发生在事务提交之后

`common/TxHooks.afterCommit()`

在事务方法体里删缓存是错的：删除后、提交前有一小段窗口，并发请求会重新查库并把**旧数据**写回缓存，于是脏数据被固定住一整个 TTL。正确做法是注册 `TransactionSynchronization`，提交后才失效；无事务时立即执行。钩子内的异常只记日志，不能反过来影响已成功的业务。

### 2. 点赞：关系与计数分离

`LikeServiceImpl` 用两个 key：`like:rel:{userId}:{momentId}` 判断是否已赞，`like:count:{momentId}` 存计数。避免每次列表渲染 `COUNT(*)` 扫表。并发下的最终一致性由数据库唯一约束兜底，不依赖缓存。

### 3. 图片处理管线

`entity/FileUtil`（383 行）

上传不是「存下来」那么简单，顺序很关键：

```
校验体积/扩展名 → 校验文件头（防改后缀）→ 只读文件头查像素数（防解压炸弹）
  → 读 EXIF 方向 → 解码 → 按方向真正转正 → 取转正后的宽高
  → 缩放到长边 1600 → 重编码（剥离 EXIF）/ 生成 720 缩略图
```

四个关键点：

- **必须先读方向再解码。** 先解码后旋转，转的是一张已经丢了方向信息的图，方向无从判断。
- **宽高必须在转正之后取。** 转 90° 会交换宽高，用转正前的值做宽高比会得到横图，前端占位塌陷（CLS）。
- **一律重编码，不做「小图原样落盘」。** 原图里带着 GPS 坐标、设备型号、精确拍摄时间，任何人都能下载读到。
- **像素炸弹防护**：几十 KB 的 PNG 可以声明 20000×20000，`ImageIO.read` 全解码要几 GB 堆内存，一个请求就能打挂 `-Xmx512m` 的进程。所以解码前只读文件头拿宽高，超过 4000 万像素直接拒绝。

### 4. 敏感词：DFA 字典树

`common/SensitiveWordFilter`

不用 `String.contains` 逐个词扫 —— 那是 O(词数 × 文本长度)。把词库建成 Trie，一次遍历文本即可，复杂度只与文本长度有关，词库扩到几千条也不影响响应时间。

### 5. 启动自检：把「配置错了但能跑起来」变成启动失败

`common/StartupCheck`

三类坑在启动时就拦下：

- 上传目录写成相对路径（Linux 上写 `D:/uploads` 会被当成工作目录下的 `D:` 文件夹，图片写进去、nginx 找不到、**全站破图且不报错**）
- 生产环境没配发件邮箱（注册验证码只打日志，用户永远收不到，表现为「一直提示验证码错误」）
- 用 dev profile 起服务（dev 配置里带默认数据库密码和公开的 JWT 密钥）

### 6. 限流与防爆破

- `RateLimiter`：Redis 固定窗口计数，按 IP / 用户维度
- `LoginGuard`：登录失败次数限制，挡住密码暴力破解

### 7. 图片回收：软删 + 定时物理清理

`service/PhotoPurgeTask`，每天 03:40（避开 03:00 的备份）：

删除动作用软删，满 30 天后由定时任务物理删除 `moment_image` 行与磁盘文件（原图 + `_t` 缩略图一起删，否则留下孤儿文件慢慢吃磁盘）。参数全部可配：`PHOTO_PURGE_ENABLED` / `PHOTO_PURGE_DAYS` / `PHOTO_PURGE_CRON`。

### 8. 数据库迁移纪律

Flyway 管表结构，`src/main/resources/db/migration/`。规则只有一条：

> **脚本一旦执行过就不要再改内容。** Flyway 校验 checksum，改一个字符就会启动失败。要调整结构只能新增 `V3__xxx.sql`，历史只往后长。

同时 `clean-disabled: true`，防止误执行 `flyway clean` 清空整库。

### 9. 好友关系：成对记录 + 唯一约束

`friendship` 表不存「我关注了你」这种单向边，而是每个关系只存一行，`user_a` 固定放较小的 id、`user_b` 放较大的 id，配合 `UNIQUE KEY uk_pair (user_a, user_b)`。

这样设计的直接收益：**A 反复点「加好友」不可能插出第二行**，重复申请由数据库拦住，不依赖应用层先查后插（那在并发下必然漏）。`from_user` 单独记录谁发起的，用来区分「我申请的你」和「你申请的我」。查询时 `(user_a = me OR user_b = me)` 一次拿到双方关系。

### 10. 健康检查的取舍

只暴露 `health` 且不显示细节，并**主动关掉邮件探针**：它每次都会真连一次 SMTP，邮件只是注册的旁路，SMTP 抖一下整个 health 变 DOWN，监控就会去重启一个其实完全正常的服务。这条在本地实测踩到过。

---

## 数据表

11 张：`user`、`moment`、`moment_image`、`moment_comment`、`user_like`、`user_collect`、`friendship`、`notification`、`album`、`topic`、`photo`。

`moment_image` 除 URL 外还存 `width` / `height`（`V2__add_moment_image_size.sql` 新增），前端据此先占住正确比例的盒子再加载图片，避免列表因图片到达而跳动。

---

## 目录结构

```
src/main/java/com/we/sharesystem/
├── common/          Result、业务异常、全局异常处理、限流、事务钩子、敏感词、启动自检
├── config/          Web（CORS、静态资源映射）、Redis、MyBatis-Plus
├── controller/      12 个 REST 控制器
├── dto/             入参对象（带 Bean Validation 注解）
├── entity/          数据实体 + FileUtil（图片处理）
├── interceptor/     JWT 校验拦截器
├── mapper/          MyBatis-Plus Mapper
├── service/         业务接口与实现
├── utils/           通用工具
└── vo/              出参对象

src/main/resources/
├── application.yml         公共配置
├── application-dev.yml     本地开发
├── application-prod.yml    生产
└── db/migration/           Flyway 脚本

deploy/              部署相关（见下）
```

---

## 本地启动

**前置**：JDK 17、MySQL 8、Redis 7。邮件功能可选 —— 不配 SMTP 时验证码会打印到日志。

```bash
# 1. 建库（表结构由 Flyway 自动创建，不需要手工建表）
mysql -uroot -p -e "CREATE DATABASE sharing DEFAULT CHARSET utf8mb4;"

# 2. 按需改本地配置（默认连 localhost:3306/sharing、redis 6379）
#    application-dev.yml 里带默认账号，直接跑通常不用改

# 3. 启动
mvn spring-boot:run
```

服务跑在 `8080`。前端开发时由 Vite 代理 `/api` 和 `/images` 过来。

**发送验证码（可选）**：设置 `MAIL_USERNAME`（QQ 邮箱）与 `MAIL_AUTH_CODE`（SMTP 授权码）两个环境变量。

---

## 接口约定

- 生产环境所有接口带 `/api` 前缀，由 nginx 剥掉后转发（后端自身不认识 `/api`）
- 统一响应体 `Result`：`{ code, message, data }`，`code = 200` 为成功
- 业务失败抛 `BusinessException`，由 `GlobalExceptionHandler` 转成对应 `code`
- 分页统一走 `PageParam`（页码从 1 开始）
- 认证：请求头 `Authorization: Bearer <token>`，由 `interceptor` 校验，无状态

### 在线接口文档

启动后打开 **http://localhost:8080/swagger-ui/index.html**（规范原文 `/v3/api-docs`）。

用法：先调 `POST /users/login` 拿 token → 点右上角 **Authorize** 填入（只填 token 本身，不要带 `Bearer ` 前缀）→ 之后所有请求自动带 `Authorization` 头，可以直接点 **Try it out** 试。

两处设计说明：

- **哪些接口公开，只维护一份。** `common/PublicEndpoints` 是唯一事实来源，`WebConfig` 的拦截器按它放行，`OpenApiConfig` 按同一份清单去掉文档里的锁标。分两处写会出真事：今天放行了一个接口忘了同步文档，页面上它带着锁，看的人以为要登录，试一下发现能通 —— 比没有文档更糟。
- **生产默认关闭。** 文档等于把全部接口和参数结构挂在公网上，对演示项目也没必要。需要时用 `SPRINGDOC_ENABLED=true` 临时打开，并确认 nginx 没有把 `/swagger-ui` 反代出去（默认配置只反代 `/api/` 与静态文件，天然进不来）。

> 只能用 springdoc，不能用 springfox —— 后者依赖 `javax.*`，在 Spring Boot 3（`jakarta.*`）上根本起不来，这是 Boot 3 升级时最常见的坑之一。

---

## 部署

完整步骤见 [`deploy/DEPLOY.md`](deploy/DEPLOY.md)，上线路线与预算见 [`deploy/上线路线.md`](deploy/上线路线.md)。

`deploy/` 目录内容：

| 文件 | 用途 |
|---|---|
| `DEPLOY.md` | 完整部署手册（从装 Docker 到 HTTPS） |
| `上线路线.md` | 阶段划分、预算、时间线、故障速查 |
| `nginx-share-system.conf` | 有域名时的 nginx 配置（含 noindex、防盗链） |
| `nginx-share-system-ip.conf` | 备案期间只有 IP 时的临时配置 |
| `share-system.env.example` | 容器内应用的环境变量模板 |
| `backup.sh` / `restore.sh` | 数据库与图片的备份 / 恢复 |
| `photo-maintenance.sh` | 图片巡检（孤儿文件、缩略图缺失） |

要点：

- **配置分两份**：`.env` 给 docker compose 做变量插值（内存上限、端口、数据目录），`deploy/share-system.env` 给容器内的应用读（数据库密码、JWT 密钥、SMTP）。别混用。
- **`docker-compose.yml` 里的内存上限必须按机器规格调。** 2G 机器上 MySQL 默认 `buffer pool 512M` 就吃掉一半，加上 JVM 和 Redis，一上传图片就被 OOM Killer 干掉，而现象是「容器自己消失了、日志里没有任何异常」。
- **发布策略**：镜像按 commit sha 打 tag，健康检查通过后才更新 `latest`。回滚 = `BACKEND_TAG=<sha> docker compose up -d backend`。

---

## 测试

```bash
mvn test
```

6 个测试类、24 个用例：`JwtUtilTest`、`IpUtilTest`、`PageParamTest`、`SensitiveWordFilterTest`、`FileUtilTest`，以及 `ShareSystemApplicationTests`（加载完整的 Spring 上下文，能兜住 Bean 装配、配置绑定这类「编译通过但起不来」的问题）。

`FileUtilTest` 用真实生成的图片验证四件事：

- 带 EXIF 方向标记的照片，落盘后像素真的被转正，且返回的是**转正后**的宽高
- 没有 EXIF 时保持原方向（不能因为读不到方向就乱转）
- 落盘文件里读不到元数据（EXIF 已被剥离）
- 缩略图与原图同时生成，文件名符合 `xxx_t.jpg` 约定

这是唯一能覆盖「图片处理顺序」这类时序 bug 的手段 —— 顺序反了不会报错，只会让照片躺着显示。

---

## 已知限制

- 好友关系只有「申请中 / 已同意」两种状态，没有拉黑，也没有「解除好友」后的历史
- 通知只落库，既没有 WebSocket 推送也没有前端轮询 —— 未读数只在进入页面或触发操作时刷新
- 限流是 Redis 固定窗口，多实例之间计数共享，但固定窗口在窗口边界处允许双倍突发（换滑动窗口或令牌桶才能消除）
- 图片存本地磁盘，水平扩展前必须先抽成对象存储接口
- 未接入链路追踪与结构化日志，排查跨服务问题只能靠日志时间线
- 接口没有写 `@Operation` 描述，Swagger 上看到的是方法名和参数名，没有业务说明

## 后续计划

- 补压测数据（QPS / P95 / 连接池占用），确定单机容量上限
- 补并发一致性测试（同一条动态并发点赞的最终计数）
- 图片存储抽象成接口，支持本地磁盘 / 对象存储切换
- 给核心接口补 `@Operation` / `@Schema`，让文档说出「这个接口干什么」而不只是「它叫什么」
