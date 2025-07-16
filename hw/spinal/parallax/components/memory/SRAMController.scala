package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.fsm._
import spinal.lib.io.TriState
import spinal.lib.bus.amba4.axi.Axi4Aw
import parallax.utilities.ParallaxLogger
import parallax.utilities.Formattable

// ExtSRAM 的配置参数
case class SRAMConfig(
    addressWidth: Int,
    dataWidth: Int,
    virtualBaseAddress: BigInt = 0x0,
    sizeBytes: BigInt,
    readWaitCycles: Int = 0,
    writeWaitCycles: Int = 0,
    writeDeassertCycles: Int = 1,
    sramByteEnableIsActiveLow: Boolean = true,
    // --- NEW ---: 添加字地址模式配置
    useWordAddressing: Boolean = false,
    enableLog: Boolean = true
) {
  require(isPow2(dataWidth / 8), "dataWidth must be a power of 2 bytes")
  require(sizeBytes > 0 && sizeBytes % (dataWidth / 8) == 0, "sramSize must be a multiple of data bus width")

  val bytesPerWord: Int = dataWidth / 8
  val internalWordCount: BigInt = sizeBytes / bytesPerWord

  // --- MODIFIED ---: 根据寻址模式调整地址空间检查
  if (useWordAddressing) {
    require(internalWordCount <= (BigInt(1) << addressWidth), s"sizeBytes (${sizeBytes}) requires more word addresses than available with addressWidth=${addressWidth}")
  } else {
    require(sizeBytes <= (BigInt(1) << addressWidth), s"sizeBytes (${sizeBytes}) exceeds byte-addressable space with addressWidth=${addressWidth}")
  }

  // --- NEW ---: 计算地址转换所需的位移量
  // 如果是字地址，需要右移 log2(bytesPerWord) 位。例如 32bit dataWidth (4 bytes/word) -> 右移 2 位
  val addressShift: Int = if (useWordAddressing) log2Up(bytesPerWord) else 0

  val internalWordMaxAddr: BigInt = internalWordCount - 1
  val internalMaxByteAddr = sizeBytes - 1
  val internalWordAddrWidth = log2Up(internalWordCount)
  val byteAddrWidth = log2Up(sizeBytes)

}

// ExtSRAM 的外部引脚定义 (无变化)
case class SRAMIO(c: SRAMConfig) extends Bundle with IMasterSlave with Formattable {
  val data = (TriState(Bits(c.dataWidth bits)))
  val addr = UInt(c.addressWidth bits)
  val be_n = Bits(c.dataWidth / 8 bits) // 保持 _n 后缀表示低有效
  val ce_n = Bool()
  val oe_n = Bool()
  val we_n = Bool()

  override def asMaster(): Unit = {
    master(data)
    out(addr, be_n, ce_n, oe_n, we_n)
  }

  private def formatTriState(tri: TriState[Bits]): Seq[Any] = {
    Seq(
      L"TriState(",
      L"writeEnable=${tri.writeEnable}, ",
      L"read=${tri.read}, ",
      L"write=${tri.write})"
    )
  }

  override def format: Seq[Any] = {
    Seq(
      L"SRAMIO(",
      L"ce_n=${ce_n}, ",
      L"oe_n=${oe_n}, ",
      L"we_n=${we_n}, ",
      L"addr=${addr}, ",
      L"data=${formatTriState(data)}, ",
      L"be_n=${be_n})"
    )
  }
}

object SRAMController {
  private var _nextInstanceId = 0
  def nextInstanceId: Int = {
    val id = _nextInstanceId
    _nextInstanceId += 1
    id
  }
}

// SRAMController 组件
class SRAMController(val axiConfig: Axi4Config, val config: SRAMConfig) extends Component {

  private case class ValidationInput(
      addr: UInt,
      len: UInt,
      size: UInt,
      burst: Bits
  ) extends Bundle {
    // 我们可以根据需要添加更多字段
  }

  // --- NEW: 将所有重复的验证逻辑提取到一个私有函数中 ---
  private def performValidation(cmd: ValidationInput): (Bool, UInt) = {
    // 这个函数包含所有之前重复的计算逻辑
    // 它返回一个元组: (is_error: Bool, sram_addr: UInt)

    // 1. 扩展操作数
    val virtualBaseAddressBitLength = if (config.virtualBaseAddress == 0) 1 else log2Up(config.virtualBaseAddress) + 1
    val maxAddressBitLength = Math.max(axiConfig.addressWidth, virtualBaseAddressBitLength)
    val extendedWidth = maxAddressBitLength + 1
    val addr_extended = cmd.addr.resize(extendedWidth)
    val vbase_extended = U(config.virtualBaseAddress, extendedWidth bits)

    // 2. 执行减法
    val byte_offset_addr = addr_extended - vbase_extended

    // 3. 计算突发指标
    val bytesPerBeat = if (axiConfig.useSize) U(1) << cmd.size else U(config.bytesPerWord)
    val burst_len_extended = (cmd.len + 1).resize(extendedWidth)
    val bytesPerBeat_extended = bytesPerBeat.resize(extendedWidth)
    val end_byte_offset_addr = byte_offset_addr + burst_len_extended * bytesPerBeat_extended - bytesPerBeat_extended

    // 4. 执行对齐和兼容性检查
    val addr_aligned = (cmd.addr & (bytesPerBeat - 1).resize(cmd.addr.getWidth)) === 0
    val word_aligned_if_needed = if (config.useWordAddressing) {
        (cmd.addr & U(config.bytesPerWord - 1, cmd.addr.getWidth bits)) === 0
    } else { True }
    val size_compatible_if_needed = if (config.useWordAddressing && axiConfig.useSize) {
        cmd.size === U(log2Up(config.bytesPerWord))
    } else { True }
    
    // 5. 执行最终的边界检查
    val is_negative = byte_offset_addr(config.addressWidth)
    val is_out_of_bounds = end_byte_offset_addr >= U(config.sizeBytes, extendedWidth bits)

    // 6. 计算SRAM地址
    val sram_addr_candidate = (byte_offset_addr(config.addressWidth-1 downto 0) >> config.addressShift).resized

    // 7. 组合所有错误条件
    val is_error = (cmd.burst =/= Axi4.burst.INCR) || !addr_aligned || !word_aligned_if_needed || !size_compatible_if_needed || is_negative || is_out_of_bounds
    
    // 8. 返回结果
    (is_error, sram_addr_candidate)
  }


  val instanceId = SRAMController.nextInstanceId
  require(
    axiConfig.dataWidth == config.dataWidth,
    s"AXI and SRAM data width must match axiConfig.dataWidth = ${axiConfig.dataWidth} ExtSRAMConfig.dataWidth = ${config.dataWidth}"
  )
  // --- NEW ---: 对字地址模式的额外要求
  if (config.useWordAddressing) {
    require(axiConfig.useSize, "AXI useSize must be enabled for word addressing mode to ensure proper burst increments.")
  }

  ParallaxLogger.debug(s"Creating SRAMController with axiConfig=${axiConfig}, config=${config}")
  val hasReadWaitCycles = config.readWaitCycles > 0
  val hasWriteWaitCycles = config.writeWaitCycles > 0
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig))
    val ram = master(SRAMIO(config))
  }

  // --- AXI4 Slave 接口就绪信号 ---
  // FIXED: 需要为所有状态提供默认值，避免锁存器
  // FSM中的状态将会覆盖这些默认值
  io.axi.aw.ready := False
  io.axi.ar.ready := False
  io.axi.w.ready := False
  io.axi.b.valid := False
  io.axi.r.valid := False

  // --- AXI4 Slave 接口默认 payload ---
  // FIXED: 为了避免锁存器，需要提供默认值，但这些值只在valid=false时生效
  // 在FSM中，当valid=true时，这些值会被正确的事务值覆盖
  io.axi.b.payload.id := 0
  io.axi.b.payload.resp := Axi4.resp.OKAY
  io.axi.r.payload.id := 0
  io.axi.r.payload.data := B(0, axiConfig.dataWidth bits)
  io.axi.r.payload.resp := Axi4.resp.OKAY
  io.axi.r.payload.last := False

  // --- SRAM 控制信号初始化 (将被FSM控制的寄存器) ---
  // --- MODIFIED: 将这些变量提升为组件的Reg，作为SRAM引脚的直接驱动 ---
  val sram_be_n_inactive_value =
    if (config.sramByteEnableIsActiveLow)
      B((1 << (config.dataWidth / 8)) - 1, config.dataWidth / 8 bits)
    else B(0, config.dataWidth / 8 bits)
      
  val sram_addr_out_reg        = Reg(UInt(config.addressWidth bits)) init(0)
  val sram_data_out_reg        = Reg(Bits(config.dataWidth bits)) init(0)
  val sram_be_n_out_reg        = Reg(Bits(config.dataWidth / 8 bits)) init(sram_be_n_inactive_value)
  val sram_ce_n_out_reg        = Reg(Bool()) init(True)
  val sram_oe_n_out_reg        = Reg(Bool()) init(True)
  val sram_we_n_out_reg        = Reg(Bool()) init(True)
  val sram_data_writeEnable_out_reg = Reg(Bool()) init(False) // 用于控制TriState数据总线方向

  // --- 连接这些寄存器到SRAM物理引脚 ---
  io.ram.addr := sram_addr_out_reg
  io.ram.ce_n := sram_ce_n_out_reg
  io.ram.oe_n := sram_oe_n_out_reg
  io.ram.we_n := sram_we_n_out_reg
  io.ram.be_n := sram_be_n_out_reg
  io.ram.data.write := sram_data_out_reg
  io.ram.data.writeEnable := sram_data_writeEnable_out_reg

  report(L"#$instanceId [DEBUG] io.ram: ${io.ram.format}")

  // --- 状态机定义 ---
  val fsm = new StateMachine {
    val ar_cmd_reg = Reg(cloneOf(io.axi.ar.payload))
    val aw_cmd_reg = Reg(cloneOf(io.axi.aw.payload))
    val burst_count_remaining = Reg(UInt(axiConfig.lenWidth + 1 bits))
    val current_sram_addr = Reg(UInt(config.addressWidth bits)) // 用于记录本次burst的当前SRAM逻辑地址
    val read_data_buffer = Reg(Bits(config.dataWidth bits))
    val read_wait_counter = hasReadWaitCycles generate Reg(UInt(log2Up(config.readWaitCycles + 1) bits))
    val write_wait_counter = hasWriteWaitCycles generate Reg(UInt(log2Up(config.writeWaitCycles + 1) bits))

    // --- NEW: 为新的 DEASSERT 状态添加计数器 ---
    // 仅当周期数大于1时才需要生成物理计数器
    val hasWriteDeassertCycles = config.writeDeassertCycles > 1
    val write_deassert_counter = hasWriteDeassertCycles generate Reg(UInt(log2Up(config.writeDeassertCycles) bits))

    val transaction_error_occurred = Reg(Bool()) init (False) addAttribute("MARK_DEBUG","TRUE")
    val read_priority = Reg(Bool()) init (False) // 用于arbitration

    val next_sram_addr_prefetch = Reg(UInt(config.addressWidth bits))
    val addr_prefetch_valid = Reg(Bool()) init (False)

    val is_write_transaction = Reg(Bool())

    val IDLE: State = new State with EntryPoint {
      onEntry {
        // --- MODIFIED: 初始化SRAM输出寄存器到安全状态 ---
        sram_ce_n_out_reg := True
        sram_oe_n_out_reg := True
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False // 确保数据总线处于高阻
        sram_be_n_out_reg := sram_be_n_inactive_value // 默认字节使能无效
        sram_addr_out_reg := 0 // 默认地址为0
      }
      whenIsActive {
        // FIXED: 在非-IDLE状态中，确保不接受新的命令通道请求
        io.axi.aw.ready := False
        io.axi.ar.ready := False
        
        // --- MODIFIED: 状态机活动期间，如果未被特定状态覆盖，保持默认行为 ---
        // (这些通常在onEntry设置一次就够了，但为了安全和调试，也可以在这里强制)
        // sram_ce_n_out_reg := True // 已经在onEntry中设置
        // sram_oe_n_out_reg := True // 已经在onEntry中设置
        // sram_we_n_out_reg := True // 已经在onEntry中设置
        // sram_data_writeEnable_out_reg := False // 已经在onEntry中设置
        // sram_be_n_out_reg := sram_be_n_inactive_value // 已经在onEntry中设置
        // sram_addr_out_reg := 0 // 已经在onEntry中设置

        transaction_error_occurred := False
        addr_prefetch_valid := False

        // FIXED: 修复循环依赖问题，使用更稳定的仲裁逻辑
        // 原始的实现有循环依赖问题：
        // io.axi.aw.ready := !read_priority || !io.axi.ar.valid
        // io.axi.ar.ready := read_priority || !io.axi.aw.valid
        // 当同时有AR和AW请求时，两个都可能不会ready
        
        // 新的实现：保证至少有一个通道可以ready
        val awHasRequest = io.axi.aw.valid
        val arHasRequest = io.axi.ar.valid
        
        when(awHasRequest && arHasRequest) {
          // 当两个都有请求时，根据优先级选择一个
          io.axi.aw.ready := !read_priority
          io.axi.ar.ready := read_priority
        } otherwise {
          // 当只有一个有请求时，直接接受
          io.axi.aw.ready := awHasRequest
          io.axi.ar.ready := arHasRequest
        }
        
        // 在IDLE状态中，W通道不应该ready
        io.axi.w.ready := False

        // --- Write Channel Transaction Handling ---
        when(io.axi.aw.fire) {
          // Log the incoming request
          val sizeInfo = if (axiConfig.useSize) L", Size=${io.axi.aw.size}" else L""
          if (config.enableLog) {
            report(
              L"$instanceId AW Fire. Addr=0x${io.axi.aw.addr}, ID=${io.axi.aw.id}, Len=${io.axi.aw.len}, Burst=${io.axi.aw.burst}${sizeInfo}"
            )
          }

          // Store transaction details
          aw_cmd_reg := io.axi.aw.payload
          burst_count_remaining := (io.axi.aw.len + 1).resize(burst_count_remaining.getWidth)
          read_priority := !read_priority

                // 不再直接计算和跳转，而是进入验证状态
            is_write_transaction:=True
          goto(VALIDATE)
        }

        // --- Read Channel Transaction Handling ---
        when(io.axi.ar.fire) {
          // Log the incoming request
          val sizeInfo = if (axiConfig.useSize) L", Size=${io.axi.ar.size}" else L""
          if (config.enableLog) {
            report(
              L"$instanceId AR Fire. Addr=0x${io.axi.ar.addr}, ID=${io.axi.ar.id}, Len=${io.axi.ar.len}, Burst=${io.axi.ar.burst}${sizeInfo}"
            )
          }
          
          // Store transaction details
          ar_cmd_reg := io.axi.ar.payload
          burst_count_remaining := (io.axi.ar.len + 1).resize(burst_count_remaining.getWidth)
          read_priority := !read_priority
            is_write_transaction:=False
          goto(VALIDATE)
        }
      }
    }

        val VALIDATE: State = new State {
      whenIsActive {
        // 1. 使用 Mux 根据事务类型直接选择命令源
        // 这种方式比创建一个空的Bundle再填充它要高效和简洁得多
        val cmd_addr  = Mux(is_write_transaction, aw_cmd_reg.addr,  ar_cmd_reg.addr)
        val cmd_len   = Mux(is_write_transaction, aw_cmd_reg.len,   ar_cmd_reg.len)
        val cmd_size  = Mux(is_write_transaction, aw_cmd_reg.size,  ar_cmd_reg.size)
        val cmd_burst = Mux(is_write_transaction, aw_cmd_reg.burst, ar_cmd_reg.burst)

        // 2. 将选择出的信号打包成 ValidationInput 传递给函数
        // 这里只是为了匹配函数签名，在硬件中不会创建真正的复杂结构
        val validationCmd = ValidationInput(
          addr = cmd_addr,
          len = cmd_len,
          size = cmd_size,
          burst = cmd_burst
        )
        
        // 3. 调用通用验证函数
        val (is_error, sram_addr) = performValidation(validationCmd)

        // 4. 根据验证结果和事务类型，决定下一个状态 (这部分逻辑不变)
        when(is_error) {
          transaction_error_occurred := True
          when(is_write_transaction) {
            goto(WRITE_DATA_ERROR_CONSUME)
          } otherwise {
            goto(READ_RESPONSE_ERROR)
          }
        } otherwise {
          transaction_error_occurred := False
          current_sram_addr := sram_addr
          when(is_write_transaction) {
            goto(WRITE_DATA_FETCH)
          } otherwise {
            hasReadWaitCycles generate { read_wait_counter := 0 }
            goto(READ_SETUP)
          }
        }
      }
    }



    // --- MODIFIED: 新增写数据获取状态 ---
    val WRITE_DATA_FETCH: State = new State {
        onEntry {
            // --- MODIFIED: 确保SRAM输出在等待AXI W数据时是高阻/非活动 ---
            sram_ce_n_out_reg := True
            sram_oe_n_out_reg := True
            sram_we_n_out_reg := True
            sram_data_writeEnable_out_reg := False
            sram_be_n_out_reg := sram_be_n_inactive_value
        }
        whenIsActive {
            // FIXED: 在非-IDLE状态中，确保不接受新的命令通道请求
            io.axi.aw.ready := False
            io.axi.ar.ready := False
            io.axi.w.ready := True // 准备好接收W通道数据
            
            if (config.enableLog)
                report(
                    L"$instanceId WRITE_DATA_FETCH. SRAM_Target_Addr=0x${current_sram_addr}, BurstCountRem=${burst_count_remaining}"
                )

            when(io.axi.w.fire) {
                if (config.enableLog)
                    report(
                        L"$instanceId W Fire. Data=0x${io.axi.w.data}, Strb=0x${io.axi.w.strb}, Last=${io.axi.w.last}"
                    )

                // --- MODIFIED: 锁存要写入SRAM的数据和控制信号 ---
                sram_addr_out_reg := current_sram_addr
                sram_data_out_reg := io.axi.w.data
                if (config.sramByteEnableIsActiveLow) {
                    sram_be_n_out_reg := ~io.axi.w.strb
                } else {
                    sram_be_n_out_reg := io.axi.w.strb
                }
                
                // --- MODIFIED: 进入写执行状态 ---
                goto(WRITE_EXECUTE)
            }
        }
    }

    // --- MODIFIED: 修改写执行状态 ---
    val WRITE_EXECUTE: State = new State {
        onEntry {
            // ... (onEntry 逻辑保持不变)
            sram_ce_n_out_reg := False
            sram_oe_n_out_reg := True
            sram_we_n_out_reg := False
            sram_data_writeEnable_out_reg := True 
            
            hasWriteWaitCycles generate { write_wait_counter := 0 }
        }
        whenIsActive {
            // ... (whenIsActive 的大部分逻辑保持不变)
            sram_ce_n_out_reg := False
            sram_oe_n_out_reg := True
            sram_we_n_out_reg := False
            sram_data_writeEnable_out_reg := True

            val waitCond = if (hasWriteWaitCycles) write_wait_counter === config.writeWaitCycles else True
            when(waitCond) {
                // 写周期完成
                burst_count_remaining := burst_count_remaining - 1
                
                // --- MODIFIED: 跳转到新的专用 WRITE_DEASSERT 状态 ---
                goto(WRITE_DEASSERT) 
            } otherwise {
                hasWriteWaitCycles generate { write_wait_counter := write_wait_counter + 1 }
            }
        }
        // --- REMOVED: 移除 onExit 逻辑 ---
        // 数据总线的驱动将在 WRITE_FINALIZE 状态中被禁用，以满足时序要求
    }

    // --- NEW: 实现专用的 DEASSERT 状态 ---
    // 此状态根据需求，仅将 WE_n 拉高，并持续 writeDeassertCycles 个周期
    val WRITE_DEASSERT: State = new State {
        onEntry {
            // 按照硬件要求：只拉高 WE_n，保持其他信号不变
            sram_we_n_out_reg := True   // <-- 关键修改：仅断言 WE_n
            sram_ce_n_out_reg := False  // 保持片选有效
            sram_oe_n_out_reg := True   // 输出使能依然无效
            sram_data_writeEnable_out_reg := True // 保持数据驱动，满足 tHD (Hold Time)
            // sram_addr_out_reg 和 sram_be_n_out_reg 从上一状态保持不变

            // 初始化断言周期计数器
            hasWriteDeassertCycles generate { write_deassert_counter := 0 }
        }
        whenIsActive {
            // 在此状态期间保持信号稳定
            sram_we_n_out_reg := True
            sram_ce_n_out_reg := False
            
            // 检查是否已达到指定的断言周期
            val waitCond = if (hasWriteDeassertCycles) write_deassert_counter === (config.writeDeassertCycles - 1) else True
            when(waitCond) {
                // 断言周期结束，进入最终收尾状态
                goto(WRITE_FINALIZE)
            } otherwise {
                hasWriteDeassertCycles generate { write_deassert_counter := write_deassert_counter + 1 }
            }
        }
    }

    // --- NEW (RENAMED): 写操作收尾状态, 原来的 WRITE_DEASSERT ---
    val WRITE_FINALIZE: State = new State {
        onEntry {
            // 断言所有剩余的控制信号，并将数据总线设为高阻态
            sram_ce_n_out_reg := True
            sram_we_n_out_reg := True // 保持高电平
            sram_oe_n_out_reg := True
            sram_be_n_out_reg := sram_be_n_inactive_value
            sram_data_writeEnable_out_reg := False // <-- 关键修改：在此处禁用数据总线驱动
        }
        whenIsActive {
            io.axi.aw.ready := False
            io.axi.ar.ready := False
            io.axi.w.ready := False

            // 检查 burst 是否结束
            when(burst_count_remaining === 0) {
                goto(WRITE_RESPONSE) // 突发传输完成，发送写响应
            } otherwise {
                // 突发传输未完成，计算下一个地址并获取下一个数据
                val bytesIncrement = if (axiConfig.useSize) U(1) << aw_cmd_reg.size else U(config.bytesPerWord)
                val sramAddrIncrement = if (config.useWordAddressing) (bytesIncrement / config.bytesPerWord).resized else bytesIncrement.resized
                current_sram_addr := current_sram_addr + sramAddrIncrement
                goto(WRITE_DATA_FETCH)
            }
        }
    }


    val WRITE_DATA_ERROR_CONSUME: State = new State {
      onEntry {
        // --- MODIFIED: 确保错误状态下SRAM控制信号安全 ---
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
        sram_oe_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
      whenIsActive {
        // FIXED: 在非-IDLE状态中，确保不接受新的命令通道请求
        io.axi.aw.ready := False
        io.axi.ar.ready := False
        io.axi.w.ready := True
        
        if (config.enableLog) report(L"$instanceId WRITE_DATA_ERROR_CONSUME. BurstCountRem=${burst_count_remaining}")
        
        when(io.axi.w.fire) {
          burst_count_remaining := burst_count_remaining - 1
          when(burst_count_remaining === 1) { // 应该改为 === 0，因为在外面已经-1了
            goto(WRITE_RESPONSE)
          }
        }
      }
    }

    val WRITE_RESPONSE: State = new State {
      onEntry {
        // sram_perform_write := False // MODIFIED: 此信号已废弃，其逻辑已融入FSM状态转换
        // --- MODIFIED: 确保SRAM控制信号在响应AXI时保持非活动状态 ---
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
        sram_oe_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
      whenIsActive {
        val resp_status = transaction_error_occurred ? Axi4.resp.SLVERR | Axi4.resp.OKAY
        if (config.enableLog)
          report(L"$instanceId WRITE_RESPONSE. ID=${aw_cmd_reg.id}, Resp=${resp_status}, Addr=${aw_cmd_reg.addr}, Len=${aw_cmd_reg.len}, Size=${aw_cmd_reg.size}, Burst=${aw_cmd_reg.burst}, Lock=${aw_cmd_reg.lock}, Cache=${aw_cmd_reg.cache}, Prot=${aw_cmd_reg.prot}, Qos=${aw_cmd_reg.qos}, Region=${aw_cmd_reg.region}")
        
        io.axi.b.valid := True
        io.axi.b.payload.id := aw_cmd_reg.id
        io.axi.b.payload.resp := resp_status
        when(io.axi.b.ready) {
          report(L"$instanceId B Ready. ID=${aw_cmd_reg.id}, Resp=${resp_status}")
          goto(IDLE)
        }
      }
    }

    val READ_SETUP: State = new State {
      onEntry { // MODIFIED: 将SRAM信号设置移动到onEntry，确保它们在状态转换后立即生效并稳定
        sram_ce_n_out_reg := False
        sram_oe_n_out_reg := False
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False // 读操作不能驱动数据
        sram_addr_out_reg := current_sram_addr
        sram_be_n_out_reg := B(0, config.dataWidth / 8 bits) // 读操作通常全字节使能
      }
      whenIsActive {
        if (config.enableLog)
          report(
            L"$instanceId READ_SETUP. SRAM Addr=0x${current_sram_addr}, BurstCountRem=${burst_count_remaining}"
          )
        addr_prefetch_valid := False
        
        // --- MODIFIED: 保持SRAM控制信号，避免组合逻辑变化 ---
        sram_ce_n_out_reg := False
        sram_oe_n_out_reg := False
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_addr_out_reg := current_sram_addr
        sram_be_n_out_reg := B(0, config.dataWidth / 8 bits)

        hasReadWaitCycles generate { read_wait_counter := 0 }
        goto(READ_WAIT)
      }
    }
    
    // --- MODIFIED ---: 地址自增逻辑，封装成一个函数以供复用
    private def getNextSramAddr(currentAddr: UInt, axiSize: UInt): UInt = {
        val bytesIncrement = if (axiConfig.useSize) U(1) << axiSize else U(config.bytesPerWord)
        val sramAddrIncrement = if (config.useWordAddressing) (bytesIncrement / config.bytesPerWord).resized else bytesIncrement.resized
        return currentAddr + sramAddrIncrement
    }

    val READ_WAIT: State = new State {
      onEntry { // MODIFIED: 确保进入此状态时SRAM信号状态正确
        sram_ce_n_out_reg := False
        sram_oe_n_out_reg := False
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        // sram_addr_out_reg 保持不变，由上一个状态设置
        sram_be_n_out_reg := B(0, config.dataWidth / 8 bits)
      }
      whenIsActive {
        if (config.enableLog)
          report(
            L"$instanceId READ_WAIT. SRAM Addr=0x${sram_addr_out_reg}, WaitCounter=${hasReadWaitCycles generate read_wait_counter}, AddrPrefetchValid=${addr_prefetch_valid}"
          )
        // --- MODIFIED: 保持SRAM控制信号 ---
        sram_ce_n_out_reg := False
        sram_oe_n_out_reg := False
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False // 读操作数据总线不能被控制器驱动

        val prefetch_trigger_cycle =
          if (config.readWaitCycles == 0) U(0) else U(config.readWaitCycles - 1)
        val prefetch_waitCond = if (hasReadWaitCycles) read_wait_counter === prefetch_trigger_cycle else True
        when(
          prefetch_waitCond &&
            burst_count_remaining > 1 &&
            !addr_prefetch_valid
        ) {
          next_sram_addr_prefetch := getNextSramAddr(current_sram_addr, ar_cmd_reg.size)
          addr_prefetch_valid := True
          if (config.enableLog)
            report(
              L"$instanceId Address prefetch at wait_cycle ${hasReadWaitCycles generate read_wait_counter} - Next sram_addr 0x${getNextSramAddr(current_sram_addr, ar_cmd_reg.size)}"
            )
        }
        val waitCond = if (hasReadWaitCycles) read_wait_counter === config.readWaitCycles else True
        when(waitCond) {
          read_data_buffer := io.ram.data.read // 在等待周期结束后，锁存SRAM数据
          // if (config.readWaitCycles == 0) { // MODIFIED: 这段逻辑现在不需要额外判断，因为read_wait_counter会立即满足条件
          //   when(burst_count_remaining > 1 && !addr_prefetch_valid) {
          //     next_sram_addr_prefetch := getNextSramAddr(current_sram_addr, ar_cmd_reg.size)
          //     addr_prefetch_valid := True
          //   }
          // }
          goto(READ_RESPONSE)
        } otherwise {
          hasReadWaitCycles generate { read_wait_counter := read_wait_counter + 1 }
        }
      }
    }

    val READ_RESPONSE: State = new State {
      onEntry { // MODIFIED: 确保进入此状态时SRAM信号状态正确
        sram_ce_n_out_reg := False // 保持片选和输出使能有效，直到AXI传输完成
        sram_oe_n_out_reg := False
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_addr_out_reg := current_sram_addr // 保持地址
        sram_be_n_out_reg := B(0, config.dataWidth / 8 bits)
      }
      whenIsActive {
        val is_last_beat = burst_count_remaining === 1
        if (config.enableLog)
          report(
            L"$instanceId READ_RESPONSE. ID=${ar_cmd_reg.id}, Data=0x${read_data_buffer}, BurstCountRem=${burst_count_remaining}, Resp=${Axi4.resp.OKAY}, Last=${is_last_beat}"
          )

        io.axi.r.valid := True
        io.axi.r.payload.id := ar_cmd_reg.id
        io.axi.r.payload.data := read_data_buffer
        io.axi.r.payload.resp := Axi4.resp.OKAY
        io.axi.r.payload.last := is_last_beat
        
        // --- MODIFIED: 保持SRAM控制信号，避免组合逻辑变化 ---
        sram_ce_n_out_reg := False
        sram_oe_n_out_reg := False
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_addr_out_reg := current_sram_addr
        sram_be_n_out_reg := B(0, config.dataWidth / 8 bits)


        when(io.axi.r.fire) {
          if (config.enableLog) report(L"$instanceId R Fire. Last=${io.axi.r.last}")
          burst_count_remaining := burst_count_remaining - 1
          when(is_last_beat) {
            goto(IDLE)
          } otherwise {
            when(addr_prefetch_valid) {
              current_sram_addr := next_sram_addr_prefetch
              addr_prefetch_valid := False // 已经使用了预取地址，清空标志
            } otherwise {
              current_sram_addr := getNextSramAddr(current_sram_addr, ar_cmd_reg.size)
            }
            goto(READ_SETUP)
          }
        } otherwise {
          if (config.enableLog) report(L"io.axi.r.valid = ${io.axi.r.valid}, io.axi.r.ready = ${io.axi.r.ready}")
        }
      }
      onExit {
        // --- MODIFIED: 确保退出读响应状态时，SRAM信号恢复非活动状态 ---
        sram_oe_n_out_reg := True
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
    }

    val READ_RESPONSE_ERROR: State = new State {
      onEntry { // MODIFIED: 确保错误状态下SRAM控制信号安全
        sram_ce_n_out_reg := True
        sram_oe_n_out_reg := True
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
      whenIsActive {
        val is_last_beat = burst_count_remaining === 1
        if (config.enableLog)
          report(
            L"$instanceId READ_RESPONSE_ERROR. ID=${ar_cmd_reg.id}, BurstCountRem=${burst_count_remaining}, Resp=${Axi4.resp.SLVERR}, Last=${is_last_beat}, r.valid=${io.axi.r.valid}, r.ready=${io.axi.r.ready}, r.fire=${io.axi.r.fire}"
          )

        io.axi.r.valid := True
        io.axi.r.payload.id := ar_cmd_reg.id
        io.axi.r.payload.data := B(0, axiConfig.dataWidth bits)
        io.axi.r.payload.resp := Axi4.resp.SLVERR
        io.axi.r.payload.last := is_last_beat

        when(io.axi.r.fire) {
          if (config.enableLog)
            report(L"$instanceId READ_RESPONSE_ERROR - r.fire detected! BurstCountRem=${burst_count_remaining}, is_last_beat=${is_last_beat}")
          burst_count_remaining := burst_count_remaining - 1
          when(is_last_beat) {
            if (config.enableLog)
              report(L"$instanceId READ_RESPONSE_ERROR - Going to IDLE")
            goto(IDLE)
          }
        }
      }
    }
  }

  fsm.build()
  fsm.stateReg.addAttribute("MARK_DEBUG", "TRUE")
  Seq(io.axi.aw.fire, io.axi.ar.fire, io.axi.w.fire, io.axi.r.fire, io.axi.b.fire).foreach(
    _.addAttribute("mark_debug", "true")
  )

  if (config.enableLog) {
    val currentCycle = Reg(UInt(32 bits)) init(0)
    currentCycle := currentCycle + 1
    report(
      L"SRAMController ${instanceId} - Cycle ${currentCycle}: AXI Status\n" :+
      L"  FSM State: ${fsm.stateReg}\n" :+ // 打印当前FSM状态
      L"  AW: v=${io.axi.aw.valid} r=${io.axi.aw.ready} fire=${io.axi.aw.fire} addr=${io.axi.aw.addr} id=${io.axi.aw.id} len=${io.axi.aw.len} size=${io.axi.aw.size} burst=${io.axi.aw.burst}\n" :+
      L"  AR: v=${io.axi.ar.valid} r=${io.axi.ar.ready} fire=${io.axi.ar.fire} addr=${io.axi.ar.addr}\n" :+
      L"  W: v=${io.axi.w.valid} r=${io.axi.w.ready} fire=${io.axi.w.fire} data=${io.axi.w.data} strb=${io.axi.w.strb} last=${io.axi.w.last}\n" :+
      L"  R: v=${io.axi.r.valid} r=${io.axi.r.ready} fire=${io.axi.r.fire} data=${io.axi.r.data} last=${io.axi.r.last}\n" :+
      L"  B: v=${io.axi.b.valid} r=${io.axi.b.ready} fire=${io.axi.b.fire}\n" :+
      L"  Internal: BurstRemaining=${fsm.burst_count_remaining}, CurrentSRAMAddr=${fsm.current_sram_addr}, ReadPriority=${fsm.read_priority}"
    )
  }
}

object SRAMControllerGen extends App {
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
    useLock = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useRegion = false,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true
  )

  // 示例1：原始的字节寻址模式 (1MB SRAM)
  val ramConfigByteAddr = SRAMConfig(
    addressWidth = 20, // 2^20 = 1M, 匹配字节数
    dataWidth = 32,
    virtualBaseAddress = 0x80000000L,
    sizeBytes = 1 << 20, // 1MB
    useWordAddressing = false, // 明确禁用字地址模式
    readWaitCycles = 0,
    writeWaitCycles = 0,
    sramByteEnableIsActiveLow = true,
    enableLog = true
  )
  
  println("--- Generating SRAMController with Byte Addressing ---")
  SpinalConfig(
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC)
  ).generateVerilog(new SRAMController(axiConfig, ramConfigByteAddr).setDefinitionName("SRAMControllerByteAddr"))


  // --- NEW ---
  // 示例2：新的字寻址模式 (4MB SRAM)
  // 您的示例：4MB = 4 * 1024 * 1024 = 2^22 bytes
  // 数据宽度 32-bit (4 bytes/word)
  // 字的数量 = (2^22 bytes) / (4 bytes/word) = 2^20 words
  // 因此，SRAM 物理地址线只需要 20 位 (addressWidth = 20)
  val ramConfigWordAddr = SRAMConfig(
    addressWidth = 20, // 2^20 = 1M words, 匹配字数
    dataWidth = 32,
    virtualBaseAddress = 0x80000000L,
    sizeBytes = 4 * 1024 * 1024, // 4MB
    useWordAddressing = true,    // 启用字地址模式
    readWaitCycles = 1, // 对于字寻址，这里保留等待周期
    writeWaitCycles = 1, // 对于字寻址，这里保留等待周期
    sramByteEnableIsActiveLow = true,
    enableLog = true
  )
  
  println("\n--- Generating SRAMController with Word Addressing ---")
  SpinalConfig(
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC)
  ).generateVerilog(new SRAMController(axiConfig, ramConfigWordAddr).setDefinitionName("SRAMControllerWordAddr"))

}
