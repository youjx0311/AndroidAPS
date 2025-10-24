package app.aaps.core.interfaces.notifications

import android.content.Context
import androidx.annotation.RawRes
import java.util.concurrent.TimeUnit

/**
 * 通知常量类：存储所有通知类型、级别、重要性等静态常量
 * 与可继承的 Notification 基础类分离，避免 object 类无法继承的问题
 */
object NotificationConstants {
    // 大剂量相关通知类型
    const val BOLUS_FAILED = "BOLUS_FAILED"
    // 每日剂量限制相关通知类型
    const val APPROACHING_DAILY_LIMIT = "APPROACHING_DAILY_LIMIT"
    // 用户自定义消息通知类型
    const val USER_MESSAGE = 1000

    // 通知级别：从紧急到普通
    const val URGENT = 0       // 紧急（如注射失败、泵故障）
    const val NORMAL = 1       // 正常（如配置更新提醒）
    const val LOW = 2          // 低优先级（如版本检测）
    const val INFO = 3         // 信息类（如操作成功反馈）
    const val ANNOUNCEMENT = 4 // 公告类（如系统通知）

    // 配置文件相关通知ID
    const val PROFILE_SET_FAILED = 0                  // 配置文件设置失败
    const val PROFILE_SET_OK = 1                      // 配置文件设置成功
    const val PROFILE_NOT_SET_NOT_INITIALIZED = 5     // 配置文件未设置/未初始化
    const val FAILED_UPDATE_PROFILE = 6               // 配置文件更新失败
    const val INVALID_PROFILE_NOT_ACCEPTED = 75       // 无效配置文件（不被泵接受）

    // 基础率相关通知ID
    const val BASAL_VALUE_BELOW_MINIMUM = 7           // 基础率低于最小值
    const val MINIMAL_BASAL_VALUE_REPLACED = 29       // 最小基础率已替换
    const val MAXIMUM_BASAL_VALUE_REPLACED = 39       // 最大基础率已替换
    const val WRONG_BASAL_STEP = 23                   // 基础率步长错误
    const val BASAL_PROFILE_NOT_ALIGNED_TO_HOURS = 30 // 基础率配置未按小时对齐

    // 泵相关通知ID
    const val PUMP_ERROR = 15                         // 泵错误
    const val PUMP_UNREACHABLE = 26                   // 泵无法连接
    const val PUMP_SUSPENDED = 80                     // 泵已暂停
    const val PUMP_SETTINGS_FAILED = 84               // 泵设置失败
    const val PUMP_TIMEZONE_UPDATE_FAILED = 85        // 泵时区更新失败
    const val PUMP_WARNING = 87                       // 泵警告
    const val PUMP_SYNC_ERROR = 88                    // 泵数据同步错误
    const val WRONG_SERIAL_NUMBER = 16                // 泵序列号错误
    const val WRONG_PUMP_PASSWORD = 34                // 泵密码错误
    const val UNSUPPORTED_FIRMWARE = 28               // 不支持的泵固件版本
    const val WRONG_DRIVER = 24                       // 泵驱动错误
    const val UNSUPPORTED_ACTION_IN_PUMP = 71         // 泵不支持当前操作
    const val WRONG_PUMP_DATA = 72                    // 泵数据错误

    // 特定泵型号通知ID
    const val COMBO_PUMP_ALARM = 25                   // Combo泵报警
    const val MEDTRONIC_PUMP_ALARM = 44               // 美敦力泵报警
    const val OMNIPOD_POD_NOT_ATTACHED = 59           // Omnipod Pod未连接
    const val OMNIPOD_POD_SUSPENDED = 61              // Omnipod Pod已暂停
    const val OMNIPOD_POD_ALERTS = 63                 // Omnipod Pod警报
    const val OMNIPOD_POD_FAULT = 66                  // Omnipod Pod故障
    const val OMNIPOD_UNCERTAIN_SMB = 67              // Omnipod SMB不确定
    const val OMNIPOD_UNKNOWN_TBR = 68                // Omnipod未知TBR
    const val OMNIPOD_STARTUP_STATUS_REFRESH_FAILED = 69 // Omnipod启动状态刷新失败
    const val OMNIPOD_TIME_OUT_OF_SYNC = 70           // Omnipod时间不同步
    const val EOFLOW_PATCH_ALERTS = 79                // EOFLOW贴片警报
    const val PATCH_NOT_ACTIVE = 83                   // 贴片未激活
    const val EQUIL_ALARM = 93                       // Equil泵报警
    const val EQUIL_ALARM_INSULIN = 94                // Equil泵胰岛素报警
    const val INSIGHT_DATE_TIME_UPDATED = 47          // Insight泵时间已更新
    const val INSIGHT_TIMEOUT_DURING_HANDSHAKE = 48   // Insight泵握手超时

    // 连接相关通知ID
    const val DEVICE_NOT_PAIRED = 43                  // 设备未配对
    const val BLUETOOTH_NOT_ENABLED = 82              // 蓝牙未开启
    const val BLUETOOTH_NOT_SUPPORTED = 86            // 设备不支持蓝牙
    const val RILEYLINK_CONNECTION = 45               // RileyLink连接问题
    const val PERMISSION_BT = 78                      // 蓝牙权限缺失

    // 血糖相关通知ID
    const val BG_READINGS_MISSED = 27                 // 血糖读数缺失
    const val SHORT_DIA = 21                          //  Dia时间过短
    const val CARBS_REQUIRED = 60                     // 需要输入碳水化合物

    // 夜曲（NS）相关通知ID
    const val OLD_NS = 9                              // 旧版NS
    const val NSCLIENT_NO_WRITE_PERMISSION = 13       // NS客户端无写入权限
    const val NS_ANNOUNCEMENT = 18                    // NS公告
    const val NS_ALARM = 19                           // NS警报
    const val NS_URGENT_ALARM = 20                    // NS紧急警报
    const val NS_MALFUNCTION = 40                     // NS故障
    const val NSCLIENT_VERSION_DOES_NOT_MATCH = 73    // NS客户端版本不匹配

    // 权限相关通知ID
    const val MISSING_SMS_PERMISSION = 14             // 缺失SMS权限
    const val PERMISSION_STORAGE = 35                 // 存储权限缺失
    const val PERMISSION_LOCATION = 36                // 位置权限缺失
    const val PERMISSION_BATTERY = 37                 // 电池权限缺失
    const val PERMISSION_SMS = 38                     // SMS权限缺失
    const val PERMISSION_SYSTEM_WINDOW = 56           // 系统窗口权限缺失

    // 系统/应用相关通知ID
    const val EASY_MODE_ENABLED = 2                   // 简易模式已开启
    const val UD_MODE_ENABLED = 4                     // UD模式已开启
    const val EXTENDED_BOLUS_DISABLED = 3             // 扩展大剂量已禁用
    const val INVALID_PHONE_NUMBER = 10               // 无效手机号
    const val INVALID_MESSAGE_BODY = 11               // 无效消息内容
    const val TOAST_ALARM = 22                        // Toast警报
    const val NEW_VERSION_DETECTED = 41               // 检测到新版本
    const val DISK_FULL = 51                          // 磁盘已满
    const val OVER_24H_TIME_CHANGE_REQUESTED = 54     // 请求超过24小时的时间更改
    const val INVALID_VERSION = 55                    // 无效应用版本
    const val TIME_OR_TIMEZONE_CHANGE = 58            // 时间或时区已更改
    const val IDENTIFICATION_NOT_SET = 77             // 身份标识未设置
    const val MDT_INVALID_HISTORY_DATA = 76           // 美敦力历史数据无效
    const val SMB_FALLBACK = 89                       // SMB fallback触发
    const val DYN_ISF_FALLBACK = 91                   // 动态ISF fallback触发
    const val MASTER_PASSWORD_NOT_SET = 90            // 主密码未设置
    const val AAPS_DIR_NOT_SELECTED = 92              // 未选择AAPS目录
    const val VERSION_EXPIRE = 74                     // 应用版本已过期
    const val DST_LOOP_DISABLED = 49                  // DST循环已禁用
    const val DST_IN_24H = 50                         // 24小时内进入DST

    // 通知重要性与分类
    const val IMPORTANCE_HIGH = 2         // 高重要性（如报警通知）
    const val CATEGORY_ALARM = "alarm"    // 警报分类（系统级警报）
}

/**
 * 通知基础类：可被继承，存储通知的动态属性（如ID、文本、时间等）
 * 提供多构造函数和链式调用方法，适配不同场景的通知创建需求
 */
open class Notification(
    open var id: Int = 0,                          // 通知ID（对应常量类中的ID）
    open var date: Long = System.currentTimeMillis(), // 通知创建时间（毫秒）
    open var text: String = "",                    // 通知文本内容
    open var level: Int = NotificationConstants.URGENT, // 通知级别（默认紧急）
    open var validTo: Long = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10), // 通知有效期（默认10分钟）
    @RawRes open var soundId: Int? = null,         // 通知提示音资源ID（可选）
    open var action: Runnable? = null,             // 通知点击后的执行动作（可选）
    open var buttonText: Int = 0,                  // 通知按钮文本资源ID（可选）
    open var contextForAction: Context? = null     // 动作执行所需的上下文（可选）
) {
    /**
     * 构造函数1：简化版（仅需ID、文本、级别）
     * 适用于快速创建基础通知（如操作结果反馈）
     */
    constructor(id: Int, text: String, level: Int) : this(
        id = id,
        text = text,
        level = level
    )

    /**
     * 构造函数2：带有效期的通知
     * 适用于需要自动失效的通知（如临时提醒）
     */
    constructor(id: Int, text: String, level: Int, validMinutes: Int) : this(
        id = id,
        text = text,
        level = level,
        validTo = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(validMinutes.toLong())
    )

    /**
     * 构造函数3：完整参数版
     * 适用于自定义时间、有效期的通知（如历史事件通知）
     */
    constructor(id: Int, date: Long, text: String, level: Int, validTo: Long) : this(
        id = id,
        date = date,
        text = text,
        level = level,
        validTo = validTo
    )

    /**
     * 链式调用：设置通知文本
     * 示例：Notification(id).text("注射成功").level(INFO)
     */
    fun text(text: String): Notification = this.also { it.text = text }

    /**
     * 链式调用：设置通知级别
     */
    fun level(level: Int): Notification = this.also { it.level = level }

    /**
     * 链式调用：设置通知提示音
     */
    fun sound(soundId: Int): Notification = this.also { it.soundId = soundId }

    /**
     * 链式调用：设置通知点击动作
     */
    fun action(action: Runnable, context: Context): Notification = this.also {
        it.action = action
        it.contextForAction = context
    }

    /**
     * 链式调用：设置通知按钮文本
     */
    fun buttonText(buttonText: Int): Notification = this.also { it.buttonText = buttonText }
}
