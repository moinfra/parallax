#include <regdef.h>

    .p2align 2
    .text
    .global WRITESERIAL
    .global READSERIAL
    .global READSERIALWORD

WRITESERIAL:                            // 2000 写串口：将a0的低八位写入串口
#ifdef MACH_FPGA
la.global    t1,SerialStat          // 0x2000 2004 (伪指令，占用8字节)
.TESTW:
ld.b        t0,t1,0x0               // 查看串口状态 // 0x2008
andi        t0,t0,0x001             // 截取写状态位 // 0x200C
bne         t0,zero,.WSERIAL        // 状态位非零可写进入写 // 0x2010
b           .TESTW                  // 检测验证，忙等待 // 0x2014
.WSERIAL:
la.global    t1,SerialData          // 0x2018 (伪指令，占用8字节)
st.b        a0,t1,0x0               // 写入 // 0x2020
jirl        zero,ra,0x0             // 0x2024
#else                               // QEMU模拟器上的写串口操作
#TODO
#endif

    READSERIAL:                             // 2028 读串口：将读到的数据写入a0低八位
    #ifdef MACH_FPGA
    la.global    t1,SerialStat          // 0x2028 (伪指令，占用8字节)
    .TESTR:
    ld.b        t0,t1,0x0               // 查看串口状态 // 0x2030
    andi        t0,t0,0x002             // 截取读状态位 // 0x2034
    bne         t0,zero,.RSERIAL        // 状态位非零可读进入读 // 0x2038
    b           .TESTR                  // 检测验证 // 0x203C
    .RSERIAL:
    la.global    t1,SerialData          // 0x2040 (伪指令，占用8字节)
    ld.b        a0,t1,0x0               // 读出 // 0x2048
    jirl        zero,ra,0x0             // 0x204C
#else //ifdef MACH_FPGA             // QEMU模拟器上的读串口操作
#TODO
#endif //ifdef MACH_FPGA

READSERIALWORD:                 // 2050
addi.w      sp,sp,-0x14             // 保存ra,s0 // 0x2050
st.w        ra,sp,0x0               // 0x2054
st.w        s0,sp,0x4               // 0x2058
st.w        s1,sp,0x8               // 0x205C
st.w        s2,sp,0xC               // 0x2060
st.w        s3,sp,0x10              // 0x2064

bl          READSERIAL              // 0x2068
or          s0,zero,a0              // 0x206C
bl          READSERIAL              // 0x2070
or          s1,zero,a0              // 0x2074
bl          READSERIAL              // 0x2078
or          s2,zero,a0              // 0x207C
bl          READSERIAL              // 0x2080
or          s3,zero,a0              // 0x2084

andi        s0,s0,0x0FF             // 截取低八位 // 0x2088
andi        s3,s3,0x0FF             // 0x208C
andi        s2,s2,0x0FF             // 0x2090
andi        s1,s1,0x0FF             // 0x2094
or          a0,zero,s3              // 0x2098
slli.w      a0,a0,8                 // 0x209C
or          a0,a0,s2                // 0x20A0
slli.w      a0,a0,8                 // 0x20A4
or          a0,a0,s1                // 0x20A8
slli.w      a0,a0,8                 // 0x20AC
or          a0,a0,s0                // 0x20B0

ld.w        ra,sp,0x0               // 0x20B4
ld.w        s0,sp,0x4               // 0x20B8
ld.w        s1,sp,0x8               // 0x20BC
ld.w        s2,sp,0xC               // 0x20C0
ld.w        s3,sp,0x10              // 0x20C4
addi.w      sp,sp,0x14              // 0x20C8
jirl        zero,ra,0x0             // 0x20CC
