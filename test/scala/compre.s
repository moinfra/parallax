/tmp/compre.bin:     file format binary


Disassembly of section .data:

0000000000000000 <.data>:
   0:   15008004        lu12i.w $r4,-523264(0x80400)
   4:   03800084        ori     $r4,$r4,0x0
   8:   02800005        addi.w  $r5,$r0,0
   c:   1403578a        lu12i.w $r10,6844(0x1abc)
  10:   03b7bd4a        ori     $r10,$r10,0xdef
  14:   15fdb96b        lu12i.w $r11,-4661(0xfedcb)
  18:   03aa616b        ori     $r11,$r11,0xa98
  1c:   00102d4c        add.w   $r12,$r10,$r11
  20:   2980008c        st.w    $r12,$r4,0
  24:   00112d4c        sub.w   $r12,$r10,$r11
  28:   2980108c        st.w    $r12,$r4,4(0x4)
  2c:   001c2d4c        mul.w   $r12,$r10,$r11
  30:   2980208c        st.w    $r12,$r4,8(0x8)
  34:   00152d4c        or      $r12,$r10,$r11
  38:   2980308c        st.w    $r12,$r4,12(0xc)
  3c:   0014ad4c        and     $r12,$r10,$r11
  40:   2980408c        st.w    $r12,$r4,16(0x10)
  44:   0015ad4c        xor     $r12,$r10,$r11
  48:   2980508c        st.w    $r12,$r4,20(0x14)
  4c:   0017ad4c        srl.w   $r12,$r10,$r11
  50:   2980608c        st.w    $r12,$r4,24(0x18)
  54:   02bc014c        addi.w  $r12,$r10,-256(0xf00)
  58:   2980708c        st.w    $r12,$r4,28(0x1c)
  5c:   0200016c        slti    $r12,$r11,0
  60:   2980808c        st.w    $r12,$r4,32(0x20)
  64:   0200014c        slti    $r12,$r10,0
  68:   2980908c        st.w    $r12,$r4,36(0x24)
  6c:   03b7bd4c        ori     $r12,$r10,0xdef
  70:   2980a08c        st.w    $r12,$r4,40(0x28)
  74:   0377bd4c        andi    $r12,$r10,0xdef
  78:   2980b08c        st.w    $r12,$r4,44(0x2c)
  7c:   0040954c        slli.w  $r12,$r10,0x5
  80:   2980c08c        st.w    $r12,$r4,48(0x30)
  84:   0044954c        srli.w  $r12,$r10,0x5
  88:   2980d08c        st.w    $r12,$r4,52(0x34)
  8c:   15579bcc        lu12i.w $r12,-344866(0xabcde)
  90:   2980e08c        st.w    $r12,$r4,56(0x38)
  94:   2980f08a        st.w    $r10,$r4,60(0x3c)
  98:   2880f08c        ld.w    $r12,$r4,60(0x3c)
  9c:   2980f08c        st.w    $r12,$r4,60(0x3c)
  a0:   2901008a        st.b    $r10,$r4,64(0x40)
  a4:   2801008c        ld.b    $r12,$r4,64(0x40)
  a8:   2981008c        st.w    $r12,$r4,64(0x40)
  ac:   2901108a        st.b    $r10,$r4,68(0x44)
  b0:   2a01108c        ld.bu   $r12,$r4,68(0x44)
  b4:   2981108c        st.w    $r12,$r4,68(0x44)
  b8:   1401110d        lu12i.w $r13,2184(0x888)
  bc:   03a21dad        ori     $r13,$r13,0x887
  c0:   2880008e        ld.w    $r14,$r4,0
  c4:   580009ae        beq     $r13,$r14,8(0x8) # 0xcc
  c8:   028004a5        addi.w  $r5,$r5,1(0x1)
  cc:   14059e2d        lu12i.w $r13,11505(0x2cf1)
  d0:   038d5dad        ori     $r13,$r13,0x357
  d4:   2880108e        ld.w    $r14,$r4,4(0x4)
  d8:   580009ae        beq     $r13,$r14,8(0x8) # 0xe0
  dc:   028004a5        addi.w  $r5,$r5,1(0x1)
  e0:   15da0bcd        lu12i.w $r13,-77730(0xed05e)
  e4:   03afa1ad        ori     $r13,$r13,0xbe8
  e8:   2880208e        ld.w    $r14,$r4,8(0x8)
  ec:   580009ae        beq     $r13,$r14,8(0x8) # 0xf4
  f0:   028004a5        addi.w  $r5,$r5,1(0x1)
  f4:   15ffffed        lu12i.w $r13,-1(0xfffff)
  f8:   03bffdad        ori     $r13,$r13,0xfff
  fc:   2880308e        ld.w    $r14,$r4,12(0xc)
 100:   580009ae        beq     $r13,$r14,8(0x8) # 0x108
 104:   028004a5        addi.w  $r5,$r5,1(0x1)
 108:   1401110d        lu12i.w $r13,2184(0x888)
 10c:   03a221ad        ori     $r13,$r13,0x888
 110:   2880408e        ld.w    $r14,$r4,16(0x10)
 114:   580009ae        beq     $r13,$r14,8(0x8) # 0x11c
 118:   028004a5        addi.w  $r5,$r5,1(0x1)
 11c:   15feeeed        lu12i.w $r13,-2185(0xff777)
 120:   039dddad        ori     $r13,$r13,0x777
 124:   2880508e        ld.w    $r14,$r4,20(0x14)
 128:   580009ae        beq     $r13,$r14,8(0x8) # 0x130
 12c:   028004a5        addi.w  $r5,$r5,1(0x1)
 130:   1400000d        lu12i.w $r13,0
 134:   038005ad        ori     $r13,$r13,0x1
 138:   2880608e        ld.w    $r14,$r4,24(0x18)
 13c:   580009ae        beq     $r13,$r14,8(0x8) # 0x144
 140:   028004a5        addi.w  $r5,$r5,1(0x1)
 144:   1403578d        lu12i.w $r13,6844(0x1abc)
 148:   03b3bdad        ori     $r13,$r13,0xcef
 14c:   2880708e        ld.w    $r14,$r4,28(0x1c)
 150:   580009ae        beq     $r13,$r14,8(0x8) # 0x158
 154:   028004a5        addi.w  $r5,$r5,1(0x1)
 158:   1400000d        lu12i.w $r13,0
 15c:   038005ad        ori     $r13,$r13,0x1
 160:   2880808e        ld.w    $r14,$r4,32(0x20)
 164:   580009ae        beq     $r13,$r14,8(0x8) # 0x16c
 168:   028004a5        addi.w  $r5,$r5,1(0x1)
 16c:   1400000d        lu12i.w $r13,0
 170:   038001ad        ori     $r13,$r13,0x0
 174:   2880908e        ld.w    $r14,$r4,36(0x24)
 178:   580009ae        beq     $r13,$r14,8(0x8) # 0x180
 17c:   028004a5        addi.w  $r5,$r5,1(0x1)
 180:   1403578d        lu12i.w $r13,6844(0x1abc)
 184:   03b7bdad        ori     $r13,$r13,0xdef
 188:   2880a08e        ld.w    $r14,$r4,40(0x28)
 18c:   580009ae        beq     $r13,$r14,8(0x8) # 0x194
 190:   028004a5        addi.w  $r5,$r5,1(0x1)
 194:   1400000d        lu12i.w $r13,0
 198:   03b7bdad        ori     $r13,$r13,0xdef
 19c:   2880b08e        ld.w    $r14,$r4,44(0x2c)
 1a0:   580009ae        beq     $r13,$r14,8(0x8) # 0x1a8
 1a4:   028004a5        addi.w  $r5,$r5,1(0x1)
 1a8:   146af36d        lu12i.w $r13,219035(0x3579b)
 1ac:   03b781ad        ori     $r13,$r13,0xde0
 1b0:   2880c08e        ld.w    $r14,$r4,48(0x30)
 1b4:   580009ae        beq     $r13,$r14,8(0x8) # 0x1bc
 1b8:   028004a5        addi.w  $r5,$r5,1(0x1)
 1bc:   14001aad        lu12i.w $r13,213(0xd5)
 1c0:   03b9bdad        ori     $r13,$r13,0xe6f
 1c4:   2880d08e        ld.w    $r14,$r4,52(0x34)
 1c8:   580009ae        beq     $r13,$r14,8(0x8) # 0x1d0
 1cc:   028004a5        addi.w  $r5,$r5,1(0x1)
 1d0:   15579bcd        lu12i.w $r13,-344866(0xabcde)
 1d4:   038001ad        ori     $r13,$r13,0x0
 1d8:   2880e08e        ld.w    $r14,$r4,56(0x38)
 1dc:   580009ae        beq     $r13,$r14,8(0x8) # 0x1e4
 1e0:   028004a5        addi.w  $r5,$r5,1(0x1)
 1e4:   1403578d        lu12i.w $r13,6844(0x1abc)
 1e8:   03b7bdad        ori     $r13,$r13,0xdef
 1ec:   2880f08e        ld.w    $r14,$r4,60(0x3c)
 1f0:   580009ae        beq     $r13,$r14,8(0x8) # 0x1f8
 1f4:   028004a5        addi.w  $r5,$r5,1(0x1)
 1f8:   15ffffed        lu12i.w $r13,-1(0xfffff)
 1fc:   03bfbdad        ori     $r13,$r13,0xfef
 200:   2881008e        ld.w    $r14,$r4,64(0x40)
 204:   580009ae        beq     $r13,$r14,8(0x8) # 0x20c
 208:   028004a5        addi.w  $r5,$r5,1(0x1)
 20c:   1400000d        lu12i.w $r13,0
 210:   0383bdad        ori     $r13,$r13,0xef
 214:   2881108e        ld.w    $r14,$r4,68(0x44)
 218:   580009ae        beq     $r13,$r14,8(0x8) # 0x220
 21c:   028004a5        addi.w  $r5,$r5,1(0x1)
 220:   58000800        beq     $r0,$r0,8(0x8) # 0x228
 224:   028004a5        addi.w  $r5,$r5,1(0x1)
 228:   58000940        beq     $r10,$r0,8(0x8) # 0x230
 22c:   028004a5        addi.w  $r5,$r5,1(0x1)
 230:   5c000940        bne     $r10,$r0,8(0x8) # 0x238
 234:   028004a5        addi.w  $r5,$r5,1(0x1)
 238:   5c000800        bne     $r0,$r0,8(0x8) # 0x240
 23c:   028004a5        addi.w  $r5,$r5,1(0x1)
 240:   6800094b        bltu    $r10,$r11,8(0x8) # 0x248
 244:   028004a5        addi.w  $r5,$r5,1(0x1)
 248:   6800096a        bltu    $r11,$r10,8(0x8) # 0x250
 24c:   028004a5        addi.w  $r5,$r5,1(0x1)
 250:   1500000f        lu12i.w $r15,-524288(0x80000)
 254:   038971ef        ori     $r15,$r15,0x25c
 258:   4c0001e1        jirl    $r1,$r15,0
 25c:   028004a5        addi.w  $r5,$r5,1(0x1)
 260:   02800c0f        addi.w  $r15,$r0,3(0x3)
 264:   580008af        beq     $r5,$r15,8(0x8) # 0x26c
 268:   58000000        beq     $r0,$r0,0 # 0x268
 26c:   58000000        beq     $r0,$r0,0 # 0x26c
