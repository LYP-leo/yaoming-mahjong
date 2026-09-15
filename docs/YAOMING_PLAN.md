# 要命麻将重构计划 · 2026-09-07

## 依据与范围

依据用户提供《要命麻将规则集》Version 26.9 LTS / MITR / 2026.8.22。参考 salasasa 项目的房间与牌局分离、按规则独立状态机、客户端展示与服务端判定分离。继续采用 Java / Spring Boot + Vue 3，原有玩法保留为旧版入口，默认进入新“要命麻将”。不引入 salasasa 的受限制美术资源或调用其游戏服务。

先实现文档明确的无花“朴素规则”。文档红字内容为空；终场积分公式缺失，先显示实际点数排名；清全带幺按附录 B 的 4 番（总表写 3 番）。这三点已向用户发出非阻塞核对。

## 规则逐项映射

- 三人；每种四张；万 1/5/9，条/筒 1–9，东南西中发白，无北、花牌、红宝牌，共 108 张。
- 初始各 10 点；东一至南三共 6 局；每局轮庄，不连庄；任意一家 0 点立即终场。
- 随机洗牌、两次各三骰、根据开门家与第二掷点数切墙；三轮每人四张再各一张，共 13 张，庄家开始摸牌。
- 万 159 为唯一万子顺子；条/筒为普通顺子。只有下家可吃；碰/杠优先于吃；点和最高，多个点和按离出牌者最近截和。
- 摸牌、打牌、吃、碰、明杠、暗杠、加杠；杠从墙尾补牌。文档未规定抢杠和，因此不启用。
- 舍弃牌型禁止对此牌点和；跳过有效点和对同牌临时禁止，自己下一次摸牌解除。限制不扩散到其他等待牌，自摸不受限。
- 标准四面子雀头或风龙；不承认七对子。枚举合法拆分后取最高番单一组合，固定副露不可重组。
- 20 个番种：番牌、杠、门清、碰碰和、十二落抬、不求人、杠上炮、断幺、五门齐、混一色、混全带幺、清一色、清全带幺、混四连、风龙、清四连、全带五、九数齐、字一色、清三连。
- 风龙仅加不求人/杠上炮；字一色排除混全带幺/碰碰和/番牌，按附录仍加清一色；清一色排除混一色；清全带幺排除混全带幺。
- 每个杠 +1；番牌按圈风/门风/箭刻各 +1，同风叠加。数牌连续按数值相邻，同时 5 万与 1/9 万相邻；混四连为连续三个数值加一种字牌类别，清三/四连只包含相应连续数值。
- 至少 4 番可和、封顶 8；点和目标收取 3n、自摸每家 n，实际支付 min(应付,余额)，零和守恒。
- 无墙可摸则荒牌流局，无罚符。每局统一结果页，所有人确认后下一局；终场各自确认离开，最后一人离开清房。
- 三人首次准备完成后随机抽取东南西初始风位，随后固定座次依局数轮庄；十二落抬按四副露包括暗杠执行（原文暗杠放入副露区）。

## 架构与交付顺序

1. 独立纯 Java `yaoming` 计番器及拆牌算法，逐条规则用例验证。
2. 独立三人房间状态机 `WAITING/NEED_DRAW/NEED_DISCARD/REACTION/HAND_END/MATCH_END`；服务端输出当前玩家合法动作；版本校验/请求幂等/房间恢复令牌/隐私裁剪/原子快照。
3. Vue 分拆大厅、规则说明、牌桌、操作区、结果页；明确分区，自己的手牌在下方，上/右对手，副露、牌河、昵称不重叠；移动屏支持横向牌桌滚动而非挤压叠牌。
4. 机器人补位与托管：支持一名真人立即开局，也支持三人联机；机器人只用自己的手牌和公共信息决策。
5. 规则单元/状态转换/非法动作/支付/隐私/恢复/多房间及真实 HTTP 全流程；Vue 测试、构建、浏览器桌面与手机检查；文档记录实际结果。
6. 本地可玩后同步现有 `dell` 部署并校验公网。若服务器网络不可达，保留完整本地运行方式并明确未部署。

## 新接口契约

前缀 `/api/yaoming`；身份使用 `X-Resume-Token`，读房间同时传 `playerId` 查询参数。

- `GET /rules` → `{name,version,description,notes:string[],fans:{id,name,fan,description}[],tiles:Tile[]}`。
- `GET /rooms` → `{id,name,status,players,capacity:3}[]`。
- `POST /rooms {name,playerName}` / `POST /rooms/{id}/join {playerName}` / `POST /rooms/{id}/resume {token}` → `{roomId,playerId,token}`。
- `GET /rooms/{id}?playerId=...` → RoomView。
- `POST /rooms/{id}/actions {playerId,token,version,requestId,type,tileIds?:string[]}` → RoomView（LEAVE 返回 null）；`type` 由合法动作列表选择。
- RoomView: `{id,name,version,status,round,roundLabel,dealerSeat,currentSeat,wallCount,message,meId,players,actions,result,events,dice,deadlineAt}`。
- PlayerView: `{id,name,seat,wind,score,bot,ready,online,acknowledged,hand:Tile[],handSize,discards:Tile[],melds:Meld[],discardedCodes:string[],passedCodes:string[]}`；对手 hand/私有振听信息为空。
- Meld: `{type:'CHI'|'PONG'|'KONG',tiles:Tile[],fromSeat,claimedTileId,concealed,added}`；added 区分加杠的叠牌展示，旧快照默认为 false。
- Action: `{type,label,tileIds:string[]}`；吃的 tileIds 只含自己两张，杠含自己选用牌，自摸/过/准备等为空。
- Result: `{draw,matchOver,title,winnerId,winningTile,rawFan,fan,items:{id,name,fan,description}[],payments:{fromId,toId,amount,requested}[],scores:{playerId,name,score,delta,rank}[],hands:{playerId,hand:Tile[],melds:Meld[]}[],reason}`。
- events: `{sequence,text}` 最近最多 30 条公开事件；dice: `{opening:number[],breaking:number[],openingSeat,breakStack}`；无响应截止时 deadlineAt=null。
- 合法 action 类型包括 READY、ADD_BOT（房主）、DRAW、DISCARD、CHI、PONG、OPEN_KONG、CONCEALED_KONG、ADDED_KONG、WIN、PASS、ACK、LEAVE、TRUSTEE。actions 为当前视角专属白名单；所有参数后端重新验证。
- 前端 1 秒轮询带身份私有视图并忽略过期版本；写入期间串行，失败保留房间与明确错误；刷新/恢复必须重新同步服务器。

## 验收门槛

### 公网验收发现的部署调整

2026-09-07 实测公网 Vite 开发服务收到异常 URL 后，将 `URI malformed` 调试遮罩广播到已连接玩家。后端原进程亦已停止。收尾部署因此补充：Java 使用独立 systemd 服务自动启动；保留 FRP 的前端 5173 入口，由现有 Nginx 在本机 127.0.0.1:5173 提供已验证 Vue 静态构建及同源 API，停用仅供开发的前端 Vite 服务。先备份原 Nginx 配置和旧静态文件目录，不影响其他服务或旧牌局数据。

108 张实体牌始终守恒且唯一；私有手牌不泄露；不存在无合法推进方式的状态；没有 4 番以下和牌；资金总额恒为 30 且无负点；双方/三方看见同一结算；重复提交不重复扣分或摸牌；拒绝越权/过期写入；服务器重启恢复牌墙和状态；机器人整场可结束；手机/桌面看清牌面与动作。
