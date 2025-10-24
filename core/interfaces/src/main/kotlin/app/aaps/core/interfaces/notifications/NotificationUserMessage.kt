package app.aaps.core.interfaces.notifications

data class NotificationUserMessage(
    override var id: Int,
    override var date: Long,
    override var text: String,
    override var level: Int
) : Notification(id, date, text, level) {
    companion object {
        const val USER_MESSAGE = NotificationConstants.USER_MESSAGE
    }
}
