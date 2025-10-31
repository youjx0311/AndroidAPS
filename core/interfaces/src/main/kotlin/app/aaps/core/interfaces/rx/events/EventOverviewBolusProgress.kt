package app.aaps.core.interfaces.rx.events

// 确保继承自正确的 Event 类（根据项目实际基础类调整）
open class Event

object EventOverviewBolusProgress : Event() {

    // 内部数据类，用于传递治疗信息
    data class Treatment(
        var insulin: Double = 0.0,
        var carbs: Int = 0,
        var isSMB: Boolean,
        var id: Long
    )

    // 状态枚举
    enum class Status {
        ATTEMPTING,  // 尝试中
        COMPLETED,   // 已完成
        STOPPED      // 已停止
    }

    // 公开字段，供外部访问
    var status: String = ""
    var t: Treatment? = null
    var percent: Int = 0

    // 判断是否为 SMB 类型
    fun isSMB(): Boolean = t?.isSMB == true
}
