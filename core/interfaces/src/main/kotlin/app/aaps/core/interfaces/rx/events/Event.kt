package app.aaps.core.interfaces.rx.events

// 正确继承 Event 基类，无重复声明
object EventOverviewBolusProgress : Event() {

    data class Treatment(
        var insulin: Double = 0.0,
        var carbs: Int = 0,
        var isSMB: Boolean,
        var id: Long
    )

    enum class Status {
        ATTEMPTING,
        COMPLETED,
        STOPPED
    }

    var status = ""
    var t: Treatment? = null
    var percent = 0

    fun isSMB(): Boolean = t?.isSMB == true
}
