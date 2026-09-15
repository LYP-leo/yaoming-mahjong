# 在 Windows / RTX 3060 Ti 上训练要命麻将 AI

这是一套可以离线模拟对局、训练和评估的小模型 PPO 基线，不是已经练成高手的模型。无需启动网页、原后端服务、数据库或连接线上房间。三人和四人使用随包保存的真实 Java 规则引擎，分别训练。

## 先确认显存

你的 RTX 3060 Ti 有 **8GB 独立显存**。任务管理器中的 47.8GB“共享 GPU 内存”来自系统内存，不能把它当成额外 47.8GB 的高速显存；本包按 **8GB** 规划。微软解释了[专用与共享 GPU 内存的区别](https://devblogs.microsoft.com/directx/gpus-in-the-task-manager/)，NVIDIA 也说明了[显存不足时回退到系统内存会降低速度](https://nvidia.custhelp.com/app/answers/detail/a_id/5490/~/system-memory-fallback-for-stable-diffusion)。

默认模型很小，采样缓冲区放在系统内存。不要为“用满 55.8GB”而增大模型或批次，也不需要修改显卡驱动的共享内存策略。**交付机器没有 NVIDIA GPU，3060 Ti 的实际峰值显存和训练速度尚未实测**；请先跑下面的设备检查和短训练。

## 1. 准备三个东西

1. Windows 64 位，安装适用于 RTX 3060 Ti、支持 CUDA 12.6 runtime 的 NVIDIA 显卡驱动。打开新终端运行 `nvidia-smi`，应能看到显卡。该命令显示的 `CUDA Version` 是驱动支持的信息，不等于 Python 环境实际安装的 CUDA runtime 版本；后面的自检会报告 PyTorch runtime。
2. Python 3.11 64 位，推荐用 Miniconda/Miniforge 管理。脚本也接受 3.12、3.13；本机 CPU 回归环境是 Python 3.13.5、PyTorch 2.9.1+cpu。
3. Java 21 或更高版本的 64 位运行时，把 `java.exe` 所在目录加入 `PATH`，重新打开终端，确认 `java -version` 显示 21 或更高。可用 [Eclipse Temurin 官方发行版](https://adoptium.net/temurin/releases/?version=21)。仅运行训练包不要求 Maven，不要求 Java 编译器；重建规则引擎才需要 JDK。

如果设置了 `JAVA_HOME`，本包会优先使用它下面的 `bin/java.exe`，请确认它没有指向旧版 Java。

**不需要单独安装 CUDA Toolkit、cuDNN 或 Visual Studio 编译器。** 本包使用官方预编译 PyTorch wheel。固定 PyTorch 2.9.1 + CUDA 12.6，依据 [PyTorch 官方历史版本安装说明](https://pytorch.org/get-started/previous-versions/#v291)；没有使用 torchvision/torchaudio，避免无关依赖。

首次安装要下载 PyTorch 和 Python 依赖，需要访问 `download.pytorch.org` 与 PyPI，并预留数 GB 磁盘空间。安装完成后，训练和评估不需要联网。

## 2. 解压、创建环境

把完整训练压缩包解压到一个新目录，例如 `D:\yaoming-ai`。压缩包包含 `yaoming-training` 顶层文件夹。后续所有命令都在**包含 `setup_windows.ps1` 的目录**运行，而不是在原麻将项目根目录运行。

在已初始化 Conda 的 PowerShell 中：

```powershell
conda create -n yaoming-python python=3.11 -y
conda activate yaoming-python
cd D:\yaoming-ai\yaoming-training
python --version
java -version
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup_windows.ps1
```

最后一个命令只为这一子进程允许运行脚本，不永久修改执行策略，不需要管理员权限。脚本会用当前激活的 Python 创建当前目录的 `.venv`，所有训练依赖只装到 `.venv`；Conda 环境仅提供 Python 解释器。

如果已有 Python 3.11、不想使用 Conda，可以指定它的完整路径：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup_windows.ps1 -PythonExecutable "C:\Python311\python.exe"
```

示例路径请替换为你的实际安装路径。如果用 Windows Python Launcher，可以先用 `py -3.11 -c "import sys; print(sys.executable)"` 找到路径。

安装脚本会检查 Python、Java、依赖、实际 CUDA 运算和随包模拟器。遇到错误会停止，不会偷偷切到 CPU，不会删除已有 `.venv`；已有环境只有在基础解释器一致时才会复用。换 Python 时，最简单的做法是把训练包重新解压到一个新目录。

成功后**不用激活 `.venv`**，直接使用它的 Python，可避免终端混用解释器：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.doctor --device cuda
```

CPU 备用安装路径：在全新的解压目录运行安装脚本时加 `-CpuOnly`，随后把所有训练命令的 `--device cuda` 改成 `--device cpu`。

## 3. 先跑短训练

先用较小配置检查环境和吞吐，不要上来就跑几天：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.train --rule yaoming-3p --device cuda --steps 4096 --run runs/smoke-three --num-envs 2 --horizon 64 --minibatch 128
```

`--steps` 是学习玩家的有效决策样本数，不是完整对局数，也不是自动摸牌次数。训练按并行批次推进，结束样本数最多比目标多 `num-envs - 1`。运行目录不要重复使用；想再做一次独立实验，换成 `runs/smoke-three-2`。续训使用后文的 `--resume`。

这段短训练用于检查“能不能跑”，不代表已经学会麻将。观察训练日志的样本数、损失、实际决策吞吐和回报；不要把单次回报增长当成棋力提升。

## 4. 正式训练

RTX 3060 Ti 8GB 的保守起步参数如下。先训练一套规则，不建议同时开两套抢同一块 GPU。

三人版：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.train --rule yaoming-3p --device cuda --steps 10000000 --run runs/three --num-envs 8 --horizon 128 --minibatch 256
```

四人版使用单独的模型和目录：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.train --rule yaoming-4p --device cuda --steps 10000000 --run runs/four --num-envs 8 --horizon 128 --minibatch 256
```

默认使用冻结快照对手的自我对弈（`--opponent selfplay`），每 10 次训练更新同步一次对手快照。不加 `--init` 时模型从随机权重起步，不会自动获得现有规则机器人的知识。推荐先按 README 的 `imitate` 命令模仿现有机器人，再用 `--init` 启动新的 PPO 训练，减少早期完全随机出牌的问题；这仍不保证训练效果。

可以另外开一个终端运行 `nvidia-smi -l 2` 观察显存。GPU 利用率不高不一定是错误：小模型、Java 对局推进和 Python 通信也可能是瓶颈。先测实际吞吐，再决定是否增大并行环境数，不要从上一次算番测试速度直接推算训练时长。

训练不能保证在某个固定样本数或时长后超过规则机器人；这是一组起步实验预算。请通过独立评估决定是否继续增加预算。

## 5. 评估与续训

与现有规则机器人打固定种子的测试对局：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.evaluate --checkpoint runs/three/latest.pt --opponent heuristic --matches 100 --device cuda --seed 10000
```

保持评估种子和对手一致，比较不同训练阶段；正式判断时增加对局数，并使用没有参加训练的种子。不要只挑胜利牌局查看。评估不回传梯度，不会让模型继续学习。

把同一次三人训练继续到总共 2000 万决策：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.train --device cuda --resume runs/three/latest.pt --steps 20000000
```

续训恢复检查点里的规则、模型、优化器和训练配置；`--steps` 是累计目标，不是“再加这么多步”。三人模型不能用四人配置直接续训。进行中的模拟对局会重新开局，不承诺逐动作完全复原中断前轨迹。

新 PPO 训练在开局前先写 0-step 安全检查点，随后默认每 10 次训练更新保存 `latest.pt` 和对应的 `step-XXXXXXXXXXXX.pt` 历史快照，正常结束也保存。训练阶段按一次 `Ctrl+C` 会请求在当前完整 PPO 更新完成后保存并退出；再按一次会立即中断，只保留上一个已完成检查点。不会保存半次优化更新。短实验希望更频繁保存，可以加 `--checkpoint-every 1`。初始化阶段还未进入安全停止处理时中断，可以从已写好的 0-step 检查点重新开始；强行杀进程或关机不能保证保存最新进度。历史快照不自动删除，长训练请监控磁盘空间。

每个运行目录包含 `config.json`（配置）、`metrics.jsonl`（逐次更新的指标）和 `latest.pt`（最新完整检查点）。

只加载自己训练或可信来源的 `.pt` 文件。模型检查点不是普通文本，切勿运行来历不明的训练包或加载陌生检查点。

## 6. 包内文件与复现

- `yaoming_rl/`：Python 环境桥、策略网络、训练器、评估器与设备检查。
- `simulator/simulator.jar`、`simulator/lib/`：已经编译的 Java 离线模拟器及依赖。
- `simulator/manifest.json`、`engine-src/`：规则引擎版本信息与源码快照，用于核对训练规则。
- `requirements.txt`：除 PyTorch 外的固定 Python 依赖。
- `tests/`：自动化回归测试。

查看全部参数：

```powershell
.\.venv\Scripts\python.exe -m yaoming_rl.train --help
.\.venv\Scripts\python.exe -m yaoming_rl.evaluate --help
.\.venv\Scripts\python.exe -m pytest -q
```

只有修改模拟器源码或需要验证源码重建时，才安装 JDK 21、确保 `javac` 可用后执行：

```powershell
.\.venv\Scripts\python.exe build_simulator.py --test
```

该训练包不会自动改动或发布你线上网站的机器人。训练完后，还需要选定通过评估的模型，再单独做游戏后端推理接入和兼容性测试。

## 常见问题

**报错中把多个 `python.exe` 路径连在一起**：这是旧版安装脚本的路径解析问题，不是 Conda 环境损坏。2026-09-12 的安装器修订版已修复：按 PATH 顺序只选第一个 Python/Java，或使用你显式指定的路径。替换同目录的 `setup_windows.ps1` 后直接重跑即可，不需要删除 `.venv` 或重装 Conda。

还未替换脚本时，可用完整路径绕过 Python 的多结果查询。比如用户的 Conda 环境位于下面这个位置（其他安装位置请自行替换）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup_windows.ps1 -PythonExecutable "C:\Users\Leo\anaconda3\envs\yaoming-python\python.exe"
```

新版同时修复了多 Java 路径的同类问题。若需要控制 Java 版本，用指向所需 JDK/JRE 的 `JAVA_HOME`；不要为此删除其他 Python/Java 安装。

**Conda 显示 `Channel "defaults" has the following notices` 和 `[info]`**：这是渠道通知，不是安装失败。是否失败要看后续命令的退出状态和错误内容，不能仅凭这段通知判断。

**CUDA 不可用 / 安装完只看到 CPU**：确认运行的是 `.venv\Scripts\python.exe`，`nvidia-smi` 能识别 3060 Ti，驱动支持选定 runtime，并运行 `doctor --device cuda`。不要盲目安装一串不同版本的 CUDA Toolkit。若先前用 `-CpuOnly` 安装，可用同一基础 Python 不带此参数重跑脚本，脚本会明确选择 CUDA wheel。

**CUDA out of memory**：先关闭游戏、其他模型和占显存应用，把 `--minibatch 256` 改成 `128` 或 `64`；必要时减少 `--num-envs` 和 `--horizon`，新实验换运行目录。不要把共享 GPU 内存当作解决显存不足的常规方案。

**模拟器缺失 / Java 版本错误**：确认解压的是完整包、没有只复制 `.py` 文件，并检查新终端中的 `java -version`。运行只需要 Java 21；只有重新编译才需要 JDK 21。

**依赖下载超时**：安装尚未完成。先确认网络能访问 PyTorch 官方 wheel 索引和 PyPI，再重跑安装脚本；不要用不明来源的二进制 wheel 替换。

**断点无法载入 / 规则哈希不一致**：不要跳过兼容性检查。使用原检查点对应的完整训练包，或用新目录重新开始实验；规则变动后直接混用旧样本可能破坏训练。
