# 服务器部署说明

## 2026-09-14 重装恢复（取代下方旧版开发服务说明）

- 项目 `/home/leo/mahjong_20260822`；Java 后端由 `mahjong-backend.service` 以 `leo` 运行 `backend/target/mahjong-server-0.1.0.jar`，监听 `127.0.0.1:8080`。
- 持久化数据 `backend/data/yaoming-rooms.json`。旧房间和牌谱必须有真实旧数据才能恢复，不上传本机测试数据。
- Vue 源码在项目的 `frontend`，正式静态站点在 `/var/www/mahjong-yaoming-20260907`；Nginx 监听 80 和本机 5173，`/api/` 同源转发后端，SSE 禁用缓冲。
- Nginx 配置 `/etc/nginx/sites-available/mahjong`，由 `sites-enabled/mahjong` 启用。来源 `deploy/nginx-mahjong.conf`。
- 复用已有 `frpc_tencent`；`vue` 公网 5173、`mahjong_web` 公网 80 均转发到本机 5173。不再运行 `mahjong-frontend` Vite 开发服务。
- 本地 JDK 21+/Maven 测试打包，前端 `npm test`、`npm run build` 通过后发布；服务器只需 Java 21+ 运行时和 Nginx，无需安装 Node/Maven。
- 本次首次恢复脚本 `scripts/deploy-fresh-dell-20260914.sh` 仅用于不存在旧项目/站点的服务器，校验包、JAR、首页哈希，拒绝覆盖已有部署，不修改 FRP 配置。不是日常增量更新脚本。

当前验证命令：

```bash
systemctl status mahjong-backend nginx frpc_tencent --no-pager
systemctl is-enabled mahjong-backend nginx frpc_tencent
curl -I http://127.0.0.1:5173/
curl http://127.0.0.1:5173/api/yaoming/rulesets
curl http://127.0.0.1:5173/api/yaoming/rooms
curl -I http://82.156.207.98:5173/
sudo journalctl -u mahjong-backend -n 80 --no-pager
sudo nginx -t
```

`/api/rooms` 和 SockJS `/ws` 属于退役版，不再作为健康检查。当前仍是 HTTP，尚无域名/TLS；后端 8080 不直接暴露公网。下面保留旧版历史说明，不能按旧版重启 Vite 取代正式站点。

## 当前部署结构

- `mahjong-frontend.service` 使用 Node.js 20 常驻运行 Vue/Vite 服务，监听 `127.0.0.1:5173`。
- Vite 的 `/api` 和 `/ws` 开发代理连接 Java 后端 `127.0.0.1:8080`，浏览器始终使用前端同源地址。
- Vite 自身的 API CORS 中间件已关闭，所有 `/api` OPTIONS 与业务请求统一代理到 Spring，由后端按部署来源校验。
- `frpc_tencent` 的 `[vue]` 隧道将腾讯云 `5173` 直接转发到 Vue 的 `127.0.0.1:5173`。
- `[mahjong_web]` 将腾讯云标准 HTTP 80 端口同样直接转发到 Vue 5173，便于不带端口访问。
- Nginx 及 `/var/www/mahjong` 构建产物保留为生产静态服务备用，但不再是 FRP 的转发目标。

配置源文件位于 `deploy/nginx-mahjong.conf`，服务器启用位置为 `/etc/nginx/sites-available/mahjong`。

## 更新前端

服务器已安装 Node.js 20。更新源码后执行：

```bash
cd frontend
npm ci
npm test -- --run
npm run build
```

完成后重启 Vue 常驻服务：

```bash
sudo systemctl restart mahjong-frontend
sudo systemctl status mahjong-frontend --no-pager
```

`mahjong-frontend.service` 的定义保存在 `deploy/mahjong-frontend.service`。当前按需求直接运行 Vue/Vite；正式高并发发布时仍建议改用 Nginx 提供构建产物。

## 访问地址

- 局域网：`http://192.168.3.101/`
- VPN：`http://10.190.131.87/`
- 腾讯云 FRP（主入口）：`http://82.156.207.98/`
- 腾讯云 FRP（备用入口）：`http://82.156.207.98:5173/`

FRP 当前使用明文 HTTP。正式对外发布时应在腾讯云入口绑定域名并配置 TLS；Java 后端 8080 没有通过 FRP 直接暴露。

若 Windows 开启了本地 HTTP 代理，代理可能拒绝访问裸公网 IP 并返回 502。此时应在系统代理直连列表中加入 `82.156.207.98`，修改后重启已打开的浏览器进程。

FRP 客户端配置位于 `/usr/local/lib/frp_0.63.0_linux_amd64/frpc_tencent.ini`，修改后执行：

```bash
sudo systemctl restart frpc_tencent
sudo systemctl status frpc_tencent --no-pager
```

## 验证

```bash
curl -I http://127.0.0.1/
curl -I http://127.0.0.1:5173/
curl http://127.0.0.1/api/rooms
curl -i http://127.0.0.1/ws/info
curl -I http://82.156.207.98/
```

预期首页为 200，房间 API 返回 JSON，SockJS info 返回 200 且包含 `websocket: true`。
