Disassembly of section .data:

0000000000000000 <.data>:
       0:       1c00006c        pcaddu12i       $r12,3(0x3)
       4:       2885d18c        ld.w    $r12,$r12,372(0x174)
       8:       4c000180        jirl    $r0,$r12,0
        ...
    2000:       1c00002d        pcaddu12i       $r13,1(0x1)
    2004:       288551ad        ld.w    $r13,$r13,340(0x154)
    2008:       280001ac        ld.b    $r12,$r13,0
    200c:       0340058c        andi    $r12,$r12,0x1
    2010:       5c000980        bne     $r12,$r0,8(0x8) # 0x2018
    2014:       53fff7ff        b       -12(0xffffff4) # 0x2008
    2018:       1c00002d        pcaddu12i       $r13,1(0x1)
    201c:       2884d1ad        ld.w    $r13,$r13,308(0x134)
    2020:       290001a4        st.b    $r4,$r13,0
    2024:       4c000020        jirl    $r0,$r1,0
    2028:       1c00002d        pcaddu12i       $r13,1(0x1)
    202c:       2884b1ad        ld.w    $r13,$r13,300(0x12c)
    2030:       280001ac        ld.b    $r12,$r13,0
    2034:       0340098c        andi    $r12,$r12,0x2
    2038:       5c000980        bne     $r12,$r0,8(0x8) # 0x2040
    203c:       53fff7ff        b       -12(0xffffff4) # 0x2030
    2040:       1c00002d        pcaddu12i       $r13,1(0x1)
    2044:       288431ad        ld.w    $r13,$r13,268(0x10c)
    2048:       280001a4        ld.b    $r4,$r13,0
    204c:       4c000020        jirl    $r0,$r1,0
    2050:       02bfb063        addi.w  $r3,$r3,-20(0xfec)
    2054:       29800061        st.w    $r1,$r3,0
    2058:       29801077        st.w    $r23,$r3,4(0x4)
    205c:       29802078        st.w    $r24,$r3,8(0x8)
    2060:       29803079        st.w    $r25,$r3,12(0xc)
    2064:       2980407a        st.w    $r26,$r3,16(0x10)
    2068:       57ffc3ff        bl      -64(0xfffffc0) # 0x2028
    206c:       00151017        or      $r23,$r0,$r4
    2070:       57ffbbff        bl      -72(0xfffffb8) # 0x2028
    2074:       00151018        or      $r24,$r0,$r4
    2078:       57ffb3ff        bl      -80(0xfffffb0) # 0x2028
    207c:       00151019        or      $r25,$r0,$r4
    2080:       57ffabff        bl      -88(0xfffffa8) # 0x2028
    2084:       0015101a        or      $r26,$r0,$r4
    2088:       0343fef7        andi    $r23,$r23,0xff
    208c:       0343ff5a        andi    $r26,$r26,0xff
    2090:       0343ff39        andi    $r25,$r25,0xff
    2094:       0343ff18        andi    $r24,$r24,0xff
    2098:       00156804        or      $r4,$r0,$r26
    209c:       0040a084        slli.w  $r4,$r4,0x8
    20a0:       00156484        or      $r4,$r4,$r25
    20a4:       0040a084        slli.w  $r4,$r4,0x8
    20a8:       00156084        or      $r4,$r4,$r24
    20ac:       0040a084        slli.w  $r4,$r4,0x8
    20b0:       00155c84        or      $r4,$r4,$r23
    20b4:       28800061        ld.w    $r1,$r3,0
    20b8:       28801077        ld.w    $r23,$r3,4(0x4)
    20bc:       28802078        ld.w    $r24,$r3,8(0x8)
    20c0:       28803079        ld.w    $r25,$r3,12(0xc)
    20c4:       2880407a        ld.w    $r26,$r3,16(0x10)
    20c8:       02805063        addi.w  $r3,$r3,20(0x14)
    20cc:       4c000020        jirl    $r0,$r1,0
    20d0:       494e4f4d        0x494e4f4d
    20d4:       20524f54        ll.w    $r20,$r26,21068(0x524c)
    20d8:       20726f66        ll.w    $r6,$r27,29292(0x726c)
    20dc:       6e6f6f4c        bgeu    $r26,$r12,-102548(0x26f6c) # 0xfffffffffffe9048
    20e0:       63726167        blt     $r11,$r7,-36256(0x37260) # 0xffffffffffff9340
    20e4:       20323368        ll.w    $r8,$r27,12848(0x3230)
    20e8:       6e69202d        bgeu    $r1,$r13,-104160(0x26920) # 0xfffffffffffe8a08
    20ec:       61697469        blt     $r3,$r9,92532(0x16974) # 0x18a60
    20f0:       657a696c        bge     $r11,$r12,96872(0x17a68) # 0x19b58
    20f4:       00002e64        ctz.d   $r4,$r19
    20f8:       8c807f00        0x8c807f00
    20fc:       00807f00        bstrins.d       $r0,$r24,0x0,0x1f
    2100:       1c00fdcc        pcaddu12i       $r12,2030(0x7ee)
    2104:       02bc018c        addi.w  $r12,$r12,-256(0xf00)
    2108:       1c00fdcd        pcaddu12i       $r13,2030(0x7ee)
    210c:       02be11ad        addi.w  $r13,$r13,-124(0xf84)
    2110:       5800118d        beq     $r12,$r13,16(0x10) # 0x2120
    2114:       29800180        st.w    $r0,$r12,0
    2118:       0280118c        addi.w  $r12,$r12,4(0x4)
    211c:       53fff7ff        b       -12(0xffffff4) # 0x2110
    2120:       1c000023        pcaddu12i       $r3,1(0x1)
    2124:       28812063        ld.w    $r3,$r3,72(0x48)
    2128:       00150076        move    $r22,$r3
    212c:       1c00002c        pcaddu12i       $r12,1(0x1)
    2130:       2880b18c        ld.w    $r12,$r12,44(0x2c)
    2134:       1c00002d        pcaddu12i       $r13,1(0x1)
    2138:       2880e1ad        ld.w    $r13,$r13,56(0x38)
    213c:       298001ac        st.w    $r12,$r13,0
    2140:       1c00002d        pcaddu12i       $r13,1(0x1)
    2144:       288081ad        ld.w    $r13,$r13,32(0x20)
    2148:       298001ac        st.w    $r12,$r13,0
    214c:       0380800c        ori     $r12,$r0,0x20
    2150:       02bffd8c        addi.w  $r12,$r12,-1(0xfff)
    2154:       02bff063        addi.w  $r3,$r3,-4(0xffc)
    2158:       29800060        st.w    $r0,$r3,0
    215c:       5ffff580        bne     $r12,$r0,-12(0x3fff4) # 0x2150
    2160:       1c00fdcc        pcaddu12i       $r12,2030(0x7ee)
    2164:       02bc818c        addi.w  $r12,$r12,-224(0xf20)
    2168:       29800183        st.w    $r3,$r12,0
    216c:       00150072        move    $r18,$r3
    2170:       0380800c        ori     $r12,$r0,0x20
    2174:       02bffd8c        addi.w  $r12,$r12,-1(0xfff)
    2178:       02bff063        addi.w  $r3,$r3,-4(0xffc)
    217c:       29800060        st.w    $r0,$r3,0
    2180:       5ffff580        bne     $r12,$r0,-12(0x3fff4) # 0x2174
    2184:       1c00fdcc        pcaddu12i       $r12,2030(0x7ee)
    2188:       02bbf18c        addi.w  $r12,$r12,-260(0xefc)
    218c:       29801183        st.w    $r3,$r12,4(0x4)
    2190:       29802243        st.w    $r3,$r18,8(0x8)
    2194:       1c00fdce        pcaddu12i       $r14,2030(0x7ee)
    2198:       02bbc1ce        addi.w  $r14,$r14,-272(0xef0)
    219c:       288001ce        ld.w    $r14,$r14,0
    21a0:       1c00fdcd        pcaddu12i       $r13,2030(0x7ee)
    21a4:       02bba1ad        addi.w  $r13,$r13,-280(0xee8)
    21a8:       298001ae        st.w    $r14,$r13,0
    21ac:       50000400        b       4(0x4) # 0x21b0
    21b0:       1c000017        pcaddu12i       $r23,0
    21b4:       02bc82f7        addi.w  $r23,$r23,-224(0xf20)
    21b8:       280002e4        ld.b    $r4,$r23,0
    21bc:       028006f7        addi.w  $r23,$r23,1(0x1)
    21c0:       1c00002c        pcaddu12i       $r12,1(0x1)
    21c4:       28bec18c        ld.w    $r12,$r12,-80(0xfb0)
    21c8:       4c000181        jirl    $r1,$r12,0
    21cc:       280002e4        ld.b    $r4,$r23,0
    21d0:       5fffec80        bne     $r4,$r0,-20(0x3ffec) # 0x21bc
    21d4:       1c00002c        pcaddu12i       $r12,1(0x1)
    21d8:       28be418c        ld.w    $r12,$r12,-112(0xf90)
    21dc:       4c000180        jirl    $r0,$r12,0
    21e0:       1c00002c        pcaddu12i       $r12,1(0x1)
    21e4:       28bdc18c        ld.w    $r12,$r12,-144(0xf70)
    21e8:       4c000181        jirl    $r1,$r12,0
    21ec:       00110084        sub.w   $r4,$r4,$r0
    21f0:       0381480c        ori     $r12,$r0,0x52
    21f4:       5800208c        beq     $r4,$r12,32(0x20) # 0x2214
    21f8:       0381100c        ori     $r12,$r0,0x44
    21fc:       58005c8c        beq     $r4,$r12,92(0x5c) # 0x2258
    2200:       0381040c        ori     $r12,$r0,0x41
    2204:       5800ac8c        beq     $r4,$r12,172(0xac) # 0x22b0
    2208:       03811c0c        ori     $r12,$r0,0x47
    220c:       5801008c        beq     $r4,$r12,256(0x100) # 0x230c
    2210:       50024400        b       580(0x244) # 0x2454
    2214:       02bfe063        addi.w  $r3,$r3,-8(0xff8)
    2218:       29800077        st.w    $r23,$r3,0
    221c:       29801078        st.w    $r24,$r3,4(0x4)
    2220:       1c00fdd7        pcaddu12i       $r23,2030(0x7ee)
    2224:       02b782f7        addi.w  $r23,$r23,-544(0xde0)
    2228:       0381f018        ori     $r24,$r0,0x7c
    222c:       280002e4        ld.b    $r4,$r23,0
    2230:       02bfff18        addi.w  $r24,$r24,-1(0xfff)
    2234:       1c00002c        pcaddu12i       $r12,1(0x1)
    2238:       28bcf18c        ld.w    $r12,$r12,-196(0xf3c)
    223c:       4c000181        jirl    $r1,$r12,0
    2240:       028006f7        addi.w  $r23,$r23,1(0x1)
    2244:       5fffeb00        bne     $r24,$r0,-24(0x3ffe8) # 0x222c
    2248:       28800077        ld.w    $r23,$r3,0
    224c:       28801078        ld.w    $r24,$r3,4(0x4)
    2250:       02802063        addi.w  $r3,$r3,8(0x8)
    2254:       50020000        b       512(0x200) # 0x2454
    2258:       02bfe063        addi.w  $r3,$r3,-8(0xff8)
    225c:       29800077        st.w    $r23,$r3,0
    2260:       29801078        st.w    $r24,$r3,4(0x4)
    2264:       1c00002c        pcaddu12i       $r12,1(0x1)
    2268:       28bbe18c        ld.w    $r12,$r12,-264(0xef8)
    226c:       4c000181        jirl    $r1,$r12,0
    2270:       00150097        move    $r23,$r4
    2274:       1c00002c        pcaddu12i       $r12,1(0x1)
    2278:       28bba18c        ld.w    $r12,$r12,-280(0xee8)
    227c:       4c000181        jirl    $r1,$r12,0
    2280:       00150098        move    $r24,$r4
    2284:       280002e4        ld.b    $r4,$r23,0
    2288:       02bfff18        addi.w  $r24,$r24,-1(0xfff)
    228c:       1c00002c        pcaddu12i       $r12,1(0x1)
    2290:       28bb918c        ld.w    $r12,$r12,-284(0xee4)
    2294:       4c000181        jirl    $r1,$r12,0
    2298:       028006f7        addi.w  $r23,$r23,1(0x1)
    229c:       5fffeb00        bne     $r24,$r0,-24(0x3ffe8) # 0x2284
    22a0:       28800077        ld.w    $r23,$r3,0
    22a4:       28801078        ld.w    $r24,$r3,4(0x4)
    22a8:       02802063        addi.w  $r3,$r3,8(0x8)
    22ac:       5001a800        b       424(0x1a8) # 0x2454
    22b0:       02bfe063        addi.w  $r3,$r3,-8(0xff8)
    22b4:       29800077        st.w    $r23,$r3,0
    22b8:       29801078        st.w    $r24,$r3,4(0x4)
    22bc:       1c00002c        pcaddu12i       $r12,1(0x1)
    22c0:       28ba818c        ld.w    $r12,$r12,-352(0xea0)
    22c4:       4c000181        jirl    $r1,$r12,0
    22c8:       00150097        move    $r23,$r4
    22cc:       1c00002c        pcaddu12i       $r12,1(0x1)
    22d0:       28ba418c        ld.w    $r12,$r12,-368(0xe90)
    22d4:       4c000181        jirl    $r1,$r12,0
    22d8:       00150098        move    $r24,$r4
    22dc:       00448b18        srli.w  $r24,$r24,0x2
    22e0:       1c00002c        pcaddu12i       $r12,1(0x1)
    22e4:       28b9f18c        ld.w    $r12,$r12,-388(0xe7c)
    22e8:       4c000181        jirl    $r1,$r12,0
    22ec:       298002e4        st.w    $r4,$r23,0
    22f0:       02bfff18        addi.w  $r24,$r24,-1(0xfff)
    22f4:       028012f7        addi.w  $r23,$r23,4(0x4)
    22f8:       5fffeb00        bne     $r24,$r0,-24(0x3ffe8) # 0x22e0
    22fc:       28800077        ld.w    $r23,$r3,0
    2300:       28801078        ld.w    $r24,$r3,4(0x4)
    2304:       02802063        addi.w  $r3,$r3,8(0x8)
    2308:       50014c00        b       332(0x14c) # 0x2454
    230c:       1c00002c        pcaddu12i       $r12,1(0x1)
    2310:       28b9418c        ld.w    $r12,$r12,-432(0xe50)
    2314:       4c000181        jirl    $r1,$r12,0
    2318:       00150095        move    $r21,$r4
    231c:       03801804        ori     $r4,$r0,0x6
    2320:       1c00002c        pcaddu12i       $r12,1(0x1)
    2324:       28b9418c        ld.w    $r12,$r12,-432(0xe50)
    2328:       4c000181        jirl    $r1,$r12,0
    232c:       1c00fdc1        pcaddu12i       $r1,2030(0x7ee)
    2330:       02b35021        addi.w  $r1,$r1,-812(0xcd4)
    2334:       2981f023        st.w    $r3,$r1,124(0x7c)
    2338:       28801022        ld.w    $r2,$r1,4(0x4)
    233c:       28802023        ld.w    $r3,$r1,8(0x8)
    2340:       28803024        ld.w    $r4,$r1,12(0xc)
    2344:       28804025        ld.w    $r5,$r1,16(0x10)
    2348:       28805026        ld.w    $r6,$r1,20(0x14)
    234c:       28806027        ld.w    $r7,$r1,24(0x18)
    2350:       28807028        ld.w    $r8,$r1,28(0x1c)
    2354:       28808029        ld.w    $r9,$r1,32(0x20)
    2358:       2880902a        ld.w    $r10,$r1,36(0x24)
    235c:       2880a02b        ld.w    $r11,$r1,40(0x28)
    2360:       2880b02c        ld.w    $r12,$r1,44(0x2c)
    2364:       2880c02d        ld.w    $r13,$r1,48(0x30)
    2368:       2880d02e        ld.w    $r14,$r1,52(0x34)
    236c:       2880e02f        ld.w    $r15,$r1,56(0x38)
    2370:       2880f030        ld.w    $r16,$r1,60(0x3c)
    2374:       28810031        ld.w    $r17,$r1,64(0x40)
    2378:       28811032        ld.w    $r18,$r1,68(0x44)
    237c:       28812033        ld.w    $r19,$r1,72(0x48)
    2380:       28813034        ld.w    $r20,$r1,76(0x4c)
    2384:       28815036        ld.w    $r22,$r1,84(0x54)
    2388:       28816037        ld.w    $r23,$r1,88(0x58)
    238c:       28817038        ld.w    $r24,$r1,92(0x5c)
    2390:       28818039        ld.w    $r25,$r1,96(0x60)
    2394:       2881903a        ld.w    $r26,$r1,100(0x64)
    2398:       2881a03b        ld.w    $r27,$r1,104(0x68)
    239c:       2881b03c        ld.w    $r28,$r1,108(0x6c)
    23a0:       2881c03d        ld.w    $r29,$r1,112(0x70)
    23a4:       2881d03e        ld.w    $r30,$r1,116(0x74)
    23a8:       2881e03f        ld.w    $r31,$r1,120(0x78)
    23ac:       4c0002a1        jirl    $r1,$r21,0
    23b0:       1c00fdc1        pcaddu12i       $r1,2030(0x7ee)
    23b4:       02b14021        addi.w  $r1,$r1,-944(0xc50)
    23b8:       29801022        st.w    $r2,$r1,4(0x4)
    23bc:       29802023        st.w    $r3,$r1,8(0x8)
    23c0:       29803024        st.w    $r4,$r1,12(0xc)
    23c4:       29804025        st.w    $r5,$r1,16(0x10)
    23c8:       29805026        st.w    $r6,$r1,20(0x14)
    23cc:       29806027        st.w    $r7,$r1,24(0x18)
    23d0:       29807028        st.w    $r8,$r1,28(0x1c)
    23d4:       29808029        st.w    $r9,$r1,32(0x20)
    23d8:       2980902a        st.w    $r10,$r1,36(0x24)
    23dc:       2980a02b        st.w    $r11,$r1,40(0x28)
    23e0:       2980b02c        st.w    $r12,$r1,44(0x2c)
    23e4:       2980c02d        st.w    $r13,$r1,48(0x30)
    23e8:       2980d02e        st.w    $r14,$r1,52(0x34)
    23ec:       2980e02f        st.w    $r15,$r1,56(0x38)
    23f0:       2980f030        st.w    $r16,$r1,60(0x3c)
    23f4:       29810031        st.w    $r17,$r1,64(0x40)
    23f8:       29811032        st.w    $r18,$r1,68(0x44)
    23fc:       29812033        st.w    $r19,$r1,72(0x48)
    2400:       29813034        st.w    $r20,$r1,76(0x4c)
    2404:       29814035        st.w    $r21,$r1,80(0x50)
    2408:       29815036        st.w    $r22,$r1,84(0x54)
    240c:       29816037        st.w    $r23,$r1,88(0x58)
    2410:       29817038        st.w    $r24,$r1,92(0x5c)
    2414:       29818039        st.w    $r25,$r1,96(0x60)
    2418:       2981903a        st.w    $r26,$r1,100(0x64)
    241c:       2981a03b        st.w    $r27,$r1,104(0x68)
    2420:       2981b03c        st.w    $r28,$r1,108(0x6c)
    2424:       2981c03d        st.w    $r29,$r1,112(0x70)
    2428:       2981d03e        st.w    $r30,$r1,116(0x74)
    242c:       2981e03f        st.w    $r31,$r1,120(0x78)
    2430:       1c000004        pcaddu12i       $r4,0
    2434:       02be0084        addi.w  $r4,$r4,-128(0xf80)
    2438:       29800024        st.w    $r4,$r1,0
    243c:       2881f023        ld.w    $r3,$r1,124(0x7c)
    2440:       03801c04        ori     $r4,$r0,0x7
    2444:       1c00002c        pcaddu12i       $r12,1(0x1)
    2448:       28b4b18c        ld.w    $r12,$r12,-724(0xd2c)
    244c:       4c000181        jirl    $r1,$r12,0
    2450:       50000400        b       4(0x4) # 0x2454
    2454:       53fd8fff        b       -628(0xffffd8c) # 0x21e0
        ...
    3000:       02800484        addi.w  $r4,$r4,1(0x1)
    3004:       4c000020        jirl    $r0,$r1,0
    3008:       15002004        lu12i.w $r4,-524032(0x80100)
    300c:       15008005        lu12i.w $r5,-523264(0x80400)
    3010:       14006006        lu12i.w $r6,768(0x300)
    3014:       00101886        add.w   $r6,$r4,$r6
    3018:       2880008c        ld.w    $r12,$r4,0
    301c:       298000ac        st.w    $r12,$r5,0
    3020:       02801084        addi.w  $r4,$r4,4(0x4)
    3024:       028010a5        addi.w  $r5,$r5,4(0x4)
    3028:       5ffff086        bne     $r4,$r6,-16(0x3fff0) # 0x3018
    302c:       4c000020        jirl    $r0,$r1,0
    3030:       15008004        lu12i.w $r4,-523264(0x80400)
    3034:       15008205        lu12i.w $r5,-523248(0x80410)
    3038:       15008406        lu12i.w $r6,-523232(0x80420)
    303c:       03818007        ori     $r7,$r0,0x60
    3040:       00150014        move    $r20,$r0
    3044:       58006e87        beq     $r20,$r7,108(0x6c) # 0x30b0
    3048:       00408a8c        slli.w  $r12,$r20,0x2
    304c:       0040a68e        slli.w  $r14,$r20,0x9
    3050:       0010308c        add.w   $r12,$r4,$r12
    3054:       001038ae        add.w   $r14,$r5,$r14
    3058:       0015000d        move    $r13,$r0
    305c:       58004da7        beq     $r13,$r7,76(0x4c) # 0x30a8
    3060:       28800193        ld.w    $r19,$r12,0
    3064:       0040a5a8        slli.w  $r8,$r13,0x9
    3068:       001020c8        add.w   $r8,$r6,$r8
    306c:       001501d0        move    $r16,$r14
    3070:       0015000f        move    $r15,$r0
    3074:       580029e7        beq     $r15,$r7,40(0x28) # 0x309c
    3078:       028005ef        addi.w  $r15,$r15,1(0x1)
    307c:       28800211        ld.w    $r17,$r16,0
    3080:       28800112        ld.w    $r18,$r8,0
    3084:       001c4671        mul.w   $r17,$r19,$r17
    3088:       02801108        addi.w  $r8,$r8,4(0x4)
    308c:       02801210        addi.w  $r16,$r16,4(0x4)
    3090:       00104651        add.w   $r17,$r18,$r17
    3094:       29bff111        st.w    $r17,$r8,-4(0xffc)
    3098:       53ffdfff        b       -36(0xfffffdc) # 0x3074
    309c:       028005ad        addi.w  $r13,$r13,1(0x1)
    30a0:       0288018c        addi.w  $r12,$r12,512(0x200)
    30a4:       53ffbbff        b       -72(0xfffffb8) # 0x305c
    30a8:       02800694        addi.w  $r20,$r20,1(0x1)
    30ac:       53ff9bff        b       -104(0xfffff98) # 0x3044
    30b0:       4c000020        jirl    $r0,$r1,0
    30b4:       15008004        lu12i.w $r4,-523264(0x80400)
    30b8:       15bd5b65        lu12i.w $r5,-136485(0xdeadb)
    30bc:       03bbbca5        ori     $r5,$r5,0xeef
    30c0:       15f59d66        lu12i.w $r6,-21269(0xfaceb)
    30c4:       038030c6        ori     $r6,$r6,0xc
    30c8:       14002007        lu12i.w $r7,256(0x100)
    30cc:       00151010        or      $r16,$r0,$r4
    30d0:       0015000f        move    $r15,$r0
    30d4:       1400100c        lu12i.w $r12,128(0x80)
    30d8:       2980020f        st.w    $r15,$r16,0
    30dc:       028005ef        addi.w  $r15,$r15,1(0x1)
    30e0:       02801210        addi.w  $r16,$r16,4(0x4)
    30e4:       5ffff5ec        bne     $r15,$r12,-12(0x3fff4) # 0x30d8
    30e8:       0015000d        move    $r13,$r0
    30ec:       14000fee        lu12i.w $r14,127(0x7f)
    30f0:       03bffdce        ori     $r14,$r14,0xfff
    30f4:       0014b8ac        and     $r12,$r5,$r14
    30f8:       0040898c        slli.w  $r12,$r12,0x2
    30fc:       0010308c        add.w   $r12,$r4,$r12
    3100:       2880018f        ld.w    $r15,$r12,0
    3104:       004484b0        srli.w  $r16,$r5,0x1
    3108:       004085ef        slli.w  $r15,$r15,0x1
    310c:       0015c1ef        xor     $r15,$r15,$r16
    3110:       0014b9f0        and     $r16,$r15,$r14
    3114:       001599e6        xor     $r6,$r15,$r6
    3118:       00408a10        slli.w  $r16,$r16,0x2
    311c:       29800186        st.w    $r6,$r12,0
    3120:       00104090        add.w   $r16,$r4,$r16
    3124:       2880020c        ld.w    $r12,$r16,0
    3128:       00153c06        or      $r6,$r0,$r15
    312c:       001c31ef        mul.w   $r15,$r15,$r12
    3130:       028005ad        addi.w  $r13,$r13,1(0x1)
    3134:       001015e5        add.w   $r5,$r15,$r5
    3138:       29800205        st.w    $r5,$r16,0
    313c:       00159585        xor     $r5,$r12,$r5
    3140:       5fffb4ed        bne     $r7,$r13,-76(0x3ffb4) # 0x30f4
    3144:       4c000020        jirl    $r0,$r1,0
    3148:       00000000        0x00000000
    314c:       bfd003f8        0xbfd003f8
    3150:       80002028        0x80002028
    3154:       bfd003fc        0xbfd003fc
    3158:       807f0000        0x807f0000
    315c:       80002050        0x80002050
    3160:       807f0054        0x807f0054
    3164:       800021e0        0x800021e0
    3168:       80800000        0x80800000
    316c:       807f0008        0x807f0008
    3170:       80002000        0x80002000
    3174:       80002100        0x80002100
