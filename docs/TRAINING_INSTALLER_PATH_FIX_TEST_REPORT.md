# Windows 训练安装器路径修复测试记录

日期：2026-09-12。计划见 `TRAINING_INSTALLER_PATH_FIX_PLAN.md`。

## 已完成修复

`training/setup_windows.ps1` 新增 `Resolve-ApplicationPath`，在读取 `.Source` 前只取首个 Application。Python 选择、Java PATH 回退、两个命令执行辅助函数统一使用，保留显式 Python 路径和 JAVA_HOME 优先级。安装说明补充临时绕过方式和 Conda 通知说明。

## 实测结果

- Windows PowerShell 5.1.26100.9444：本机 PATH 命中两个 Python，旧写法转字符串确实拼接两个路径；新版返回一个 `System.String`，与首个匹配一致。
- 抽取脚本函数（不运行安装主体）后，通过 `Get-CheckedOutput -FilePath python` 成功执行 Python 并输出实际解释器位置。
- 显式 `C:/Program Files/Java/jdk-21.0.10/bin/java.exe` 路径含空格，`-version` 成功执行并捕获 stderr；Python exit 7 被正确识别为失败。
- 原有 Python 回归再次执行：`test_learning.py`、`test_bridge_integration.py`、`test_cli_integration.py` 共 **40 项通过，55.95 秒**。包含 Java 21 的真实训练、续训、评估流程。
- `git diff --check` 通过。

## 边界与交接

上述多 PATH 检查为实际 PowerShell 手工诊断，不应误称已新增自动化用例。新增多路径自动测试工作被对话打断，尚未交付测试文件。没有完整重装依赖，没有 NVIDIA CUDA 实测，也未修改目标设备上的副本。

用户随后选择在有显卡的另一台设备使用 Codex 继续。当前修复脚本已可单独复制替换；原 `artifacts/yaoming-training-3060ti-20260912.zip` **尚未重打包，仍包含旧安装器**。另一台设备需替换脚本或使用显式 `-PythonExecutable` 绕过。没有删除任何既有虚拟环境，也未部署线上网站。
