// core/interfaces/src/main/kotlin/app/aaps/core/interfaces/rx/events/Event.kt
package app.aaps.core.interfaces.rx.events

import org.apache.commons.lang3.builder.ReflectionToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle

/** 事件总线的所有事件的基类 */
abstract class Event {
    override fun toString(): String {
        return ReflectionToStringBuilder.toString(this, ToStringStyle.SHORT_PREFIX_STYLE)
    }

    companion object {
        init {
            ReflectionToStringBuilder.setDefaultStyle(ToStringStyle.SHORT_PREFIX_STYLE)
        }
    }
}
