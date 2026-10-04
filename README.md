# Vibe Music

一个带 AI 音乐资料助手的展示站，由 Vue 客户端、Vue 管理端和 Spring Boot 服务端组成。可以浏览歌曲、歌手、歌单和封面，使用收藏、评论等互动功能，并通过 AI 助手查询站内曲库。

> **当前版本仅展示音乐资料与图片，不提供音频播放或下载。** 点击播放会显示版权提示，服务端拒绝音频上传。仓库保留部分原有播放器界面代码；公开部署时请只使用有权展示的图片和资料。

## 页面预览

| 客户端首页 | 音乐资料 |
| --- | --- |
| ![客户端首页](./Vibe%20Music%20项目文档/attachment/1.webp) | ![歌曲列表](./Vibe%20Music%20项目文档/attachment/3.webp) |
| ![歌手页面](./Vibe%20Music%20项目文档/attachment/4.webp) | ![歌单页面](./Vibe%20Music%20项目文档/attachment/5.webp) |

| 账号与 AI 助手 | 管理端 |
| --- | --- |
| ![登录页面](./Vibe%20Music%20项目文档/attachment/2.webp) | ![管理端概览](./Vibe%20Music%20项目文档/attachment/8.webp) |
| ![收藏和 AI 助手](./Vibe%20Music%20项目文档/attachment/6.webp) | ![AI 推荐卡片](./Vibe%20Music%20项目文档/attachment/7.webp) |

## 功能特点

- **音乐资料展示**：首页推荐、歌曲和歌手列表、歌单详情、封面与分类查询。
- **账号与互动**：邮箱验证码注册、登录、密码找回、个人资料修改、收藏、评论、点赞和反馈。
- **AI 音乐资料助手**：基于 LangChain4j 调用站内曲库查询工具；正文通过 SSE 流式返回，推荐歌曲由服务端按 ID 查询后生成卡片。登录用户可继续会话，服务端设有请求频率和并发限制。
- **内容管理**：管理歌曲、歌手、歌单、封面、轮播图、用户和反馈，查看数据概览。
- **展示模式**：客户端不请求音频，服务端拒绝音频上传；封面和头像由 MinIO 保存。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 客户端 | Vue 3、TypeScript、Vite 6、Vue Router、Pinia、Element Plus、Tailwind CSS、Axios |
| 管理端 | Vue 3、TypeScript、Vite 6、Pure Admin 相关组件、Element Plus、ECharts |
| 服务端 | Java 17、Spring Boot 3.5、Spring MVC、MyBatis-Plus、Maven 多模块 |
| 基础服务 | MySQL 8、Redis、MinIO、SMTP |
| 认证与 AI | JWT、Spring Security Crypto、LangChain4j 1.0.1、兼容 OpenAI 协议的模型接口、SSE |
| 部署 | Docker Compose、Caddy、HTTPS |

```text
vibe-music/
├── vibe-music-client/      # 访客使用的前端
├── vibe-music-admin/       # 内容管理前端
├── vibe-music-backend/     # Java 多模块服务端
├── sql/                    # MySQL 建表脚本，不含账号或曲库数据
├── docs/                   # 部署与项目说明
└── Vibe Music 项目文档/     # 图文项目文档和截图
```

## 本地运行

需要 JDK 17、Node.js 18+、pnpm、MySQL 8、Redis 和 MinIO。邮件注册需要 SMTP 配置；AI 功能需要兼容 OpenAI 协议的模型 API Key。

1. 在**空的测试数据库**导入 [`sql/vibe_music.sql`](./sql/vibe_music.sql)。脚本只包含表结构，会重建同名数据库和表；不要导入到已有数据的数据库。启动 Redis 和 MinIO，并创建 `vibe-music-data` Bucket。
2. 在 `vibe-music-backend/vibe-music-server/src/main/resources/` 创建本地 `application-dev.yml`。参照 [`application.yml`](./vibe-music-backend/vibe-music-server/src/main/resources/application.yml) 填写 MySQL、Redis、MinIO、邮件、模型 API Key 和至少 32 字符的 JWT 密钥。该文件已被 Git 忽略，不要提交密钥。
3. 设置 `VIBE_INITIAL_ADMIN_USER` 和至少 12 字符的 `VIBE_INITIAL_ADMIN_PASSWORD`，在空管理员表首次启动时创建管理员；创建后移除这两个环境变量。
4. 启动后端：

   ```bash
   cd vibe-music-backend
   ./mvnw -pl vibe-music-server -am -DskipTests package
   java -jar vibe-music-server/target/vibe-music-server-0.0.1-SNAPSHOT.jar
   ```

   Windows PowerShell 使用 `./mvnw.cmd`。

5. 分别在 `vibe-music-client/` 和 `vibe-music-admin/` 运行 `pnpm install`、`pnpm dev`。默认端口分别为 `8090` 和 `8089`，服务端为 `8080`；开发代理将 `/api` 转发给服务端。

SQL 不附带曲库和账号数据。可通过管理端自行录入有权展示的资料和图片。

## 部署

生产前端通过同源 `/api` 访问服务端，经 HTTPS `/media` 读取 MinIO 图片。服务端使用外部 `application-prod.yml`，密钥和密码通过环境变量提供。请勿提交本机 `application-dev.yml`、生产配置、数据库备份或 MinIO 数据。

详细步骤见 [Ubuntu Docker Compose 部署指南](./docs/Ubuntu-Docker-Compose-部署.md)。上线前检查管理员账号、历史数据库中的音频 URL、图片授权、CORS 来源和备份策略。低流量的 2 核 2 GiB 服务器可作为起点，建议在本地完成构建并持续观察内存占用。

## 文档

- [图文项目文档](./Vibe%20Music%20项目文档/zh-cn.md)
- [功能与技术说明](./docs/项目说明.md)
- [展示站上线准备](./docs/展示站部署准备.md)

## 来源与许可

本项目基于 [Alex-LiSun 的 Vibe Music 项目](https://github.com/Alex-LiSun)修改。详见 [NOTICE](./NOTICE)；代码按 [MIT License](./LICENSE) 发布。
