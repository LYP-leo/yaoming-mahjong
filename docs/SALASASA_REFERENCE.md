# salasasa 参考记录 · 2026-09-07

本次只读查看用户指定的 [salasasa 官网](https://salasasa.cn/) 与 [open_mahjong_unity 仓库](https://github.com/xelnagamiao/open_mahjong_unity)。查看时的 master 提交为 `19b51fcfde61f70967945dfa170bd8416f530655`。官网首页可读取，为 Vue 单页应用；没有创建账户、对局或访问游戏服务 API。

## 查看内容与采用的设计

- [gamestate/readme.md](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_server/server/gamestate/readme.md)：房间生命周期与各规则对局编排分离。
- [虹雀模块职责说明](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_server/server/gamestate/game_hongque/README.md) 与 [state_machine.py](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_server/server/gamestate/game_hongque/state_machine.py)：合法行动检查、状态迁移、规则计分、快照广播独立；服务器状态具有版本。
- [action_priority.py](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_server/server/gamestate/game_hongque/action_priority.py) 与 [ron_resolution.py](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_server/server/gamestate/game_hongque/ron_resolution.py)：先收集同一弃牌的响应，再按规则优先级和座位统一仲裁，避免网络先到者改变规则结果。
- [game2d/lib/types.ts](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_web/client/src/game2d/lib/types.ts)：公共座位快照与当前观察者的手牌、合法动作分开。我们的 Java 服务同样输出每位玩家的裁剪视图。
- [game2d/game/scene](https://github.com/xelnagamiao/open_mahjong_unity/tree/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_web/client/src/game2d/game/scene) 中 `Hand.ts`、`River.ts`、`MahjongScene.ts`：其 2D 客户端为 Vue + PixiJS；参考手牌、牌河、副露独立区域的布局思路，本项目继续使用 Vue 组件与原有 SVG 牌面。
- [game2d/salasasa/client.ts](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_web/client/src/game2d/salasasa/client.ts)：连接状态、页面切换期间消息、恢复与结算生命周期需要独立管理。我们的实现采用版本化 HTTP 私有快照，而非复用该客户端协议。

本项目没有发现或复用名为“要命麻将”的现成规则实现；玩法以用户提供的 DOCX 为依据。以上仅作为架构参考，新 Java 引擎和 Vue 页面独立编写，没有复制该仓库源码或下载、导入其美术资源。

## 授权边界

根 [LICENSE](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/LICENSE) 为 MIT，版权声明为 `Copyright (c) 2025 Xel`；复制实质源码须保留版权与许可声明。

[README 许可说明](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/README.md) 另有边界：Unity `Assets/Resources` 不适用通用 MIT 授权；第三方玩法有补充声明；Salasasa 名称、品牌及其服务器 API 有独立使用限制。本项目不采用这些资源、名称或服务。

2D Regular 牌面的 [SOURCE.md](https://github.com/xelnagamiao/open_mahjong_unity/blob/19b51fcfde61f70967945dfa170bd8416f530655/open_mahjong_web/client/public/game2d-assets/textures/riichi-mahjong-tiles/Regular/SOURCE.md) 指向 [FluffyStuff/riichi-mahjong-tiles](https://github.com/FluffyStuff/riichi-mahjong-tiles)，原作者声明资产为 CC0。此处仅记录其来源，本次没有将该资产包引入项目。
