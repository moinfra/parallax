## 介绍

### Parallax CPU 架构总览

欢迎来到 Parallax 项目！这是一个基于 SpinalHDL 构建的、高度模块化的、支持乱序（Out-of-Order）CPU 架构。它的核心设计思想是**插件化**和**服务化**，这使得架构非常灵活、易于扩展和维护。

#### 1. 核心设计哲学：插件化框架 (`Framework.scala`)

与将整个 CPU 写在一个巨大的组件中不同，Parallax 采用了一种先进的**插件化框架（Plugin-based Framework）**。

- **`Framework` (`Framework.scala`):** 这是整个设计的“容器”。它不包含具体的 CPU 逻辑，而是负责管理所有插件的生命周期和它们之间的交互。
- **`Plugin` (`Plugin.scala`):** 每个核心功能（如取指、解码、执行单元、ROB等）都被实现为一个独立的 `Plugin`。插件本质上是一个自包含的硬件 `Area`。
- **服务 (`Service`):** 插件之间不直接相互引用。它们通过一个**服务发现**机制进行通信。一个插件可以提供一个或多个服务（例如 `ROBService`），而其他插件可以请求这些服务来获取功能或端口。这极大地降低了组件间的耦合度。
- **构建生命周期:** 框架定义了多个构建阶段（`early`, `late`）。
  - `early`: 用于插件设置、请求资源和声明它所提供的服务。
  - `late`: 用于连接硬件逻辑。此时所有服务和资源都已就绪，可以安全地进行连接。
  - `lock.await()`: 插件使用此机制确保在 `late` 阶段执行代码前，所有依赖的 `early` 设置都已完成。

#### 2. 流水线概览

Parallax 实现了一个经典的乱序执行、顺序提交的流水线。我们可以将其分为三大块：**前端（Frontend）**、**重命名与分派（Rename & Dispatch）** 和 **后端（Backend）**。

##### 2.1. 前端：顺序取指与预测

前端负责从内存中获取指令，并进行初步处理，为后续的乱序执行做准备。

- **PC生成与取指 (`FetchPipelinePlugin.scala`, `IFUPlugin.scala`):**
  - 一个程序计数器（PC）生成取指地址。
  - **IFU (Instruction Fetch Unit)** 插件 (`InstructionFetchUnit.scala`) 接收PC，并通过 `DataCacheService` 从指令缓存（或内存）中获取一个指令“组”（Fetch Group）。
  - `FetchBuffer.scala` 在取指和流水线其他部分之间提供了一个缓冲。

- **分支预测 (`BpuAndAlignerPlugin.scala`, `BpuService.scala`):**
  - 为了避免因控制流指令（分支、跳转）而产生的停顿，BPU 会在取指时对这些指令的走向和目标地址进行预测。
  - 如果预测跳转，它会通过 `FetchRedirectService` 请求PC立即跳转到预测的目标地址，从而使流水线能够推测性地执行正确路径上的指令。

- **预解码 (`InstructionPredecoder.scala`):**
  - 在指令进入主流水线之前，一个简单的预解码器会快速识别出指令是否是分支或跳转，以便BPU能够及时进行预测。

##### 2.2. 核心乱序引擎：重命名、分派与发射

这是从顺序执行过渡到乱序执行的核心。`IssuePipeline.scala` 定义了这一部分的主要流水线阶段 (`s0_decode`, `s1_rename`, `s2_rob_alloc`, `s3_dispatch`)。

- **解码 (`DecodePlugin.scala`):**
  - 接收从前端传来的原始指令。
  - 将其翻译成内部的微操作（`DecodedUop`），`Uop.scala` 中定义了其详细结构。这包含了操作类型、源/目标寄存器、立即数等信息。
  - 目前支持一个简单的自定义ISA (`SimpleDecoder`) 和一个 `LA32R` (龙芯) 指令解码器。

- **重命名 (`RenamePlugin.scala`):**
  - **目标:** 消除写后写（WAW）和写后读（WAR）伪依赖，为乱序执行铺平道路。
  - **`RenameMapTable` (RAT):** 维护一个从**架构寄存器**（如 `r1`, `r2`）到**物理寄存器**（如 `p1`, `p2` ... `p31`）的映射。
  - **`SuperScalarFreeList`:** 管理一个空闲的物理寄存器池。当一条指令需要写入结果时，从这里分配一个新的物理寄存器。
  - **`BusyTablePlugin`:** 跟踪哪些物理寄存器正在“飞行中”（即其值正在被某个尚未完成的指令计算）。
  - 此阶段的输出是 `RenamedUop`，其中所有架构寄存器都被替换成了物理寄存器标签。

- **ROB分配 (`RobAllocPlugin.scala`):**
  - 每条指令在**重排序缓冲区 (Reorder Buffer, ROB)** (`ReorderBuffer.scala`) 中被分配一个槽位。
  - ROB 是确保指令**顺序提交**的关键。它按指令原始顺序记录所有“飞行中”的指令及其状态（是否完成、有无异常）。

- **分派 (`DispatchPlugin.scala`):**
  - 将已经重命名并分配了ROB槽位的指令（`RenamedUop`）根据其操作类型（如 ALU、LSU、BRU）“分派”到对应的**发射队列 (Issue Queue)**。
  - 它通过 `IssueQueueService` 发现有哪些IQ可用以及它们处理哪些类型的指令。

##### 2.3. 后端：乱序执行与顺序提交

后端是真正进行乱序计算的地方，并在最后恢复程序顺序。

- **发射队列 (`IssueQueueComponent.scala`):**
  - 这是指令的“候车室”。指令在这里等待其所有源物理寄存器的值都准备就绪（即不再“busy”）。
  - 一旦一条指令的所有源操作数都准备好了，它就可以被“发射”（Issue）到执行单元，**而无需关心它在程序中的原始顺序**。

- **执行单元 (`EuBasePlugin.scala`):**
  - `EuBasePlugin` 是所有执行单元（Execution Unit, EU）的基类，它封装了与ROB、物理寄存器堆（PRF）、旁路网络等交互的通用逻辑。
  - **`AluIntEuPlugin`**: 整数算术逻辑单元。
  - **`BranchEuPlugin`**: 分支执行单元。它的关键职责是解析分支的实际结果，并与BPU的预测进行比较。如果预测错误，它会触发流水线冲刷（Flush），丢弃所有错误路径上的指令。
  - **`LsuPlugin`**: 加载/存储单元。这是最复杂的EU之一，内部包含：
    - **AGU (Address Generation Unit)**: 计算访存地址。
    - **Load Queue (LQ)**: 管理加载指令。
    - **Store Buffer (SB)**: 管理存储指令。它实现了**存储-加载前递（Store-to-Load Forwarding）**，允许加载指令从尚未写入缓存的存储指令中获取数据。

- **结果广播 (`BypassPlugin.scala`, `WakeupPlugin.scala`):**
  - 当一个EU完成计算后，它会通过 `BypassService` 将结果（物理寄存器标签+数据）广播给其他EU的输入端。这使得依赖该结果的指令可以立即执行，而无需等待结果写回寄存器堆，这被称为**旁路（Bypass）**。
  - 同时，它通过 `WakeupService` 将完成的物理寄存器标签广播给所有的发射队列，以“唤醒”正在等待这个寄存器的指令。

- **写回与提交 (`ROBPlugin.scala`):**
  - EU将执行状态（完成、异常等）写回指令在ROB中的对应条目。
  - **Commit** 阶段会顺序地检查ROB的头部。如果头部的指令已经完成且无异常，它的结果就被认为是“安全”的。此时，RAT中的架构寄存器映射会更新为这条指令写入的物理寄存器。该指令使用的旧物理寄存器会被释放回FreeList。这个过程保证了即使内部执行是乱序的，但对架构状态的修改（寄存器和内存）始终是按程序顺序进行的，从而保证了精确异常。
