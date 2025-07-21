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

----


**`loongarch32r-linux-gnusf-` 工具链**

这个工具链前缀（`loongarch32r-linux-gnusf-`）非常具体，它表明：

*   `loongarch32r`: 目标架构是 LoongArch 32位精简版。
*   `linux`: 目标操作系统是 Linux。
*   `gnusf`: 这通常表示使用 GNU 工具链，并且可能包含了软浮点（Soft Float）支持。

推荐了使用 Docker 镜像 `chenyy/la32r-env:latest` 来获取这个工具链。这是目前最简单、最可靠的方法，因为这个 Docker 镜像已经预配置好了所有必要的工具。

### 使用 Docker 获取 `loongarch32r-linux-gnusf-` 工具链

按照 CEMU 文档的指示，步骤如下：

1.  **安装 Docker：**
    如果你的系统还没有安装 Docker，你需要先安装它。
    *   **Arch Linux (你的系统):**
        ```bash
        sudo pacman -S docker
        sudo systemctl start docker
        sudo systemctl enable docker
        sudo usermod -aG docker $USER # 将当前用户添加到docker组，这样你就不需要每次都使用sudo
        # 退出并重新登录，使组成员更改生效
        ```
    *   **其他发行版：** 请参考 Docker 官方文档进行安装。

2.  **拉取 Docker 镜像：**
    ```bash
    docker pull chenyy/la32r-env:latest
    ```
    这会下载包含 LoongArch 32位工具链的 Docker 镜像。

3.  **启动 Docker 容器：**
    ```bash
    docker run -dit \
        --name la32r-docker \
        --net=host \
        -e LANG=en_US.UTF-8 \
        -e LANGUAGE=en_US.UTF-8 \
        -e LC_ALL=en_US.UTF-8 \
        chenyy/la32r-env:latest
    ```
    *   `--name la32r-docker`: 给容器一个易于识别的名称。
    *   `-dit`: 后台运行容器，并分配一个伪终端。
    *   `--net=host`: 让容器使用主机的网络栈，这样容器内的服务可以直接访问主机网络。
    *   `-e LANG=...`: 设置环境变量，确保语言和编码正确。

4.  **进入 Docker 容器：**
    ```bash
    docker exec -it la32r-docker /bin/zsh
    ```
    *   `docker exec`: 在运行中的容器内执行命令。
    *   `-it`: 交互式伪终端。
    *   `la32r-docker`: 你之前给容器起的名称。
    *   `/bin/zsh`: 在容器内启动 zsh shell (指定，你也可以尝试 `/bin/bash` 如果 zsh 不存在或你不喜欢)。

5.  **在容器内验证工具链：**
    进入容器后，你就可以直接使用 `loongarch32r-linux-gnusf-` 前缀的命令了。
    ```bash
    # 在 Docker 容器内部执行
    loongarch32r-linux-gnusf-gcc --version
    loongarch32r-linux-gnusf-objdump --version
    ```
    你应该能看到 GCC 和 Binutils 的版本信息，确认工具链已正确安装并可用。

### 如何在容器外使用 `objdump` 反汇编文件？

如果你想在容器外部（你的 Arch Linux 主机）反汇编文件，你有两种选择：

1.  **将文件复制到容器内进行反汇编：**
    这是最直接的方法。
    *   **从主机复制文件到容器：**
        ```bash
        docker cp <your_binary_file> la32r-docker:/tmp/your_binary_file
        ```
    *   **进入容器并反汇编：**
        ```bash
        docker exec -it la32r-docker /bin/zsh
        # 在容器内部
        loongarch32r-linux-gnusf-objdump -d /tmp/your_binary_file > /tmp/disassembly.txt
        exit # 退出容器
        ```
    *   **从容器复制结果回主机：**
        ```bash
        docker cp la32r-docker:/tmp/disassembly.txt ./disassembly.txt
        ```

2.  **将容器内的工具链二进制文件映射到主机 (高级，不推荐新手)：**
    理论上，你可以通过 Docker 卷将容器内的工具链 `bin` 目录映射到主机，然后在主机上直接调用。但这种方法通常比较复杂，容易出现路径和依赖问题，不推荐。使用 `docker cp` 是更可靠和简单的方式。
