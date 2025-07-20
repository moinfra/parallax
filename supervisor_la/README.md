# supervisor-32-la: 32位龙芯监控程序

本项目是一个为32位龙芯（LoongArch）架构设计的简易监控程序（Monitor）。它运行在裸机（或QEMU模拟器）上，通过串口提供一个交互式命令行界面，允许开发者加载、执行和调试小程序。

该项目是为“龙芯杯”竞赛而创建的，旨在提供一个最小化的底层调试环境。

## 功能特性

监控程序通过串口接收单个字符的命令，实现核心功能：

- **`r` (read registers)**: 读取并打印用户空间寄存器的状态。
- **`d` (dump memory)**: 打印从指定内存地址开始的一段数据。
- **`a` (write memory)**: 向指定内存地址写入一段数据。
- **`g` (go)**: 跳转到指定的内存地址开始执行程序。

## 目录结构

```
.
├── kernel/         # 核心监控程序
│   ├── kern/       # 内核汇编源代码 (.S)
│   ├── include/    # 头文件
│   ├── Makefile    # 编译脚本
│   └── kernel.bin  # 编译产物
├── term/           # 上位机串口终端程序
│   └── term.py     # Python实现的终端
└── utility/        # 辅助工具
    └── gen_matrix.py # 生成矩阵测试数据
```

## 环境准备

1.  **交叉编译工具链**: 需要安装 `loongarch32r-linux-gnusf-` 工具链。
    -   `loongarch32r-linux-gnusf-gcc`
    -   `loongarch32r-linux-gnusf-ld`
    -   `loongarch32r-linux-gnusf-objcopy`
2.  **Python 3**: 用于运行上位机终端程序 `term.py`。
3.  **(可选) 硬件或模拟器**:
    -   一块龙芯FPGA开发板。
    -   或使用QEMU来模拟龙芯运行环境。

## 如何编译

进入 `kernel` 目录，直接运行 `make` 命令。

```bash
cd kernel
make
```

编译成功后，将在当前目录下生成 `kernel.bin` 和 `kernel.elf` 文件。`kernel.bin` 是我们最终需要加载到硬件或模拟器中运行的二进制程序。

## 如何使用

整个流程分为两部分：在目标（FPGA/QEMU）上运行 `kernel.bin`，然后在主机上运行 `term.py` 与其交互。

1.  **运行监控程序**: 将 `kernel.bin` 加载到你的龙芯FPGA开发板，或通过QEMU启动。

2.  **连接交互终端**: 在你的主机上，打开一个新的命令行窗口，进入 `term` 目录，运行Python终端脚本。

    ```bash
    cd term
    python3 term.py
    ```
    > **注意**: `term.py` 默认会连接到 `/dev/ttyS0`。如果你的串口设备不同，请根据实际情况修改 `term.py` 文件中的 `SERIAL_PORT` 变量。

3.  **开始交互**: 连接成功后，终端会等待你输入命令。

## 示例：加载并运行一个矩阵计算程序

这里演示如何加载一个预先编译好的矩阵计算程序，并执行它。

1.  **生成测试数据**:
    `utility` 目录下的 `gen_matrix.py` 可以生成测试用的矩阵数据。
    ```bash
    cd utility
    python3 gen_matrix.py
    ```
    这会生成 `matrix.in` 和 `matrix.out` 文件。`matrix.in` 是我们需要加载到内存中的输入数据。

2.  **加载代码和数据**:
    假设你的矩阵计算程序的二进制代码文件名为 `matrix_test.bin`。
    在 `term.py` 终端中，使用 `a` (write memory) 命令，将 `matrix_test.bin` 和 `matrix.in` 的内容加载到目标的内存中。
    -   例如，加载代码到 `0x1000` 地址。
    -   加载数据到 `0x2000` 地址。

3.  **执行程序**:
    使用 `g` (go) 命令，跳转到程序的入口地址（例如 `0x1000`）开始执行。
    ```
    g
    (终端会提示你输入地址)
    1000
    ```

4.  **查看结果**:
    程序执行完毕后，会返回到监控程序。此时你可以使用 `d` (dump memory) 命令查看存放结果的内存区域，并与 `matrix.out` 的内容进行比对，以验证程序的正确性。
