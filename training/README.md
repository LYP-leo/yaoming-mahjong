# 要命麻将 AI 训练包（RTX 3060 Ti 8GB 起步版）

这是**能实际模拟完整对局、训练并评估的研究基线**，不是已经训练好的高手模型。训练完全离线，不需要启动 Vue、Spring Boot、数据库或连接线上网站。

Windows 安装请先看 [INSTALL_WINDOWS.md](INSTALL_WINDOWS.md)，本次验证边界见 [TEST_REPORT.md](TEST_REPORT.md)。

## 快速开始

在安装了 Python 3.11 和 Java 21 的 Windows 机器上，解压完整包，进入包含本 README 的 `yaoming-training` 目录：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup_windows.ps1
.\.venv\Scripts\python.exe -m yaoming_rl.doctor --device cuda
```

脚本只在包内创建 `.venv`；不会动全局 Python，不需要管理员权限。首次安装下载 PyTorch 与依赖，安装好后训练无需联网。

建议先从规则机器人模仿 1 万次决策，获得比完全随机更有意义的起点：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.imitate --rule yaoming-3p --device cuda --steps 10000 --output runs/teacher-three/latest.pt
```

这一步是监督模仿，不是强化学习，也不代表已经超过老师。随后启动真正的 PPO 自我对弈：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.train --rule yaoming-3p --device cuda --init runs/teacher-three/latest.pt --steps 10000000 --run runs/three
```

可以省略 `--init`，直接从随机权重开始，但麻将得分奖励稀疏，早期可能很长时间都是流局。建议先用 `--steps 4096` 与新的 `--run runs/smoke-three` 检查机器运行情况，确认后再增加预算。

四人模型必须单独训练；将上面两个命令的 `--rule` 改成 `yaoming-4p`，老师输出改为 `runs/teacher-four/latest.pt`，PPO 目录改为 `runs/four`。不要直接用三人模型给四人续训。

## 硬件与环境

- 专用显存预算：**8GB**。47.8GB 共享 GPU 内存是系统 RAM，不是额外高速显存。
- 默认策略/价值网络：两层 256 隐藏单元的 MLP，**411,075 个参数**。
- 默认并行环境 8 个、每轮 128 个学习决策/环境、minibatch 256、4 个 PPO epoch。
- 采样缓存保留在 CPU 内存；只把当前推理批次和更新 minibatch 放入显卡。Java 默认最大堆 1GB。
- 安装固定 Python 3.11（推荐，接受 3.12/3.13）、PyTorch 2.9.1+cu126、NumPy 2.1.3；运行预编译模拟器只需 Java 21+。
- 不依赖 LLM、CUDA Toolkit 编译器、Maven 或外部日麻模型。

`doctor --device cuda` 会报告显卡的**专用显存**、实际 PyTorch CUDA runtime、默认 minibatch 的前向/反向更新测试与 PyTorch 峰值分配。这个峰值不含 Windows 桌面/驱动等额外占用，也不代表长时间训练的峰值。本机交付测试仅使用 CPU，没有伪称实测过 3060 Ti。

## 支持什么规则和动作？

训练用的是 `engine-src` 内的原 Java 规则源码快照，包含所有当前番型、真实摸打、吃碰杠、响应优先级、支付、破产终局与轮庄。训练专用适配器只去掉网络、等待计时和牌谱记录等开销。

| 规则 | 牌集 | 起和 / 封顶 | 最多轮次 |
|---|---|---|---:|
| `yaoming-3p` | 当前三人要命，27 种、108 张，159 万特殊顺子 | 4 / 8 番 | 6 |
| `yaoming-4p` | 当前四人要命，34 种、136 张 | 3 / 8 番 | 8 |

不是日本三人麻将或标准国标麻将。若将来规则改变，需更新引擎快照、重新构建并重新验证；检查点的引擎哈希防止悄悄混用不兼容规则。

模型输出 194 个固定动作槽，非法动作概率严格屏蔽为 0：34 种弃牌、和、过，以及不同牌面的吃碰明杠暗杠加杠。READY/DRAW/ACK 由环境自动处理，不浪费训练动作。

## 训练算法与信息边界

- 观测为 1152 维：自己的暗手与自摸牌，公共牌河/摸切计数、公开副露、公开分数和牌数、圈风门风、规则与当前响应牌。
- 对手暗手、牌山顺序和其他玩家尚未解决的响应不会进入模型。
- 每张桌指定一个学习座位；Python 驱动冻结模型或规则对手直到再次轮到同一座位，才形成一条 PPO transition。终局之后轮换学习座位。
- 奖励为该学习座位两次有效决策之间的**净分差除以 10**，包括其他玩家行动产生的结算；默认 `gamma=1`。优化目标是净得分，不是专门优化第一名概率。
- 对局 episode 是一整场（含多局），不是单独一手。MATCH_END 不做价值 bootstrap，也不会把重开局后的奖励接到旧对局。
- 默认每 10 次 PPO 更新替换一次冻结对手快照。可选 `--opponent heuristic` 或 `--opponent random` 做控制实验。
- PPO rollout 的旧策略概率、动作掩码和价值保持固定，使用 GAE、裁剪目标、价值损失、熵项和梯度裁剪。

### 已知研究限制

这不是完整的高水平麻将 AI 方案：没有循环网络的完整弃牌顺序记忆、对手池联赛、长期信念推断或搜索增强。当前牌河输入是聚合计数；同牌面实体动作合并，存在刚摸到的同种牌时优先打该实体，暂不单独学习这种情况下的手切/摸切信息策略。

自我对弈可以退化、过拟合或长期流局；模仿也可能只学到老师的弱点。损失下降、脚本跑完、短局获胜都不能证明棋力提升。不能承诺训练固定时间就超过现有机器人，更不能把预生成和牌结构当作最优出牌答案。

## 评估

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.evaluate --checkpoint runs/three/latest.pt --opponent heuristic --matches 300 --seed 700000 --device cuda --output runs/eval-three.json
```

同一种子轮换所有座位后才换下一个种子；建议三人对局数取 3 的倍数、四人取 4 的倍数。相同种子固定初始化/洗牌，但不同策略的吃碰杠会改变之后实际摸牌，不能保证整场拿牌完全相同。

报告包含每场种子/座位、最终分数、平均名次、和牌率、是否第一（含并列）等。全场流局时大家可能都算“并列第一”，不能单独用这一项声称模型很强。名次并列按平均名次统计，是无座位偏好的评估指标；不是对游戏界面座位破同分排序的修改。

保持相同评估种子和对手比较不同 checkpoint；扩大样本并检查多个独立训练种子后再讨论强弱。`--output` 不会覆盖现有文件。

## 断点续训、停止与磁盘空间

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.train --resume runs/three/latest.pt --device cuda --steps 20000000
```

- `--steps` 是累计目标，不是额外步数。实际完成量最多多 `num_envs-1` 步以完成当前并行批次。
- 恢复模型、优化器、冻结对手、训练配置和 Python/PyTorch 随机状态。模拟器重新开局，因此不是断点处原对局的逐位复现。
- 恢复时改变规则或网络/算法配置会报错；要开新实验，用新 `--run`，需要旧权重时使用 `--init`。
- 模仿预热 checkpoint 必须用 `--init` 开始 PPO，不接受直接 `--resume`。
- 新 PPO 训练在开局前先写 0-step 安全 checkpoint。训练阶段按一次 Ctrl+C 会等待当前完整 PPO update 完成，保存再退出；再按一次则立即中断，保留上一个已完成检查点。
- 默认每 10 次 update、完成或安全停止时保存 `latest.pt` 和一个 `step-XXXXXXXXXXXX.pt` 历史快照。历史快照不自动删除，长训练请监控磁盘空间；需要少存一些可增大 `--checkpoint-every`。
- `metrics.jsonl` 记录实际吞吐、PPO 损失、回报和已完成对局。均分/名次为 null 表示当前采样段没有结束整场，不是程序出错。
- 模仿脚本在完成目标时保存；如果中途强行关闭，未完成的预热不会自动续上。先小规模验证再加大预热数据量。

只加载自己或可信来源的权重。虽然使用 PyTorch restricted `weights_only=True` 加载，仍不要把不明模型文件视为可信程序输入。

## 文件结构与重新构建

| 位置 | 用途 |
|---|---|
| `yaoming_rl/bridge.py` | 本机 Java 子进程协议、超时、校验和与关闭 |
| `yaoming_rl/env.py` | 同一学习座位的完整 transition |
| `yaoming_rl/model.py`、`learning.py` | 网络、动作掩码、GAE、PPO |
| `yaoming_rl/train.py`、`checkpoint.py` | 训练、续训和版本化保存 |
| `yaoming_rl/imitate.py`、`evaluate.py`、`doctor.py` | 预热、评估、设备自检 |
| `java/`、`java-tests/` | 无界面适配器与 Java 断言测试 |
| `engine-src/` | 原游戏规则引擎源码快照 |
| `simulator/` | 预编译 JAR、依赖与完整性清单 |

测试：

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

重新编译需要 JDK 21；原项目、Maven 和网络不是必需的（依赖 JAR 已在包里）：

```powershell
.\.venv\Scripts\python.exe build_simulator.py --test
```

只有在原项目中、确认要更新训练规则快照时才使用 `--refresh-engine`。测试失败不会替换之前可用的模拟器 JAR。JAR 和依赖会做 SHA-256 完整性检查，代码指纹也写进 checkpoint；这用于防止误混版本，不是第三方发布者身份认证。

## 训练完怎么接入游戏？

保存并保留评估报告与表现合适的 `step-*.pt`。本包**不会自动发布到线上，也没有把未验证模型替换现有机器人**。后续需要选定模型、实现后端推理适配，再验证延迟、合法动作与故障回退。
