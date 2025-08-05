### 关键地址汇总

标签 / 功能    地址         来源文件  描述与作用
EBASE          80001000   evec.S    异常处理入口 (Exception Base)。当CPU检测到任何异常（如非法指令、内存访问错误）时，PC会强制跳转到这个地址。
SHELL          800021e0   shell.S   主交互循环入口。这是内核完成初始化后，或处理完一条命令后，返回到的“待机”状态。它会调用READSERIAL等待新的命令。
.OP_R          80002214   shell.S   'R' (Read Registers) 命令处理程序入口。当SHELL接收到'R'命令后，会跳转到这里，开始执行寄存器转储的逻辑。
.OP_A          800022b0   shell.S   'A' (Write Memory) 命令处理程序入口。接收到'A'后跳转至此，开始执行接收并写入内存数据的流程。
.OP_G          8000230c   shell.S   'G' (Go) 命令处理程序入口。接收到'G'后跳转至此，准备上下文切换并执行用户程序。
.USERRET2      800023b0   shell.S   用户程序返回点 (User Return)。当用户程序执行jirl zero, $ra, 0返回时，PC会跳转到这个地址，恢复内核上下文。
.DONE          80002454   shell.S   命令处理完成的公共返回点。所有命令（如 'R', 'A', 'G'）的 handler 在执行完毕后，通常会跳转到这里，然后从这里再统一跳回SHELL主循环。
WRITESERIAL    80002000   utils.S   串口写字节函数入口。负责将a0寄存器中的一个字节发送到串口。被内核的打印功能（如.OP_R和WELCOME）频繁调用。
READSERIAL     80002028   utils.S   串口读字节函数入口。负责从串口接收一个字节并放入a0。SHELL主循环和.OP_A等都会调用它来接收数据。
READSERIALWORD 80002050   utils.S   串口读字函数入口。负责调用四次READSERIAL并将其结果拼接成一个32位的字，存入a0返回。被.OP_A, .OP_D, .OP_G用于接收地址和长度。
[DRIVER]

UTEST_SIMPLE: 0x80003000
UTEST_STREAM: 0x80003008
UTEST_MATRIX: 0x80003030
UTEST_CRYPTONIGHT: 0x800030b4
