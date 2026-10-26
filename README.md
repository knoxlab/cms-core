# PowerCMS 12 · Community Edition Core

**一套平台，两种交付：HTML 页面渲染 + Headless REST / GraphQL API。**

[中文](#中文) | [English](#english)

本仓库是 PowerCMS 12 **社区版**核心：基于 Spring Boot 3.5 / Spring Modulith 21 的模块化单体，
覆盖身份、站点、内容模型、内容与版本、媒体、工作流、构建发布、检索、站点包、审计与交付。

社区版仅包含开源能力，以下为企业版 / 商业模块，**不在本仓库内**（由独立的专有模块提供）：

- Knox-Media 集成、转码、HLS 播放与 DRM
- 互动运营（评论、问卷、考试、聊天室、广告、留言板）与自定义表单
- AI / RAG 知识库、混合检索、知识图谱、智能体与 MCP 工具
- 外部数据源接入、对象级 ACL 打破继承的高级特性、离线授权
- 企业集群、SSO 单点登录与高风险操作双人复核

---

## 中文

### 核心能力

- **双模一体** —— MVC 页面渲染（含渲染缓存）+ REST / 按站点动态 Schema 的 GraphQL，同源数据两种交付
- **内容治理** —— 不可变版本快照、字段级差异与恢复、编辑锁、定时发布；可版本化工作流（会签、定时流转、站内通知 + 邮件 + Webhook）
- **发布工程** —— Release → Artifact → Deployment 三级不可变发布模型、全量 / 增量 SSG、Nginx 与 Git 发布目标、原子切换与回滚、部署健康检查
- **多站点与站点包** —— 多站点多环境、站点蓝图、站点包 / 主题包导入导出与签名校验门禁
- **媒体库** —— 本地文件系统存储、秒传去重、版本管理；视频 / 大文件按 Range 直出，社区版无转码
- **检索** —— 管理索引与访客索引隔离、候选索引原子切换；默认内存实现，可切换 OpenSearch
- **身份与权限** —— 组织 / 用户 / 角色、站点级读写授权、OAuth 与 API Token、失败登录锁定、密码历史
- **审计与运维** —— 追加写审计、系统日志采集与留存策略、异步任务重试、健康检查与渲染缓存预热

### 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **21+（强制）** | `maven-enforcer-plugin` 在 `validate` 阶段强制校验，低版本直接失败并提示 |
| Maven | 3.6.3+ | 需先安装同级的 `cms-common` 模块 |
| Node.js | 20+ | 仅构建管理端（`admin-ui`）时需要 |

> **注意：必须让 `JAVA_HOME` 指向 JDK 21**。若 PATH 上默认 `java` 是 8 / 17，直接运行 `mvn` 会报错。
> Windows（PowerShell）：`$env:JAVA_HOME='D:\GraalVM\jdk-21.0.7'`；Linux / macOS：`export JAVA_HOME=/usr/lib/jvm/jdk-21`。

### 5 分钟跑起来

默认使用 **H2 文件库**（`./data/powercms`，PostgreSQL 兼容模式，零外部依赖），首次启动由 Flyway 自动建表：

```bash
cd cms-common && mvn -DskipTests install      # 先安装共享内核（错误模型、领域事件等）
cd ../cms-core && mvn spring-boot:run         # http://localhost:8080
```

首次启动自动创建系统管理员：**`admin` / `Admin@123456`**（生产环境务必修改，并设
`powercms.identity.reject-factory-password=true` 拒绝出厂密码启动）。

启动管理端（可选，独立仓库 `admin-ui`，React 18 + TypeScript + Vite + Ant Design）：

```bash
cd admin-ui && npm install && npm run dev     # http://localhost:5173，/api 与媒体路径代理到 8080
```

也可以打成可执行 jar 后运行；若构建时同级存在 `admin-ui/dist`，会被整体内嵌进 jar 一并提供管理端静态资源：

```bash
cd cms-core && mvn -DskipTests package
java -jar target/cms-core-12.0.2.jar
```

### 数据库切换

同一套代码与迁移脚本支持 **H2（默认）/ PostgreSQL 14+**，按 profile 自动切换数据源、方言与迁移占位符取值，业务代码零改动：

```bash
POWERCMS_DB_PROFILE=db-postgres mvn spring-boot:run   # PostgreSQL
```

连接参数可用环境变量覆盖（默认即括号内值）：

```bash
export POWERCMS_DB_HOST=192.168.0.109     # 默认 localhost 语义见 application.yml
export POWERCMS_DB_PORT=5432
export POWERCMS_DB_NAME=powercms12
export POWERCMS_DB_USER=postgres
export POWERCMS_DB_PASSWORD=postgres
```

其他常用配置（均有环境变量等价项，见 `src/main/resources/application.yml`）：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `powercms.media.storage-root` | `${java.io.tmpdir}/powercms-media` | 社区版媒体落盘目录 |
| `powercms.media.max-file-size` | `104857600`（100MB） | 单文件上传上限 |
| `powercms.search.backend` | `memory` | 可切换 `opensearch`（`POWERCMS_SEARCH_BACKEND`） |
| `powercms.mail.enabled` | `false` | 邮件通知开关（工作流邮件通知依赖它） |
| `powercms.ssg.auto-build-enabled` | `false` | 内容变更后是否自动触发 SSG 增量构建 |

### 验证入口

| 入口 | 地址 |
|---|---|
| 应用元信息 | `GET http://localhost:8080/api/v1/meta` |
| 健康检查 | `GET http://localhost:8080/actuator/health` |
| OpenAPI 文档 | `http://localhost:8080/swagger-ui.html` |
| 交付 REST | `GET http://localhost:8080/api/v1/delivery/...` |
| 交付 GraphQL | `POST http://localhost:8080/graphql/sites/{siteId}` |
| 交付 MVC 渲染 | `GET http://localhost:8080/mvc/sites/{siteKey}/**` |

### 构建与测试

```bash
cd cms-common && mvn install                  # 含测试
cd ../cms-core && mvn clean test              # 后端测试（含 Spring Modulith 架构边界测试）
cd ../cms-core && mvn clean package           # 产出可执行 jar
```

### 仓库结构

```text
PowerCMS12-community/
├── cms-common/        # 共享内核：统一错误模型（ApiError / ErrorCodes）、领域事件、调用上下文
├── cms-core/          # CMS 核心（本仓库）：Spring Modulith 模块化单体
│   └── com.powercms.cms.
│       ├── identity/      # 身份、组织、角色、OAuth / API Token
│       ├── site/          # 站点、环境、域名
│       ├── model/         # 内容类型、字段、关系、分类法、脚本沙箱
│       ├── content/       # 内容条目、版本与差异、生命周期
│       ├── media/         # 媒体库、版本、文件夹、本地存储直出
│       ├── workflow/      # 工作流定义、流转、定时任务与通知
│       ├── publish/       # Release / Artifact / Deployment、SSG 构建与部署
│       ├── search/        # 管理索引与访客索引（内存默认 / OpenSearch 可切换）
│       ├── sitepackage/   # 站点包、主题包导入导出与签名门禁
│       ├── delivery/      # 交付 REST / GraphQL / MVC 渲染与预览
│       ├── sitefolder/    # 站点文件夹与路由组织
│       ├── stats/         # 访问统计采集与聚合
│       ├── audit/  outbox/  webhook/  widget/  systemlog/
│       └── platform/      # 安全配置、统一错误处理、网关限流等横切设施
└── admin-ui/          # 管理端（独立仓库，构建产物可选内嵌进 cms-core）
```

### 开发规约

- 时间统一 UTC 存储、按站点 / 用户时区展示；数据与日志一律 UTF-8。
- 数据库 schema 只通过 Flyway 迁移脚本演进（`src/main/resources/db/migration`），禁用 Hibernate 自动建表（`ddl-auto: validate`）。
- API 错误统一返回 `code / message / traceId / details` 结构，异常经统一处理器转换。
- 模块间只通过公开服务接口与领域事件通信；新增模块 = `com.powercms.cms` 下新增子包 + `package-info.java` 声明模块边界，`ModularityTests` 会阻断违规依赖。
- 测试默认走内存库，不得改回文件库，避免污染开发数据。

### 许可证

以 [Apache License 2.0](LICENSE) 开源发布。

---

## English

### Overview

PowerCMS 12 Community Edition is a **dual-mode CMS**: the same sites, content models, permissions,
versions and publishing data can either render HTML pages (classic CMS mode) or expose JSON via
REST / GraphQL (Headless mode). This repository is the core backend — a Spring Boot 3.5 /
Spring Modulith modular monolith on Java 21.

Enterprise-only capabilities (Knox-Media transcoding / HLS / DRM, interaction & forms, AI / RAG /
knowledge graph / agents, external data sources, clustering / SSO / dual approval) live in separate
proprietary modules and are intentionally absent here.

### Key features

- **Dual-mode delivery** — MVC page rendering (with render cache) + REST / per-site dynamic-schema GraphQL from one source of truth
- **Content governance** — immutable version snapshots, field-level diffs and restore, edit locks, scheduled publishing; versionable workflows (co-sign, scheduled transitions, in-app + email + Webhook notifications)
- **Publishing pipeline** — Release → Artifact → Deployment immutable model, full/incremental SSG, Nginx and Git publish targets, atomic switch & rollback, deployment health checks
- **Multi-site & site packages** — multiple sites and environments, site blueprints, signed site/theme package import/export with verification gates
- **Media library** — local filesystem storage, instant-upload deduplication, versioning; byte-range streaming for video/large files (no transcoding in the Community Edition)
- **Search** — isolated admin and public indexes with atomic candidate swap; in-memory backend by default, OpenSearch optional
- **Identity & access** — organizations / users / roles, per-site authorization, OAuth and API tokens, failed-login lockout, password history
- **Audit & operations** — append-only audit trail, system logging with retention policies, async task retry, health checks and render-cache warm-up

### Requirements

| Component | Version | Notes |
|---|---|---|
| JDK | **21+ (enforced)** | `maven-enforcer-plugin` fails fast in the `validate` phase on older JDKs |
| Maven | 3.6.3+ | The sibling `cms-common` module must be installed first |
| Node.js | 20+ | Only needed to build the `admin-ui` admin console |

> Make sure `JAVA_HOME` points to JDK 21. PowerShell: `$env:JAVA_HOME='D:\GraalVM\jdk-21.0.7'`;
> Linux/macOS: `export JAVA_HOME=/usr/lib/jvm/jdk-21`.

### Quick start

The default profile uses an **H2 file database** (`./data/powercms`, PostgreSQL compatibility mode) —
no external dependencies; Flyway creates the schema on first boot:

```bash
cd cms-common && mvn -DskipTests install      # install the shared kernel first
cd ../cms-core && mvn spring-boot:run         # http://localhost:8080
```

A bootstrap admin is created on first start: **`admin` / `Admin@123456`**. In production change the
password and set `powercms.identity.reject-factory-password=true` to refuse booting with the factory password.

Admin console (optional, separate `admin-ui` repo — React 18 + TypeScript + Vite + Ant Design):

```bash
cd admin-ui && npm install && npm run dev     # http://localhost:5173, proxies /api and media to 8080
```

Or package an executable jar; if `admin-ui/dist` exists next to `cms-core` at build time it is
embedded into the jar so the app serves the admin UI itself:

```bash
cd cms-core && mvn -DskipTests package
java -jar target/cms-core-12.0.2.jar
```

### Database profiles

The same code and migrations support **H2 (default) and PostgreSQL 14+**; the active profile
(`POWERCMS_DB_PROFILE`) switches the datasource, Hibernate dialect and migration placeholder values
with zero code changes. Connection parameters can be overridden via `POWERCMS_DB_HOST` /
`POWERCMS_DB_PORT` / `POWERCMS_DB_NAME` / `POWERCMS_DB_USER` / `POWERCMS_DB_PASSWORD`.

Other commonly tuned settings (all have environment-variable equivalents, see
`src/main/resources/application.yml`): media storage root (`powercms.media.storage-root`), upload
limit (`powercms.media.max-file-size`, default 100MB), search backend (`powercms.search.backend`,
default `memory`, switchable to `opensearch`), mail (`powercms.mail.enabled`, default off), and
automatic incremental SSG builds (`powercms.ssg.auto-build-enabled`, default off).

### Verify the install

| Endpoint | URL |
|---|---|
| App metadata | `GET http://localhost:8080/api/v1/meta` |
| Health check | `GET http://localhost:8080/actuator/health` |
| OpenAPI docs | `http://localhost:8080/swagger-ui.html` |
| Delivery REST | `GET http://localhost:8080/api/v1/delivery/...` |
| Delivery GraphQL | `POST http://localhost:8080/graphql/sites/{siteId}` |
| Delivery MVC rendering | `GET http://localhost:8080/mvc/sites/{siteKey}/**` |

### Build and test

```bash
cd cms-common && mvn install                  # runs tests
cd ../cms-core && mvn clean test              # backend tests incl. Modulith boundary tests
cd ../cms-core && mvn clean package           # executable jar
```

### License

Apache License 2.0 — see [LICENSE](LICENSE).
