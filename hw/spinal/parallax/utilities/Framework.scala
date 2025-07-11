// SPDX-FileCopyrightText: 2023 "Everybody"
//
// SPDX-License-Identifier: MIT

package parallax.utilities

import spinal.core._
import spinal.core.fiber.{Handle, Lock}
import spinal.lib.pipeline._

import scala.collection.mutable.ArrayBuffer
import scala.reflect.{ClassTag, classTag}
import spinal.idslplugin.Location

/** 服务（Service）特质（Trait）
  * 这是一个标记特质，用于标识框架中的各种服务或组件。
  * 插件（Plugin）本身也是一种服务。
  */
trait Service {}

/** 插件（Plugin）特质（Trait）
  * 继承自 SpinalHDL 的 Area（表示硬件区域）和 Service 特质。
  * 这是构建 NaxRiscv 核心的基本模块单元。
  */
trait Plugin extends Area with Service {
  // 自动设置插件实例的名称为其类名
  this.setName(ClassName(this))
  // 提供一个方法来给插件名称添加前缀
  def withPrefix(prefix: String) = setName(prefix + "_" + getName())

  // 指向主框架（Framework）实例的句柄（Handle）。Handle 类似于一个 Future 或 Promise，用于异步获取值。
  val framework = Handle[Framework]()
  // 用于存储在配置（config）阶段创建的任务句柄
  val configsHandles = ArrayBuffer[Handle[_]]()
  // 用于存储在早期（early）阶段创建的任务句柄
  val earlyHandles = ArrayBuffer[Handle[_]]()

  /** 提供创建不同阶段任务的方法 (`config`, `early`, `late`)
    * 这些方法用于将代码块（body）安排在框架构建过程的特定阶段执行。
    */
  def create = new {

    /** 创建一个在“配置”（Config）阶段执行的任务。
      * @param body 要执行的代码块
      * @return 一个 Handle，可用于等待任务完成
      */
    def config[T](body: => T): Handle[T] = {
      val h = Handle { // 创建一个异步任务句柄
        framework.buildLock.retain() // 获取构建锁，防止在任务执行期间构建过程继续
        val ret = framework.rework { // 在框架的 rework 上下文中执行，可能用于异常处理或重试
          framework.configLock.await() // 等待配置锁被释放（表示配置阶段开始）
          body // 执行用户提供的代码
        }
        framework.buildLock.release() // 释放构建锁
        ret // 返回代码块的执行结果
      }
      configsHandles += h // 将此任务的句柄添加到列表中，以便框架稍后可以等待它完成
      h // 返回句柄
    }

    /** 创建一个在“早期”（Early）阶段执行的任务。
      * @param body 要执行的代码块
      * @return 一个 Handle，可用于等待任务完成
      */
    def early[T](body: => T): Handle[T] = {
      val h = Handle { // 创建一个异步任务句柄
        framework.buildLock.retain() // 获取构建锁
        val ret = framework.rework { // 在框架的 rework 上下文中执行
          framework.earlyLock.await() // 等待早期锁被释放（表示早期阶段开始）
          body // 执行用户提供的代码
        }
        framework.buildLock.release() // 释放构建锁
        ret // 返回结果
      }
      earlyHandles += h // 将此任务的句柄添加到列表中
      h // 返回句柄
    }

    /** 创建一个在“晚期”（Late）阶段执行的任务。
      * @param body 要执行的代码块
      * @return 一个 Handle，可用于等待任务完成
      */
    def late[T](body: => T): Handle[T] = {
      Handle { // 创建一个异步任务句柄
        framework.buildLock.retain() // 获取构建锁
        val ret = framework.rework { // 在框架的 rework 上下文中执行
          framework.lateLock.await() // 等待晚期锁被释放（表示晚期阶段开始）
          body // 执行用户提供的代码
        }
        framework.buildLock.release() // 释放构建锁
        ret // 返回结果
      }
      // 注意：late 任务的句柄没有被存储，框架不会显式等待它们，但它们仍受 buildLock 控制
    }
  }

  /** 获取此插件提供的额外子服务（除了插件本身）。
    * 默认情况下不提供额外的子服务。
    * @return 一个 Service 序列
    */
  def getSubServices(): Seq[Service] = Nil

  // --- 服务查找的便捷方法 ---

  /** 检查是否存在指定类型 T 的服务。
    * @tparam T 要查找的服务类型，必须是 Service 的子类型
    * @return 如果至少存在一个 T 类型的服务，则返回 true，否则返回 false
    */
  def isServiceAvailable[T <: Service: ClassTag]: Boolean = framework.getServicesOf[T].nonEmpty

  /** 获取指定类型 T 的唯一服务实例。
    * 如果找不到或找到多个，则会抛出异常。
    * @tparam T 要获取的服务类型
    * @return T 类型的服务实例
    */
  def getService[T <: Service: ClassTag]: T = framework.getService[T]

  //  def getService[T <: Service : ClassTag](id : Any) : T = framework.getService[T](id) // 这个重载方法被注释掉了

  /** 获取所有指定类型 T 的服务实例。
    * @tparam T 要获取的服务类型
    * @return 包含所有 T 类型服务实例的序列
    */
  def getServicesOf[T <: Service: ClassTag]: Seq[T] = framework.getServicesOf[T]

  /** 获取指定类型 T 的可选服务实例。
    * 如果找不到，返回 None；如果找到一个，返回 Some(instance)；如果找到多个，抛出异常。
    * @tparam T 要获取的服务类型
    * @return Option[T]
    */
  def getServiceOption[T <: Service: ClassTag]: Option[T] =
    if (isServiceAvailable[T]) Some(framework.getService[T]) else None

  /** 查找并返回满足特定条件的唯一服务实例。
    * @param filter 一个函数，用于测试服务实例是否满足条件
    * @tparam T 要查找的服务类型
    * @return 满足条件的 T 类型服务实例
    */
  def findService[T <: Service: ClassTag](filter: T => Boolean) =
    getServicesOf[T].find(filter).get // 使用 .get，如果找不到会抛出异常
}

/** 框架配置类。
  * 目前仅用于在创建 Framework 实例之前收集插件列表。
  */
class FrameworkConfig() {
  // 用于存储将要添加到框架中的插件列表
  val plugins = ArrayBuffer[Plugin]()
}

/** 主框架类（Framework）
  * 继承自 SpinalHDL 的 Area。
  * 负责管理插件的生命周期、服务发现以及构建阶段的同步。
  * @param plugins 包含在此框架实例中的插件序列
  */
class Framework(val plugins: Seq[Plugin]) extends Area {
  // 收集所有服务：包括插件本身以及它们通过 getSubServices() 提供的子服务
  val services = plugins ++ plugins.flatMap(_.getSubServices())
  ParallaxLogger.log("Framework: services collected")
  // --- 用于控制构建阶段的锁 ---
  val configLock = Lock() // 配置阶段锁
  val earlyLock = Lock() // 早期阶段锁
  val lateLock = Lock() // 晚期阶段锁
  val buildLock = Lock() // 构建过程主锁（用于保护单个任务的执行）

  // --- 框架初始化和构建流程 ---

  // 1. 初始化时，获取（锁定）所有阶段锁，阻止这些阶段的任务执行
  configLock.retain()
  earlyLock.retain()
  lateLock.retain()

  ParallaxLogger.log("Framework: locks retained")
  // 注意：buildLock 初始时是未锁定的，允许任务尝试获取它

  // 2. 将当前框架实例（this）加载到每个插件的 framework 句柄中
  //    这样插件内部就可以通过 framework 访问框架的功能（如服务查找、锁）
  plugins.foreach(_.framework.load(this)) // 这也会触发插件内部 Handle 的依赖解析

  ParallaxLogger.log("Framework: framework injected to plugins")

  // 3. 释放配置锁，允许通过 plugin.create.config 创建的任务开始执行
  configLock.release()
  ParallaxLogger.log("Framework: config lock released")

  // 4. 等待所有插件的配置任务（configsHandles 中的句柄）完成
  plugins.foreach(_.configsHandles.foreach(_.await()))
  ParallaxLogger.log("Framework: config tasks completed")
  // 5. 释放早期锁，允许通过 plugin.create.early 创建的任务开始执行
  earlyLock.release()
  ParallaxLogger.log("Framework: early lock released")
  // 6. 等待所有插件的早期任务（earlyHandles 中的句柄）完成
  plugins.foreach(_.earlyHandles.foreach(_.await()))
  ParallaxLogger.log("Framework: early tasks completed")
  // 7. 释放晚期锁，允许通过 plugin.create.late 创建的任务开始执行
  lateLock.release()
  ParallaxLogger.log("Framework: late lock released")
  // 注意：框架本身不显式等待晚期任务完成，它们会在后台运行，直到其自身逻辑结束或整个构建过程完成

  // --- 服务查找方法 ---

  /** 获取所有指定类型 T 的服务实例。
    * 使用 Scala 反射（ClassTag 和 isAssignableFrom）来查找类型兼容的服务。
    * @tparam T 要查找的服务类型
    * @return 包含所有 T 类型服务实例的序列
    */
  def getServicesOf[T <: Service: ClassTag]: Seq[T] = {
    val clazz = (classTag[T].runtimeClass) // 获取 T 的运行时 Class 对象
    // 过滤 services 列表，只保留那些类是 clazz 或其子类的服务
    val filtered = services.filter(o => clazz.isAssignableFrom(o.getClass))
    filtered.asInstanceOf[Seq[T]] // 将过滤后的结果转换为 Seq[T] 类型
  }

  /** 获取指定类型 T 的唯一服务实例。
    * 如果找到零个或多个，则抛出异常。
    * @tparam T 要获取的服务类型
    * @return T 类型的服务实例
    */
  def getService[T <: Service: ClassTag]: T = {
    val filtered = getServicesOf[T] // 先获取所有 T 类型的服务
    filtered.length match {
      case 0 => throw new Exception(s"找不到服务 ${classTag[T].runtimeClass.getName}") // 未找到
      case 1 => filtered.head // 刚好找到一个，返回它
      case _ => throw new Exception(s"找到多个 ${classTag[T].runtimeClass.getName} 实例") // 找到多个
    }
  }

  /** 获取指定类型 T 的可选服务实例。
    * 如果找不到，返回 None；如果找到一个，返回 Some(instance)；如果找到多个，抛出异常。
    * @tparam T 要获取的服务类型
    * @return Option[T]
    */
  def getServiceOption[T <: Service: ClassTag]: Option[T] = {
    val filtered = getServicesOf[T]
    filtered.length match {
      case 0 => None // 未找到，返回 None
      case 1 => Some(filtered.head) // 找到一个，返回 Some(instance)
      case _ => throw new Exception(s"找到多个 ${classTag[T].runtimeClass.getName} 实例") // 找到多个
    }
  }

  /** 根据类型 T 和一个过滤条件（filter）查找唯一的服务实例。
    * @param filter 一个接收 T 类型参数并返回布尔值的函数
    * @tparam T 要查找的服务类型
    * @return 满足条件的唯一 T 类型服务实例
    */
  def getServiceWhere[T: ClassTag](filter: T => Boolean): T = {
    val clazz = (classTag[T].runtimeClass) // 获取 T 的运行时 Class 对象
    // 过滤 services 列表：类型兼容 T 并且满足 filter 条件
    val filtered = services.filter(o => clazz.isAssignableFrom(o.getClass) && filter(o.asInstanceOf[T]))
    // 断言：必须确保只找到一个符合条件的服务
    assert(filtered.length == 1, s"查找类型 ${clazz.getName} 时，通过 filter 未找到唯一匹配的服务（找到 ${filtered.length} 个）")
    filtered.head.asInstanceOf[T] // 返回找到的唯一实例
  }

  /** 获取框架中注册的所有服务（包括插件和子服务）。
    * @return 所有服务的序列
    */
  def getServices = services

  /**
    * 断言当前出于early阶段
    */
  def requireEarly() = {
    assert(earlyLock.numRetains == 0 && lateLock.numRetains > 0, "当前不处于early阶段" + s" earlyLock.numRetains=${earlyLock.numRetains}")
  }
}

trait LockedService {
  def retain()
  def release()
}

trait LockedImpl extends LockedService {
  val lock = Lock()
  override def retain() = lock.retain()
  override def release() = lock.release()
}

object ConsoleColor {
  var enabled = true

  def ANSI_GREEN = if (enabled) "\u001B[32m" else "";
  def ANSI_BLUE = if (enabled) "\u001B[34m" else "";
  def ANSI_RED = if (enabled) "\u001B[31m" else "";
  def ANSI_YELLOW = if (enabled) "\u001B[33m" else "";
  def ANSI_RESET = if (enabled) "\u001B[0m" else "";
  def ANSI_DIM = if (enabled) "\u001B[2m" else "";
}

// elaborate 时打印。
object ParallaxLogger {
  import ConsoleColor._
  var enabled = false
  def log(foo: String)(implicit line: sourcecode.Line, file: sourcecode.File) = {
    if(enabled) println(s"$ANSI_DIM${file.value}:${line.value}$ANSI_RESET\n\t$ANSI_RESET$foo$ANSI_RESET")
  }
  def info(foo: String)(implicit line: sourcecode.Line, file: sourcecode.File) = {
    if(enabled) println(s"$ANSI_DIM${file.value}:${line.value}$ANSI_RESET\n\t$ANSI_RESET$foo$ANSI_RESET")
  }
  def debug(foo: String)(implicit line: sourcecode.Line, file: sourcecode.File) = {
    if(enabled) println(s"$ANSI_DIM${file.value}:${line.value}$ANSI_RESET\n\t$ANSI_BLUE$foo$ANSI_RESET")
  }

  def warning(foo: String)(implicit line: sourcecode.Line, file: sourcecode.File) = {
    if(enabled) println(s"$ANSI_DIM${file.value}:${line.value}$ANSI_RESET\n\t$ANSI_YELLOW$foo$ANSI_RESET")
  }

  def error(foo: String)(implicit line: sourcecode.Line, file: sourcecode.File) = {
    if(enabled) println(s"$ANSI_DIM${file.value}:${line.value}$ANSI_RESET\n\t$ANSI_RED$foo$ANSI_RESET")
  }

  def panic(foo: String)(implicit line: sourcecode.Line, file: sourcecode.File) = {
    if(enabled) println(s"$ANSI_DIM${file.value}:${line.value}$ANSI_RESET\n\t$ANSI_RED$foo$ANSI_RESET")
    sys.exit(1)
  }

  def success(foo: String)(implicit line: sourcecode.Line, file: sourcecode.File) = {
    if(enabled) println(s"$ANSI_DIM${file.value}:${line.value}$ANSI_RESET\n\t$ANSI_GREEN$foo$ANSI_RESET")
  }

}

// sim 时打印（最终编译为 verilog $display stmt）
object ParallaxSim {
  import ConsoleColor._
  def flattenRecursively(input: Any): Seq[Any] = input match {
    case seq: Seq[Any] => seq.flatMap(flattenRecursively) // 递归处理子元素
    case elem          => Seq(elem) // 非 Seq 元素，包装成单元素 Seq
  }

  def dump(message: Seq[Any])(implicit loc: Location) {
    report(flattenRecursively(message))(loc)
  }

  def debugStage(stage: Stage) {
    // stage.internals.input.ready...
    debug(
      Seq(
        L"Stage status of ${stage.name}: ",
        L"Inputs: ready=${stage.internals.input.ready}, valid=${stage.internals.input.valid}; ",
        L"Outputs: ready=${stage.internals.output.ready}, valid=${stage.internals.output.valid}"
      )
    )
  }

  def log(message: Seq[Any])(implicit loc: Location) {
    report(
      flattenRecursively(
        message
      )
    )(loc)
  }

  def logWhen(cond: Bool, message: Seq[Any])(implicit loc: Location) {
    when(cond) {
      report(
        flattenRecursively(
          message
        )
      )(loc)
    }
  }

  def info = log _

  def debug(message: Seq[Any])(implicit loc: Location) {
    report(
      flattenRecursively(
        Seq(
          ANSI_BLUE,
          message,
          ANSI_RESET
        )
      )
    )(loc)
  }

  def success(message: Seq[Any])(implicit loc: Location) {
    report(
      flattenRecursively(
        Seq(
          ANSI_GREEN,
          message,
          ANSI_RESET
        )
      )
    )(loc)
  }

  def error(message: Seq[Any])(implicit loc: Location) {
    report(
      flattenRecursively(
        Seq(
          ANSI_RED,
          message,
          ANSI_RESET
        )
      )
    )(loc)
  }

  def fatal(message: Seq[Any])(implicit loc: Location) {
    report(
      flattenRecursively(
        Seq(
          ANSI_RED,
          message,
          ANSI_RESET
        )
      )
    )(loc)
  }

  def warning(message: Seq[Any])(implicit loc: Location) {
    report(
      flattenRecursively(
        Seq(
          ANSI_YELLOW,
          message,
          ANSI_RESET
        )
      )
    )(loc)
  }
}

trait Formattable {
  def format: Seq[Any]
}

object AddressToMask {

  /** 生成地址对齐的位掩码
    * @param address 目标地址（决定掩码偏移）
    * @param size    掩码尺寸（决定连续1的个数 = 2^size）
    * @param width   输出位宽
    * @return        移位后的位掩码
    */
  def apply(address: UInt, size: UInt, width: Int): Bits = {
    // 1. 生成基础掩码选项：从1位到全1的掩码
    val maskOptions = (0 to log2Up(width)).map { i =>
      // 计算连续1的个数：2^i
      val onesCount = (1 << (1 << i)) - 1
      // 创建指定位宽的基础掩码（低位连续onesCount个1）
      U(i) -> B(onesCount, width bits)
    }

    // 2. 根据size选择对应的基础掩码
    val baseMask = size.muxListDc(maskOptions)

    // 3. 计算有效移位量（取address低位，避免越界）
    val shiftAmount = address(log2Up(width) - 1 downto 0)

    // 4. 将掩码左移并返回
    baseMask |<< shiftAmount
  }
}
