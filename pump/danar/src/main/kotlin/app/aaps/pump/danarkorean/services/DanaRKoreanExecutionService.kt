package app.aaps.pump.danarkorean.services

import android.annotation.SuppressLint
import android.os.Binder
import android.os.SystemClock
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification  // 关键：补充Notification导入
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.rx.events.EventProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.pump.dana.R  // 关键：确保R资源可访问
import app.aaps.pump.dana.events.EventDanaRNewStatus
import app.aaps.pump.danar.DanaRPlugin
import app.aaps.pump.danar.SerialIOThread
import app.aaps.pump.danar.comm.MsgBolusStart
import app.aaps.pump.danar.comm.MsgSetCarbsEntry
import app.aaps.pump.danar.comm.MsgSetExtendedBolusStart
import app.aaps.pump.danar.comm.MsgSetExtendedBolusStop
import app.aaps.pump.danar.comm.MsgSetSingleBasalProfile
import app.aaps.pump.danar.comm.MsgSetTempBasalStart
import app.aaps.pump.danar.comm.MsgSetTempBasalStop
import app.aaps.pump.danar.comm.MsgSetTime
import app.aaps.pump.danar.comm.MsgSettingBasal
import app.aaps.pump.danar.comm.MsgSettingGlucose
import app.aaps.pump.danar.comm.MsgSettingMaxValues
import app.aaps.pump.danar.comm.MsgSettingMeal
import app.aaps.pump.danar.comm.MsgSettingProfileRatios
import app.aaps.pump.danar.comm.MsgSettingPumpTime
import app.aaps.pump.danar.comm.MsgSettingShippingInfo
import app.aaps.pump.danar.comm.MsgStatusBolusExtended
import app.aaps.pump.danar.comm.MsgStatusTempBasal
import app.aaps.pump.danar.services.AbstractDanaRExecutionService
import app.aaps.pump.danarkorean.DanaRKoreanPlugin
import app.aaps.pump.danarkorean.comm.MessageHashTableRKorean
import app.aaps.pump.danarkorean.comm.MsgCheckValueK
import app.aaps.pump.danarkorean.comm.MsgSettingBasal_k
import app.aaps.pump.danarkorean.comm.MsgStatusBasic_k
import java.io.IOException
import javax.inject.Inject
import kotlin.math.abs

class DanaRKoreanExecutionService : AbstractDanaRExecutionService() {

    // 依赖注入：重命名Logger避免隐藏父类成员
    @Inject lateinit var localAapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var danaRPlugin: DanaRPlugin
    @Inject lateinit var danaRKoreanPlugin: DanaRKoreanPlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var messageHashTableRKorean: MessageHashTableRKorean
    @Inject lateinit var profileFunction: ProfileFunction

    // 大剂量重试配置（常量定义，避免魔法值）
    private val MAX_RETRY_COUNT = 3           // 最大重试次数（避免无限循环）
    private val RETRY_INTERVAL_MS = 1000L       // 重试间隔（1秒，给泵端恢复时间）
    private val BOLUS_TOLERANCE = 0.05        // 注射量允许误差（0.05U，适配泵精度）
    private var localLastApproachingDailyLimit: Long = 0L  // 重命名避免父类final变量冲突

    override fun onCreate() {
        super.onCreate()
        mBinder = LocalBinder()  // 初始化Binder，支持外部绑定服务
    }

    /**
     * 蓝牙连接逻辑：建立与泵的RFCOMM连接，创建通信线程
     */
    @SuppressLint("MissingPermission")
    override fun connect() {
        if (isConnecting) return  // 避免重复发起连接
        Thread(Runnable {
            mHandshakeInProgress = false
            isConnecting = true
            getBTSocketForSelectedPump()  // 从父类继承：获取选中泵的蓝牙Socket

            // 校验Socket和设备有效性
            if (mRfcommSocket == null || mBTDevice == null) {
                isConnecting = false
                return@Runnable
            }

            try {
                mRfcommSocket?.connect()  // 发起蓝牙连接
            } catch (e: IOException) {
                if (e.message?.contains("socket closed") == true) {
                    localAapsLogger.error("蓝牙连接异常", e)
                }
            }

            // 连接成功：创建通信线程
            if (isConnected) {
                mSerialIOThread?.disconnect("重建SerialIOThread")  // 销毁旧线程
                mSerialIOThread = SerialIOThread(
                    localAapsLogger,
                    mRfcommSocket!!,
                    messageHashTableRKorean,
                    danaPump
                )
                mHandshakeInProgress = true
                rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.HANDSHAKING, 0))
            }
            isConnecting = false
        }).start()
    }

    /**
     * 泵状态同步：获取泵的实时状态、配置和时间，确保数据一致性
     */
    override fun getPumpStatus() {
        try {
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumpstatus)))

            // 初始化状态查询指令
            val statusBasicMsg = MsgStatusBasic_k(injector)    // 基础状态（药量、电池等）
            val tempStatusMsg = MsgStatusTempBasal(injector)   // 临时基础率状态
            val exStatusMsg = MsgStatusBolusExtended(injector) // 扩展大剂量状态
            val checkValue = MsgCheckValueK(injector)          // 新泵校验指令

            // 新泵额外校验
            if (danaPump.isNewPump) {
                mSerialIOThread?.sendMessage(checkValue)
                if (!checkValue.isReceived) return  // 校验失败则终止
            }

            // 发送状态查询指令
            mSerialIOThread?.sendMessage(statusBasicMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingtempbasalstatus)))
            mSerialIOThread?.sendMessage(tempStatusMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingextendedbolusstatus)))
            mSerialIOThread?.sendMessage(exStatusMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingbolusstatus)))

            val now = System.currentTimeMillis()
            danaPump.lastConnection = now
            val profile = profileFunction.getProfile()

            // 基础率配置同步：若当前基础率与Profile差异超出步长，重新读取配置
            if (profile != null && abs(danaPump.currentBasal - profile.getBasal()) >= danaRKoreanPlugin.pumpDescription.basalStep) {
                rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumpsettings)))
                mSerialIOThread?.sendMessage(MsgSettingBasal(injector))
                // 若Profile未同步，触发Profile切换事件
                if (!danaRKoreanPlugin.isThisProfileSet(profile) && !commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)) {
                    rxBus.send(EventProfileSwitchChanged())
                }
            }

            // 配置同步：上次读取超1小时或未初始化，重新读取所有配置
            if (danaPump.lastSettingsRead + 60 * 60 * 1000L < now || !danaRKoreanPlugin.isInitialized()) {
                rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumpsettings)))
                // 发送配置查询指令（硬件信息、餐时设置、基础率、最大剂量等）
                mSerialIOThread?.sendMessage(MsgSettingShippingInfo(injector))
                mSerialIOThread?.sendMessage(MsgSettingMeal(injector))
                mSerialIOThread?.sendMessage(MsgSettingBasal_k(injector))
                mSerialIOThread?.sendMessage(MsgSettingMaxValues(injector))
                mSerialIOThread?.sendMessage(MsgSettingGlucose(injector))
                mSerialIOThread?.sendMessage(MsgSettingProfileRatios(injector))

                // 时间同步
                rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumptime)))
                mSerialIOThread?.sendMessage(MsgSettingPumpTime(injector))
                if (danaPump.pumpTime == 0L) {  // 时间读取失败：重置泵状态
                    danaPump.reset()
                    rxBus.send(EventDanaRNewStatus())
                    rxBus.send(EventInitializationChanged())
                    return
                }

                // 校准泵时间（误差超10秒则校准）
                var timeDiff = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                localAapsLogger.debug(LTag.PUMP, "泵时间差：$timeDiff 秒")
                if (abs(timeDiff) > 10) {
                    waitForWholeMinute()  // 等待整分时刻（Dana泵仅支持整分校准）
                    mSerialIOThread?.sendMessage(MsgSetTime(injector, dateUtil.now() + T.secs(10).msecs()))
                    mSerialIOThread?.sendMessage(MsgSettingPumpTime(injector))
                    timeDiff = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                    localAapsLogger.debug(LTag.PUMP, "校准后泵时间差：$timeDiff 秒")
                }
                danaPump.lastSettingsRead = now
            }

            // 发送状态更新事件
            rxBus.send(EventDanaRNewStatus())
            rxBus.send(EventInitializationChanged())

            // 每日用量上限提醒：接近80%（Constants.dailyLimitWarning默认0.8）时通知
            if (danaPump.dailyTotalUnits > danaPump.maxDailyTotalUnits * Constants.dailyLimitWarning) {
                localAapsLogger.debug(LTag.PUMP, "接近每日上限：${danaPump.dailyTotalUnits}/${danaPump.maxDailyTotalUnits}U")
                // 30分钟内仅提醒一次
                if (System.currentTimeMillis() > localLastApproachingDailyLimit + 30 * 60 * 1000L) {
                    uiInteraction.addNotification(
                        Notification.APPROACHING_DAILY_LIMIT,
                        rh.gs(R.string.approachingdailylimit),
                        Notification.URGENT
                    )
                    // 插入泵公告记录
                    pumpSync.insertAnnouncement(
                        "${rh.gs(R.string.approachingdailylimit)}: ${danaPump.dailyTotalUnits}/${danaPump.maxDailyTotalUnits}U",
                        null,
                        PumpType.DANA_R_KOREAN,
                        danaRKoreanPlugin.serialNumber()
                    )
                    localLastApproachingDailyLimit = System.currentTimeMillis()
                }
            }

            doSanityCheck()  // 从父类继承：状态合理性校验
        } catch (e: Exception) {
            localAapsLogger.error("泵状态同步异常", e)
        }
    }

    /**
     * 临时基础率设置：支持百分比调节，自动停止当前临时基础率
     */
    override fun tempBasal(percent: Int, durationInHours: Int): Boolean {
        if (!isConnected) return false

        // 先停止当前临时基础率（若存在）
        if (danaPump.isTempBasalInProgress) {
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingtempbasal)))
            mSerialIOThread?.sendMessage(MsgSetTempBasalStop(injector))
            SystemClock.sleep(500)  // 等待停止指令生效
        }

        // 发送新临时基础率指令
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.settingtempbasal)))
        mSerialIOThread?.sendMessage(MsgSetTempBasalStart(injector, percent, durationInHours))
        mSerialIOThread?.sendMessage(MsgStatusTempBasal(injector))  // 校验设置结果
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    /**
     * 停止临时基础率
     */
    override fun tempBasalStop(): Boolean {
        if (!isConnected) return false

        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingtempbasal)))
        mSerialIOThread?.sendMessage(MsgSetTempBasalStop(injector))
        mSerialIOThread?.sendMessage(MsgStatusTempBasal(injector))  // 校验停止结果
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    /**
     * 扩展大剂量设置：支持胰岛素量和时长（30分钟为单位）
     */
    override fun extendedBolus(insulin: Double, durationInHalfHours: Int): Boolean {
        if (!isConnected) return false

        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.settingextendedbolus)))
        mSerialIOThread?.sendMessage(MsgSetExtendedBolusStart(
            injector,
            insulin,
            (durationInHalfHours and 0xFF).toByte()  // 时长转为字节（0-255）
        ))
        mSerialIOThread?.sendMessage(MsgStatusBolusExtended(injector))  // 校验设置结果
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    /**
     * 停止扩展大剂量
     */
    override fun extendedBolusStop(): Boolean {
        if (!isConnected) return false

        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingextendedbolus)))
        mSerialIOThread?.sendMessage(MsgSetExtendedBolusStop(injector))
        mSerialIOThread?.sendMessage(MsgStatusBolusExtended(injector))  // 校验停止结果
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    /**
     * 加载泵事件（预留方法，当前未实现）
     */
    override fun loadEvents(): PumpEnactResult? = null

    /**
     * 大剂量注射（核心功能：含安全重试逻辑）
     * @param amount 注射量（U）
     * @param carbs 碳水化合物（g，可选）
     * @param carbTimeStamp 碳水记录时间戳
     * @param t 大剂量治疗记录对象
     * @return 注射是否成功
     */
    override fun bolus(amount: Double, carbs: Int, carbTimeStamp: Long, t: EventOverviewBolusProgress.Treatment): Boolean {
        // 基础拦截：未连接或用户主动停止
        if (!isConnected || BolusProgressData.stopPressed) {
            t.insulin = 0.0
            return false
        }

        // 初始化大剂量状态（避免历史状态干扰）
        danaPump.apply {
            bolusingTreatment = t
            bolusDone = false
            bolusStopped = false
            bolusStopForced = false
            bolusAmountToBeDelivered = amount
            bolusProgressLastTimeStamp = System.currentTimeMillis()
            isBolusing = true  // 标记泵正在注射
        }

        var isFinalSuccess = false
        var retryCount = 0
        var failReason = "未知错误"

        // 记录碳水（仅执行一次，无需重试）
        if (carbs > 0) {
            mSerialIOThread?.sendMessage(MsgSetCarbsEntry(injector, carbTimeStamp, carbs))
        }

        // 大剂量注射主逻辑（带有限重试）
        if (amount > 0) {
            while (retryCount < MAX_RETRY_COUNT && !isFinalSuccess && !BolusProgressData.stopPressed) {
                // 1. 重试前置安全校验：避免重复注射或无效操作
                if (!preBolusSafetyCheck(amount)) {
                    failReason = "重试前状态异常（可能已注射或泵离线）"
                    localAapsLogger.error(LTag.PUMP, "大剂量重试 $retryCount 失败：$failReason")
                    retryCount++
                    SystemClock.sleep(RETRY_INTERVAL_MS)
                    continue
                }

                // 2. 发送大剂量指令
                val bolusCmd = MsgBolusStart(injector, amount)
                mSerialIOThread?.sendMessage(bolusCmd)
                localAapsLogger.debug(LTag.PUMP, "大剂量尝试 $retryCount：发送注射指令（目标：${amount}U）")

                // 3. 等待注射结果（超时15秒，避免无限等待）
                val waitStart = System.currentTimeMillis()
                var isTimeout = false
                while (!danaPump.bolusStopped && !bolusCmd.failed && !isTimeout && !BolusProgressData.stopPressed) {
                    SystemClock.sleep(100)  // 100ms轮询一次
                    // 超时判定：15秒未收到泵进度更新，判定通信中断
                    if (System.currentTimeMillis() - danaPump.bolusProgressLastTimeStamp > 15 * 1000L) {
                        danaPump.bolusStopped = true
                        danaPump.bolusStopForced = true
                        isTimeout = true
                        failReason = "通信超时（15秒无响应）"
                        localAapsLogger.error(LTag.PUMP, "大剂量尝试 $retryCount：$failReason")
                    }
                }

                // 4. 单次尝试结果处理
                when {
                    // 情况1：指令明确失败（泵拒绝）
                    bolusCmd.failed -> {
                        failReason = "泵拒绝指令（可能硬件异常或剂量超限）"
                        localAapsLogger.error(LTag.PUMP, "大剂量尝试 $retryCount：$failReason")
                        retryCount++
                        SystemClock.sleep(RETRY_INTERVAL_MS)
                    }
                    // 情况2：超时或强制停止
                    isTimeout || danaPump.bolusStopForced -> {
                        localAapsLogger.error(LTag.PUMP, "大剂量尝试 $retryCount：$failReason")
                        retryCount++
                        SystemClock.sleep(RETRY_INTERVAL_MS)
                    }
                    // 情况3：指令成功，校验实际注射量
                    else -> {
                        if (postBolusAmountCheck(amount)) {
                            isFinalSuccess = true
                            t.insulin = amount  // 记录实际注射量
                            localAapsLogger.debug(LTag.PUMP, "大剂量尝试 $retryCount：成功（实际注射：${amount}U）")
                        } else {
                            failReason = "实际注射量与目标不符（可能部分注射）"
                            localAapsLogger.error(LTag.PUMP, "大剂量尝试 $retryCount：$failReason")
                            retryCount++
                            SystemClock.sleep(RETRY_INTERVAL_MS)
                        }
                    }
                }
            }

            // 5. 最终结果处理（通知+状态重置）
            if (isFinalSuccess) {
                // 发送成功通知
                uiInteraction.addNotification(
                    Notification.BOLUS_SUCCESS,
                    "${rh.gs(R.string.bolus_ok)}（${amount}U）",
                    Notification.NORMAL
                )
            } else {
                // 发送失败通知，重置状态
                t.insulin = 0.0
                danaPump.bolusAmountToBeDelivered = 0.0
                uiInteraction.addNotification(
                    Notification.BOLUS_FAILED,
                    "${rh.gs(R.string.bolus_failed)}（重试$MAX_RETRY_COUNT次失败：$failReason）",
                    Notification.URGENT
                )
            }
        }

        // 6. 清理资源，同步泵最新状态
        danaPump.isBolusing = false  // 重置注射状态
        SystemClock.sleep(300)        // 等待状态同步
        danaPump.bolusingTreatment = null
        commandQueue.readStatus(
            if (isFinalSuccess) rh.gs(R.string.bolus_ok) else rh.gs(R.string.bolus_failed),
            null
        )

        return isFinalSuccess
    }

    /**
     * 高百分比临时基础率（预留方法，当前未实现）
     */
    override fun highTempBasal(percent: Int, durationInMinutes: Int): Boolean = false

    /**
     * 短时长临时基础率（预留方法，当前未实现）
     */
    override fun tempBasalShortDuration(percent: Int, durationInMinutes: Int): Boolean = false

    /**
     * 更新泵内基础率配置：将用户Profile同步到泵
     */
    override fun updateBasalsInPump(profile: Profile): Boolean {
        if (!isConnected) return false

        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.updatingbasalrates)))
        // 将Profile转换为泵支持的格式（24小时基础率数组）
        val basal = danaPump.buildDanaRProfileRecord(profile)
        val msgSet = MsgSetSingleBasalProfile(injector, basal)
        mSerialIOThread?.sendMessage(msgSet)

        danaPump.lastSettingsRead = 0  // 强制下次读取完整配置
        getPumpStatus()  // 同步更新后的状态
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    /**
     * 设置用户选项（预留方法，当前未实现）
     */
    override fun setUserOptions(): PumpEnactResult? = null

    /**
     * 重试前安全校验：避免重复注射或无效操作
     * @return true：状态正常，可重试；false：状态异常，终止重试
     */
    private fun preBolusSafetyCheck(targetAmount: Double): Boolean {
        // 校验1：泵是否处于连接状态
        if (!isConnected) {
            localAapsLogger.error(LTag.PUMP, "前置校验失败：泵已断开连接")
            return false
        }

        // 校验2：是否正在进行其他大剂量注射
        if (danaPump.isBolusing) {
            localAapsLogger.error(LTag.PUMP, "前置校验失败：泵正在执行其他大剂量")
            return false
        }

        // 校验3：5秒内是否有历史注射记录（防重复注射）
        val lastBolusTime = danaPump.lastBolusTime
        if (System.currentTimeMillis() - lastBolusTime < 5000L) {
            localAapsLogger.error(LTag.PUMP, "前置校验失败：5秒内已有注射记录")
            return false
        }

        // 校验4：目标剂量是否在泵允许范围内（0~最大大剂量）
        if (targetAmount <= 0 || targetAmount > danaPump.maxBolus) {
            localAapsLogger.error(LTag.PUMP, "前置校验失败：剂量超出范围（0~${danaPump.maxBolus}U）")
            return false
        }

        return true
    }

    /**
     * 注射后剂量校验：确认泵端实际注射量与目标一致
     * @return true：剂量一致，判定成功；false：剂量不符，判定失败
     */
    private fun postBolusAmountCheck(targetAmount: Double): Boolean {
        // 发送大剂量状态查询指令，获取泵端实际记录
        mSerialIOThread?.sendMessage(MsgStatusBolusExtended(injector))
        SystemClock.sleep(500)  // 等待泵端响应（500ms足够）

        // 读取泵端实际注射量（DanaPump已修正为非空Double）
        val actualAmount = danaPump.lastBolusAmount

        // 允许微小误差（BOLUS_TOLERANCE=0.05U），避免泵精度问题误判
        val isAmountMatch = abs(actualAmount - targetAmount) <= BOLUS_TOLERANCE
        if (!isAmountMatch) {
            localAapsLogger.error(LTag.PUMP, "剂量校验失败：实际${actualAmount}U vs 目标${targetAmount}U")
        }
        return isAmountMatch
    }

    /**
     * 本地Binder：供外部组件绑定服务并调用方法
     */
    inner class LocalBinder : Binder() {
        val serviceInstance: DanaRKoreanExecutionService
            get() = this@DanaRKoreanExecutionService
    }
}
