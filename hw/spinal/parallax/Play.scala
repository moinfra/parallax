// package parallax

// import spinal.core._
// import spinal.core.sim._
// import scala.collection.Seq // 显式导入 Seq
// import scala.util.Random

// // 1. Dumpable Trait: 定义对象如何提供调试信息
// trait Dumpable {
//   def dump(): Seq[Any]
// }

// // 2. InfoProvider: 一个简单的数据类，实现 Dumpable
// case class InfoProvider(id: String, value: Int, tags: List[String]) extends Dumpable {
//   override def dump(): Seq[Any] = L"ID:'$id' Value:$value Tags:$tags"
//   // 注意: L"Tags:$tags" 会将整个 List 作为 Seq 中的一个元素。
//   // flattenMessage 会负责展开这个 List。
// }

// // 3. ModuleDetail: 另一个实现 Dumpable 的类，它包含一个 InfoProvider 以产生嵌套
// case class ModuleDetail(moduleName: String, status: String, subProvider: InfoProvider) extends Dumpable {
//   override def dump(): Seq[Any] = L"Module:'$moduleName' Status:$status ProviderInfo:[${subProvider.dump()}]"
//   // subProvider.dump() 返回一个 Seq[Any]，它将作为 L"..." 结果序列中的一个元素，从而形成嵌套。
// }

// // 5. MySimpleComponent: 一个简单的 SpinalHDL 组件
// // 我们将在组件的构造（阐述）期间调用报告
// class MySimpleComponent extends Component {
//   // 创建一些数据对象实例
//   val sensorData = InfoProvider("SensorAlpha", 42, List("active", "calibrated"))
//   val coreModule = ModuleDetail("CoreProcessingUnit", "Operational", InfoProvider("TempSensor", 25, List("internal")))
//   val backupModule =
//     ModuleDetail("BackupSystem", "Standby", InfoProvider("VoltageSensor", 12, Nil)) // Nil for empty list

//   // 在阐述期间生成报告
//   report(L"Sensor Report: ${sensorData.dump()}")
//   report(L"Core Module Detailed Report: ${coreModule.dump()}")

//   // 一个更复杂的报告，混合了多个 dump() 的结果
//   report(
//     L"""System Overview:
//        Main Core -> ${coreModule.dump()}
//        Backup -> ${backupModule.dump()}
//        Direct Sensor -> ${sensorData.dump()}"""
//   )
// }

// // 6. Main object: 用于运行示例
// object Demo extends App {
//   println("--- Short Report Demo (Elaboration Time Reports) ---")
//   new SpinalConfig().generateVerilog(new MySimpleComponent)
//   new SpinalConfig().generateVhdl(new MySimpleComponent)
//   println("--- Demo Finished ---")
// }
