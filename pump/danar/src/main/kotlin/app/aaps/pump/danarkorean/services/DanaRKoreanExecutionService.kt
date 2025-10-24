package app.aaps.core.interfaces.notifications

import android.content.Context
import androidx.annotation.RawRes
import java.util.concurrent.TimeUnit

object Notification {
    const val BOLUS_FAILED = "BOLUS_FAILED"
    const val APPROACHING_DAILY_LIMIT = "APPROACHING_DAILY_LIMIT"
    const val USER_MESSAGE = 1000

    const val URGENT = 0
    const val NORMAL = 1
    const val LOW = 2
    const val INFO = 3
    const val ANNOUNCEMENT = 4

    // 其他常量保持不变...
}

open class Notification {

    var id = 0
    var date: Long = 0
    var text: String = ""
    var level = 0
    var validTo: Long = 0
    @RawRes var soundId: Int? = null
    var action: Runnable? = null
    var buttonText = 0

    var contextForAction: Context? = null

    constructor()
    constructor(id: Int, date: Long, text: String, level: Int, validTo: Long) {
        this.id = id
        this.date = date
        this.text = text
        this.level = level
        this.validTo = validTo
    }

    constructor(id: Int, text: String, level: Int, validMinutes: Int) {
        this.id = id
        date = System.currentTimeMillis()
        this.text = text
        this.level = level
        validTo = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(validMinutes.toLong())
    }

    constructor(id: Int, text: String, level: Int) {
        this.id = id
        date = System.currentTimeMillis()
        this.text = text
        this.level = level
    }

    constructor(id: Int) {
        this.id = id
        date = System.currentTimeMillis()
    }

    fun text(text: String): Notification = this.also { it.text = text }
    fun level(level: Int): Notification = this.also { it.level = level }
    fun sound(soundId: Int): Notification = this.also { it.soundId = soundId }

    companion object {
        // 原有常量保持不变...
    }
}
