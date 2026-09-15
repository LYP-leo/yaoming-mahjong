# mahjong_graphic 上游牌面测试报告

日期：2026-09-08。对应编码前计划：`UPSTREAM_TILE_ASSETS_PLAN.md`。

## 实施结果

- 按用户最新决定停用所有截图牌面及截图裁切管线。旧截图、旧映射、生成脚本和截图测试移至 `archive/screenshot-tiles-20260908/`，可追溯但不参与前端构建、测试或运行；移除 Sharp 开发依赖。
- 使用指定仓库提交 `3e275804ff58325306710bef3a7406860444bc6a` 下 `Vectors 矢量图/SVG` 的 37 个源文件。每个文件及 LICENSE 与 Git blob 字节一致；消除了 Windows checkout 自动 CRLF 换行带来的校验差异。
- 单一 SVG 图集包含 34 种普通牌和 0m/0p/0s 原生红五，排列为 171×130 矢量坐标。源路径、几何、填色及纵横比保持不变，仅增加单元位置和命名空间隔离。
- 字牌游戏 H5/H6/H7 映射上游 7z/6z/5z；保持中/发/白顺序。没有角标、字体、重绘、截图或染色滤镜；上游红五本身的圆点等细节原样保留。
- LICENSE、PROVENANCE.md 与 manifest.json 随产物公开分发，保留上游 README 的 I.Mahjong → GL-MahjongTile 归属链，不推断额外许可证。

## 本地自动验证

Windows、Node 24.11.1；23:24 开始的最终全量执行：

| 命令/检查 | 实际结果 |
| --- | --- |
| `npm test` | 15 文件、442 项通过 |
| `npm run build` | 素材校验、vue-tsc、Vite 生产构建通过 |
| 上游素材测试 | 65 项，源哈希/原几何/样式隔离/可重建/只读校验/拒绝危险 SVG |
| 共享牌面测试 | 149 项，34+3 映射、红五、31 种非五误标不借牌、非法牌、ARIA、暗牌隐私、唯一 clip、原有 helper 兼容 |
| 下游渲染/编辑测试 | 9 项，手牌/牌河/副露、结算实体唯一、暗杠完整展示、只读复盘、听牌无动作、编辑约束 |
| 其他现有前端回归 | 设置/自动摸牌、计时/交互、SSE、状态同步、结算/复盘等通过 |

442 项包含 30 项历史 `tileGlyphs` helper 单测；这些 helper 不参与本次生产牌面渲染，不作为新素材正确性的依据。没有重跑后端全套规则测试，本次未修改后端。

## 浏览器视觉检查

browser-act 技能因本机无 CLI/uv 未能运行，采用现有 Codex 应用内浏览器检查真实 Vue 组件。

- 本地 `tile-art-preview.html`：已逐看完整 34 牌目录；七筒七条、7 字牌三种尺寸；三种普通五和原生红五；吃碰明杠横置、加杠叠牌、暗杠隐藏/结算翻开。图案、比例、颜色未见串牌或变形。
- 手机 390×844 视口下检查七筒七条大小尺寸；文档可视宽与 scrollWidth 同为 375，无页面横向溢出。验收后已恢复默认视口。
- 预览 DOM 中 129 个正面 image 全部共用唯一 `/src/assets/mahjong-graphic-atlas.svg`；没有旧截图 URL。暗牌隐私由实际组件测试确认。
- 本地预览只使用隔离样例，不连接真实牌局、不创建机器人或房间、不写玩家数据。

## 资源数据

- 最终图集：250,050 字节；SHA-256 `4e4337f4aa7f9b0ed43134007c10e4cdeb7b42da66ffbd4ff8457af49433ef0f`。
- 生产文件：`mahjong-graphic-atlas-C-odaEvj.svg`；Vite 估算 gzip 为 31.90 kB，这不是公网已启用 gzip 的承诺。
- 新当前入口：`index-z0JZxHmm.js`，旧版入口块 `LegacyApp-CHxSiTg8.js`，均引用同一新图集。

## 发布验收

- 更新前备份：`/home/leo/mahjong_20260822/backups/upstream-tiles-20260908/before-upstream.tar.gz`（源码及旧 dist）和 `public-before.tar.gz`（原静态站点）。
- 更新前后端：active，PID 1843067，启动时间 2026-09-08 20:56:27 CST。
- 服务器 Node 20.20.2：23:26 开始执行 `npm ci --no-audit --no-fund`、`npm test`、`npm run build`，15 文件、442 项全部通过，素材只读检查/类型检查/构建通过。npm 提示现有 whatwg-encoding/glob 包的弃用警告；本次不扩展做框架依赖升级。
- 23:26 完成静态发布：先复制新哈希资源和许可文件，再原子替换 index.html；未修改 FRP/Nginx、后端、规则或玩家数据。后端 PID/启动时间与更新前一致，仍 active。
- 公网 HTTP 校验：首页、5 个当前 JS/CSS/SVG 资源，以及 3 个许可/来源文件共 9 项全部 HTTP 200，下载字节与本地 dist 完全相等。
- 公网 SVG 的 Content-Type 为 image/svg+xml，Cache-Control 为 30 天及 public, immutable。实际响应未启用 SVG gzip，当前该素材下载为 250,050 字节；31.90 kB 仅为 Vite gzip 估值。本次没有改动服务器压缩配置。
- 公网首页 SHA-256：`4e81a0c3eec37ab350b0eb9f84201d2263b51f8bc727c2203368d4f9bcf9916b`；SVG SHA 与上方最终图集一致。
- 应用内浏览器实际刷新 `http://82.156.207.98:5173/`，大厅显示已连接；打开规则手册后能看到新牌面。DOM 中 27 种用牌全部为 `data-artwork="mahjong-graphic"`，七条/七筒和中发白分别对应 7s/7p、7z/6z/5z；仅一个图片 URL `/assets/mahjong-graphic-atlas-C-odaEvj.svg`，旧 `reference-crop` 正面数量为 0。入口脚本为 `/assets/index-z0JZxHmm.js`。
- 旧哈希静态资源暂留服务器供尚未刷新的旧客户端使用；当前入口及其所有资源不再引用它们。源码中的截图和管线已归档，后续正常构建不会重新生成截图资源。

## 验收边界

本次是指定牌面素材替换和前端回归，不是新增规则、红宝牌玩法或整局多人实战验证；不将所有已有麻将功能宣称为无 bug。原生红五仅服务原有支持红牌的规则，要命麻将规则仍保持无红宝牌。
