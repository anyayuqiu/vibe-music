# Vibe Music 展示站：Ubuntu 24.04 + Docker Compose 部署

适用机器：2 核、2 GiB 内存、40 GiB 系统盘。音乐站与管理端先上线；静态博客以后可以加到同一台机器。本文使用 Caddy 容器提供 HTTPS、静态页面和反向代理，其他服务由 Docker Compose 管理。

> 将全文的 `music.example.com`、`admin.example.com` 换成自己的域名。以下命令分为 **Windows 本机** 和 **Ubuntu 服务器**，不要混用。本文是部署模板；运行前先读完数据迁移及安全检查。没有域名时可以先完成本地构建、服务器安装和数据准备，但自动 HTTPS 无法完成。

## 1. 上线前确认

1. 两个域名的 A 记录指向服务器公网 IP；如果使用中国内地服务器，确认域名备案与接入要求。
2. 阿里云防火墙和 Ubuntu 防火墙放行 SSH、TCP 80、TCP 443。数据库 3306、Redis 6379、MinIO 9000/9001、Java 8080 **不对公网开放**。
3. 规划数据量：40 GiB 需容纳系统、Docker 镜像、MySQL、MinIO 文件与备份。备份最终还应保存到服务器以外。
4. 只上传有使用权的封面与头像；生产库不能保留示例管理员密码哈希和歌曲音频地址。

## 2. 在 Windows 本机构建

在 `D:\Cloud\vibe-music` 下执行。项目已经将客户端普通请求和 AI 流式请求统一为同源 `/api`，管理端也使用 `/api`；生产构建不应带 `localhost` API 地址。

```powershell
cd D:\Cloud\vibe-music\vibe-music-client
node .\node_modules\vite\bin\vite.js build

cd D:\Cloud\vibe-music\vibe-music-admin
pnpm build

cd D:\Cloud\vibe-music\vibe-music-backend
.\mvnw.cmd -pl vibe-music-server -am -DskipTests package
```

需上传的产物：

- `vibe-music-client/dist/` → 服务器 `/opt/vibe-music/client/`
- `vibe-music-admin/dist/` → 服务器 `/opt/vibe-music/admin/`
- `vibe-music-backend/vibe-music-server/target/vibe-music-server-0.0.1-SNAPSHOT.jar` → 服务器 `/opt/vibe-music/app.jar`

不要上传 `node_modules`、本地 `application-dev.yml` 或含密码的配置文件。

## 3. 初始化 Ubuntu

从阿里云控制台“远程连接”进入服务器，或通过 SSH 登录。安装 Docker Engine 与 Compose 插件时使用 [Docker 官方 Ubuntu 安装说明](https://docs.docker.com/engine/install/ubuntu/)；安装后确认：

```bash
docker --version
docker compose version
```

2 GiB 内存建议先配置 2 GiB swap，并限制 Java 堆大小。以下命令只在尚无 swap 文件时执行：

```bash
free -h
swapon --show
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

创建目录并上传前述构建产物。示例使用普通 SSH 账号操作；若当前账号无法写 `/opt`，先用 `sudo mkdir -p` 创建目录，再把目录所有权交给该账号。

```bash
sudo mkdir -p /opt/vibe-music/{client,admin,config,data/mysql,data/redis,data/minio}
sudo chown -R "$USER:$USER" /opt/vibe-music
cd /opt/vibe-music
```

Windows 可用 `scp` 上传；将 `SERVER_USER`、`SERVER_IP` 换成实际值。若没有配置 SSH 密钥，按 SSH 提示输入密码。

```powershell
scp -r D:\Cloud\vibe-music\vibe-music-client\dist\* SERVER_USER@SERVER_IP:/opt/vibe-music/client/
scp -r D:\Cloud\vibe-music\vibe-music-admin\dist\* SERVER_USER@SERVER_IP:/opt/vibe-music/admin/
scp D:\Cloud\vibe-music\vibe-music-backend\vibe-music-server\target\vibe-music-server-0.0.1-SNAPSHOT.jar SERVER_USER@SERVER_IP:/opt/vibe-music/app.jar
```

## 4. 在服务器创建配置

在 `/opt/vibe-music` 创建 `.env`，只放域名：

```dotenv
MUSIC_DOMAIN=music.example.com
ADMIN_DOMAIN=admin.example.com
```

另建 `.env.private`。所有示例值都要替换；不要提交到 Git、截图或聊天中。密码建议使用随机长字符串，并避免空格、引号和 `$` 等需要额外转义的字符。

```dotenv
MYSQL_ROOT_PASSWORD=REPLACE_WITH_RANDOM_VALUE
MYSQL_DATABASE=vibe_music
MYSQL_USER=vibe
MYSQL_PASSWORD=REPLACE_WITH_RANDOM_VALUE
REDIS_PASSWORD=REPLACE_WITH_RANDOM_VALUE
MINIO_ROOT_USER=REPLACE_WITH_RANDOM_VALUE
MINIO_ROOT_PASSWORD=REPLACE_WITH_RANDOM_VALUE
VIBE_JWT_SECRET=REPLACE_WITH_AT_LEAST_32_RANDOM_CHARACTERS
VIBE_ALLOWED_ORIGIN=https://music.example.com,https://admin.example.com
VIBE_MINIO_PUBLIC_URL=https://music.example.com/media
VIBE_MAIL_HOST=REPLACE_WITH_SMTP_HOST
VIBE_MAIL_USERNAME=REPLACE_WITH_MAIL_ADDRESS
VIBE_MAIL_PASSWORD=REPLACE_WITH_MAIL_AUTH_CODE
DASHSCOPE_API_KEY=REPLACE_WITH_API_KEY
VIBE_INITIAL_ADMIN_USER=REPLACE_WITH_NEW_ADMIN_NAME
VIBE_INITIAL_ADMIN_PASSWORD=REPLACE_WITH_AT_LEAST_12_CHARACTERS
```

管理员初始账号只在 `tb_admin` 为空时创建。验证登录成功后，从 `.env.private` 删除最后两行并重建后端容器。先不要把这些变量写进长期保存的 Compose 文件。

创建 `config/application-prod.yml`。文件位于 JAR 外部，服务器更新 JAR 时不会覆盖；不要使用本机的 `application-dev.yml`：

```yaml
vibe-music:
  jwt:
    secret: ${VIBE_JWT_SECRET}
  datasource:
    url: jdbc:mysql://mysql:3306/vibe_music?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER}
    password: ${MYSQL_PASSWORD}
  redis:
    host: redis
    port: 6379
    password: ${REDIS_PASSWORD}
  minio:
    endpoint: http://minio:9000
    accessKey: ${MINIO_ROOT_USER}
    secretKey: ${MINIO_ROOT_PASSWORD}
    bucket: vibe-music-data
  mail:
    host: ${VIBE_MAIL_HOST}
    username: ${VIBE_MAIL_USERNAME}
    password: ${VIBE_MAIL_PASSWORD}
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
```

设置权限：

```bash
chmod 600 .env.private config/application-prod.yml
```

## 5. 创建 Compose 文件

在 `/opt/vibe-music/compose.yaml` 写入以下内容。镜像首次使用后建议固定到已验证的具体版本或 digest，再进行后续升级。

```yaml
name: vibe-music

services:
  mysql:
    image: mysql:8.0
    env_file: .env.private
    command: ["--innodb-buffer-pool-size=128M", "--max-connections=40", "--performance-schema=OFF"]
    volumes:
      - ./data/mysql:/var/lib/mysql
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    env_file: .env.private
    command: ["sh", "-c", "exec redis-server --appendonly yes --maxmemory 128mb --maxmemory-policy noeviction --requirepass \"$$REDIS_PASSWORD\""]
    volumes:
      - ./data/redis:/data
    restart: unless-stopped

  minio:
    image: quay.io/minio/minio:latest
    env_file: .env.private
    command: server /data --console-address :9001
    volumes:
      - ./data/minio:/data
    restart: unless-stopped

  backend:
    image: eclipse-temurin:17-jre
    env_file: .env.private
    environment:
      VIBE_PROFILE: prod
      SPRING_CONFIG_ADDITIONAL_LOCATION: file:/config/
      JAVA_TOOL_OPTIONS: -Xms128m -Xmx512m
    command: ["java", "-jar", "/app/app.jar"]
    volumes:
      - ./app.jar:/app/app.jar:ro
      - ./config/application-prod.yml:/config/application-prod.yml:ro
    depends_on:
      - mysql
      - redis
      - minio
    restart: unless-stopped

  caddy:
    image: caddy:2-alpine
    env_file: .env
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ./client:/srv/client:ro
      - ./admin:/srv/admin:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - backend
    restart: unless-stopped

volumes:
  caddy_data:
  caddy_config:
```

Compose 内部网络允许服务互访；除 Caddy 外没有映射到宿主机端口。MySQL、Redis、MinIO 和 Java 不需要也不应该在阿里云防火墙中添加入站规则。

## 6. 创建 Caddyfile

在 `/opt/vibe-music/Caddyfile` 写入：

```caddyfile
{$MUSIC_DOMAIN} {
    root * /srv/client

    handle_path /api/* {
        reverse_proxy backend:8080 {
            flush_interval -1
        }
    }

    handle_path /media/* {
        reverse_proxy minio:9000
    }

    handle {
        try_files {path} /index.html
        file_server
    }
}

{$ADMIN_DOMAIN} {
    root * /srv/admin

    handle_path /api/* {
        reverse_proxy backend:8080 {
            flush_interval -1
        }
    }

    handle {
        try_files {path} /index.html
        file_server
    }
}
```

`/api/` 会去掉前缀再交给 Java，例如 `/api/admin/login` → `/admin/login`。`/media/vibe-music-data/xxx` 会交给 MinIO 的 `/vibe-music-data/xxx`。AI SSE 使用 `flush_interval -1` 保持逐段输出。Caddy 在 DNS 已生效且 80/443 可达时自动申请并续期证书。

## 7. 启动基础服务、导入数据

先检查 Compose 配置，再启动基础服务：

```bash
cd /opt/vibe-music
docker compose config --quiet
docker compose up -d mysql redis minio
docker compose ps
```

首次初始化 MySQL 后再导入数据库。**如果要保留本地测试过的数据，先从本地 MySQL 导出，再上传 SQL 文件；不要同时导入项目示例 SQL 和本地全量备份。** 本地导出的示例：

```powershell
mysqldump --single-transaction --routines --triggers --result-file=D:\Cloud\vibe-music\vibe_music_backup.sql -u YOUR_DB_USER -p vibe_music
scp D:\Cloud\vibe-music\vibe_music_backup.sql SERVER_USER@SERVER_IP:/opt/vibe-music/vibe_music_backup.sql
```

服务器导入：

```bash
cd /opt/vibe-music
docker compose exec -T mysql sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" vibe_music' < vibe_music_backup.sql
```

全新部署可使用 `sql/vibe_music.sql` 建表，但脚本会重建同名数据库和表，且不包含业务数据。只在空数据库中导入；不要对正在使用的数据库执行。管理员表保持为空，首次启动时由 `VIBE_INITIAL_ADMIN_USER` 和 `VIBE_INITIAL_ADMIN_PASSWORD` 创建管理员。迁移旧数据库时应先备份，并检查示例账号、音频地址和图片授权。

MinIO 对象需迁移到服务器上的 `vibe-music-data` Bucket。优先用 MinIO Client (`mc`) 在本地与服务器之间 `mirror`，不要猜测 `D:\Cloud\minio` 或 `music-data` 中哪个文件夹是实际对象存储目录。Bucket 仅放封面与头像，上传图片需允许匿名读取，才能让公开页面加载；不要把私密文件放进该 Bucket。创建与设置 Bucket 的例子（在服务器执行）：

```bash
docker run --rm --network vibe-music_default --env-file /opt/vibe-music/.env.private --entrypoint /bin/sh quay.io/minio/mc:latest -c 'mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" && mc mb --ignore-existing local/vibe-music-data && mc anonymous set download local/vibe-music-data'
```

旧数据库图片字段里的 `http://localhost:9000/vibe-music-data/` 地址不会自动变更。迁移对象并备份数据库后，按实际使用的表和字段，将该前缀改为 `https://music.example.com/media/vibe-music-data/`，再抽样打开封面确认。歌曲的 `audio_url` 应清空为 `NULL`。若本地导出的数据库已包含真实用户账号，公开前还应确认这些账号是否应迁移；不要把测试账号直接带上线。

## 8. 启动网站并检查

```bash
cd /opt/vibe-music
docker compose up -d backend caddy
docker compose ps
docker compose logs --tail=100 backend
docker compose logs --tail=100 caddy
docker stats --no-stream
```

浏览器依次检查：

1. `https://music.example.com` 能打开，图片来自 HTTPS `/media/`，没有 `localhost` 请求。
2. `https://admin.example.com` 可用新管理员登录，并能上传一张测试封面。
3. 用户注册、登录、浏览歌单和 AI 流式回复正常；页面不提供音频播放或下载。
4. 观察 `docker stats`、`free -h`、`df -h`，确认内存、swap 和磁盘有余量。若持续大量使用 swap，2 GiB 内存已不足。

管理员验证后从 `.env.private` 删除 `VIBE_INITIAL_ADMIN_USER` 和 `VIBE_INITIAL_ADMIN_PASSWORD`，然后执行：

```bash
docker compose up -d --force-recreate backend
```

## 9. 更新与备份

更新时先在本地重新构建并上传 `dist` 与 JAR，再执行 `docker compose up -d --force-recreate backend caddy`。如只更新静态文件，可重载 Caddy或等待文件挂载生效；替换 JAR 后必须重建后端容器。

每天备份 MySQL 与 MinIO 对象，并将备份复制到服务器之外；定期做一次恢复演练。不要把 `.env.private`、备份 SQL 和 MinIO 数据目录放入公开静态目录。博客以后若是静态站，只需增加一个静态文件目录及 Caddy 域名块，无需再运行一套 MySQL/Redis。

## 常见故障

- **证书申请失败**：检查域名解析、阿里云防火墙 80/443、服务器上是否已有其他程序占用端口。
- **页面有内容但接口失败**：检查 `/api/` 代理、`docker compose logs backend` 和浏览器网络面板；确认 URL 不含访客电脑的 `localhost`。
- **封面失败**：检查 Bucket 内对象、匿名下载策略、旧图片 URL 是否迁移，以及 `/media/` 转发。
- **Java 启动失败**：检查 `application-prod.yml` 是否加载、数据库是否完成初始化、JWT 密钥长度和各服务密码。`depends_on` 只保证容器启动顺序，不保证 MySQL 已可接受连接；首次启动可在 MySQL 就绪后重启后端。
- **机器明显卡顿**：检查 `docker stats` 和 `free -h`；不要在 2 GiB 服务器上构建前端或 Maven 工程。
