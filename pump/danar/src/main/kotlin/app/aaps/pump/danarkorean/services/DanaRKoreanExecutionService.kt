package app.aaps.pump.danarkorean.services

import android.annotation.SuppressLint
import android.os.Binder
import android.os.SystemClock
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
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
import app.aaps.pump.dana.R
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

    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var danaRPlugin: DanaRPlugin
    @Inject lateinit var danaRKoreanPlugin: DanaRKoreanPlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var messageHashTableRKorean: MessageHashTableRKorean
    @Inject lateinit var profileFunction: ProfileFunction

    // 大剂量重试配置 - 最大重试3次，每次间隔1秒
    private val MAX_BOLUS_RETRIES = 3
    private val RETRY_DELAY_MS = 1000L

    override fun onCreate() {
        super.onCreate()
        mBinder = LocalBinder()
    }

    @SuppressLint("MissingPermission") override fun connect() {
        if (isConnecting) return
        Thread(Runnable {
            mHandshakeInProgress = false
            isConnecting = true
            getBTSocketForSelectedPump()
            if (mRfcommSocket == null || mBTDevice == null) {
                isConnecting = false
                return@Runnable  // 未找到设备
            }
            try {
                mRfcommSocket?.connect()
            } catch (e: IOException) {
                if (e.message?.contains("socket closed") == true) {
                    aapsLogger.error("Unhandled exception", e)
                }
            }
            if (isConnected) {
                mSerialIOThread?.disconnect("Recreate SerialIOThread")
                mSerialIOThread = SerialIOThread(aapsLogger, mRfcommSocket!!, messageHashTableRKorean, danaPump)
                mHandshakeInProgress = true
                rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.HANDSHAKING, 0))
            }
            isConnecting = false
        }).start()
    }

    override fun getPumpStatus() {
        try {
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumpstatus)))
            val statusBasicMsg = MsgStatusBasic_k(injector)
            val tempStatusMsg = MsgStatusTempBasal(injector)
            val exStatusMsg = MsgStatusBolusExtended(injector)
            val checkValue = MsgCheckValueK(injector)
            if (danaPump.isNewPump) {
                mSerialIOThread?.sendMessage(checkValue)
                if (!checkValue.isReceived) {
                    return
                }
            }

            mSerialIOThread?.sendMessage(statusBasicMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingtempbasalstatus)))
            mSerialIOThread?.sendMessage(tempStatusMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingextendedbolusstatus)))
            mSerialIOThread?.sendMessage(exStatusMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingbolusstatus)))
            val now = System.currentTimeMillis()
            danaPump.lastConnection = now
            val profile: Profile? = profileFunction.getProfile()
            if (profile != null && abs(danaPump.currentBasal - profile.getBasal()) >= danaRKoreanPlugin.pumpDescription.basalStep) {
                rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumpsettings)))
                mSerialIOThread?.sendMessage(MsgSettingBasal(injector))
                if (!danaRKoreanPlugin.isThisProfileSet(profile) && !commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)) {
                    rxBus.send(EventProfileSwitchChanged())
                }
            }
            if (danaPump.lastSettingsRead + 60 * 60 * 1000L < now || !danaRKoreanPlugin.isInitialized()) {
                rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumpsettings)))
                mSerialIOThread?.sendMessage(MsgSettingShippingInfo(injector))
                mSerialIOThread?.sendMessage(MsgSettingMeal(injector))
                mSerialIOThread?.sendMessage(MsgSettingBasal_k(injector))
                mSerialIOThread?.sendMessage(MsgSettingMaxValues(injector))
                mSerialIOThread?.sendMessage(MsgSettingGlucose(injector))
                mSerialIOThread?.sendMessage(MsgSettingProfileRatios(injector))
                rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingpumptime)))
                mSerialIOThread?.sendMessage(MsgSettingPumpTime(injector))
                if (danaPump.pumpTime == 0L) {
                    // 初始握手失败，重置泵状态
                    danaPump.reset()
                    rxBus.send(EventDanaRNewStatus())
                    rxBus.send(EventInitializationChanged())
                    return
                }
                var timeDiff: Long = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                aapsLogger.debug(LTag.PUMP, "Pump time difference: $timeDiff seconds")
                if (abs(timeDiff) > 10) {
                    waitForWholeMinute() // Dana泵仅支持整分设置
                    // 加10秒确保跨过分界点（最终会被截断为整分）
                    mSerialIOThread?.sendMessage(MsgSetTime(injector, dateUtil.now() + T.secs(10).msecs()))
                    mSerialIOThread?.sendMessage(MsgSettingPumpTime(injector))
                    timeDiff = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                    aapsLogger.debug(LTag.PUMP, "Pump time difference after sync: $timeDiff seconds")
                }
                danaPump.lastSettingsRead = now
            }
            rxBus.send(EventDanaRNewStatus())
            rxBus.send(EventInitializationChanged())
            if (danaPump.dailyTotalUnits > danaPump.maxDailyTotalUnits * Constants.dailyLimitWarning) {
                aapsLogger.debug(LTag.PUMP, "Approaching daily limit: " + danaPump.dailyTotalUnits + "/" + danaPump.maxDailyTotalUnits)
                if (System.currentTimeMillis() > lastApproachingDailyLimit + 30 * 60 * 1000) {
                    uiInteraction.addNotification(Notification.APPROACHING_DAILY_LIMIT, rh.gs(R.string.approachingdailylimit), Notification.URGENT)
                    pumpSync.insertAnnouncement(
                        rh.gs(R.string.approachingdailylimit) + ": " + danaPump.dailyTotalUnits + "/" + danaPump.maxDailyTotalUnits + "U",
                        null,
                        PumpType.DANA_R_KOREAN,
                        danaRKoreanPlugin.serialNumber()
                    )
                    lastApproachingDailyLimit = System.currentTimeMillis()
                }
            }
            doSanityCheck()
        } catch (e: Exception) {
            aapsLogger.error("Unhandled exception in getPumpStatus", e)
        }
    }

    override fun tempBasal(percent: Int, durationInHours: Int): Boolean {
        if (!isConnected) return false
        if (danaPump.isTempBasalInProgress) {
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingtempbasal)))
            mSerialIOThread?.sendMessage(MsgSetTempBasalStop(injector))
            SystemClock.sleep(500)
        }
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.settingtempbasal)))
        mSerialIOThread?.sendMessage(MsgSetTempBasalStart(injector, percent, durationInHours))
        mSerialIOThread?.sendMessage(MsgStatusTempBasal(injector))
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    override fun tempBasalStop(): Boolean {
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingtempbasal)))
        mSerialIOThread?.sendMessage(MsgSetTempBasalStop(injector))
        mSerialIOThread?.sendMessage(MsgStatusTempBasal(injector))
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    override fun extendedBolus(insulin: Double, durationInHalfHours: Int): Boolean {
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.settingextendedbolus)))
        mSerialIOThread?.sendMessage(MsgSetExtendedBolusStart(injector, insulin, (durationInHalfHours and 0xFF).toByte()))
        mSerialIOThread?.sendMessage(MsgStatusBolusExtended(injector))
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    override fun extendedBolusStop(): Boolean {
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.stoppingextendedbolus)))
        mSerialIOThread?.sendMessage(MsgSetExtendedBolusStop(injector))
        mSerialIOThread?.sendMessage(MsgStatusBolusExtended(injector))
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    override fun loadEvents(): PumpEnactResult? {
        return null
    }

    // 核心修改：大剂量注射方法新增重试逻辑（已修复编译错误）
    override fun bolus(amount: Double, carbs: Int, carbTimeStamp: Long, t: EventOverviewBolusProgress.Treatment): Boolean {
        if (!isConnected) {
            aapsLogger.error("Bolus failed: Pump not connected")
            return false
        }
        if (BolusProgressData.stopPressed) {
            aapsLogger.error("Bolus failed: User pressed stop")
            t.insulin = 0.0
            return false
        }

        // 初始化大剂量状态
        danaPump.bolusingTreatment = t
        danaPump.bolusDone = false
        danaPump.bolusStopped = false
        danaPump.bolusStopForced = false
        danaPump.bolusAmountToBeDelivered = amount
        var retryCount = 0
        var isBolusSuccess = false

        // 1. 先发送碳水数据（如有）
        if (carbs > 0) {
            mSerialIOThread?.sendMessage(MsgSetCarbsEntry(injector, carbTimeStamp, carbs))
            aapsLogger.debug(LTag.PUMP, "Sent carb entry: $carbs g at $carbTimeStamp")
        }

        // 2. 大剂量注射：循环重试直到成功或达到最大次数
        if (amount > 0) {
            while (retryCount < MAX_BOLUS_RETRIES && !isBolusSuccess && !BolusProgressData.stopPressed) {
                // 每次重试都重新初始化大剂量指令（避免复用旧指令的状态）
                val bolusStartMsg = MsgBolusStart(injector, amount)
                danaPump.bolusProgressLastTimeStamp = System.currentTimeMillis() // 重置超时计时器
                danaPump.bolusStopped = false

                // 发送大剂量指令
                mSerialIOThread?.sendMessage(bolusStartMsg)
                aapsLogger.debug(LTag.PUMP, "Bolus attempt ${retryCount + 1}/$MAX_BOLUS_RETRIES: Sending $amount U")

                // 等待大剂量完成/超时/停止
                while (!danaPump.bolusStopped && !bolusStartMsg.failed && !BolusProgressData.stopPressed) {
                    SystemClock.sleep(100) // 短间隔轮询，避免占用CPU
                    // 检查通信超时（15秒无状态更新则判定失败）
                    if (System.currentTimeMillis() - danaPump.bolusProgressLastTimeStamp > 15 * 1000L) {
                        danaPump.bolusStopped = true
                        danaPump.bolusStopForced = true
                        aapsLogger.error(LTag.PUMP, "Bolus attempt ${retryCount + 1} timed out (15s no response)")
                        break
                    }
                }

                // 检查本次尝试结果
                if (danaPump.bolusDone && !bolusStartMsg.failed && !danaPump.bolusStopForced) {
                    // 大剂量成功
                    isBolusSuccess = true
                    aapsLogger.debug(LTag.PUMP, "Bolus success on attempt ${retryCount + 1}: $amount U delivered")
                } else if (BolusProgressData.stopPressed) {
                    // 用户主动停止，不再重试
                    aapsLogger.error(LTag.PUMP, "Bolus stopped by user on attempt ${retryCount + 1}")
                    t.insulin = 0.0
                    break
                } else {
                    // 尝试失败，准备重试
                    retryCount++
                    aapsLogger.error(LTag.PUMP, "Bolus failed on attempt ${retryCount}: ${if (bolusStartMsg.failed) "Command failed" else "Forced stop"}")
                    if (retryCount < MAX_BOLUS_RETRIES) {
                        // 重试前等待1秒（给泵恢复时间）
                        SystemClock.sleep(RETRY_DELAY_MS)
                        aapsLogger.debug(LTag.PUMP, "Waiting $RETRY_DELAY_MS ms before next retry")
                    }
                }
            }

            // 处理最终结果
            if (isBolusSuccess) {
                t.insulin = amount // 标记实际注射剂量
                commandQueue.readStatus(rh.gs(app.aaps.core.ui.R.string.bolus_ok), null)
                aapsLogger.debug(LTag.PUMP, "Bolus completed successfully: $amount U")
            } else {
                t.insulin = 0.0 // 标记注射失败
                aapsLogger.error(LTag.PUMP, "Bolus failed after $MAX_BOLUS_RETRIES attempts: $amount U not delivered")
                // 已移除未定义的通知代码，避免编译错误
            }
        }

        // 清理状态
        danaPump.bolusingTreatment = null
        return isBolusSuccess
    }

    override fun highTempBasal(percent: Int, durationInMinutes: Int): Boolean = false

    override fun tempBasalShortDuration(percent: Int, durationInMinutes: Int): Boolean = false

    override fun updateBasalsInPump(profile: Profile): Boolean {
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.updatingbasalrates)))
        val basal: Array<Double> = danaPump.buildDanaRProfileRecord(profile)
        val msgSet = MsgSetSingleBasalProfile(injector, basal)
        mSerialIOThread?.sendMessage(msgSet)
        danaPump.lastSettingsRead = 0 // 强制重新读取配置以确认更新
        getPumpStatus()
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))
        return true
    }

    override fun setUserOptions(): PumpEnactResult? = null

    inner class LocalBinder : Binder() {
        val serviceInstance: DanaRKoreanExecutionService
            get() = this@DanaRKoreanExecutionService
    }
}
