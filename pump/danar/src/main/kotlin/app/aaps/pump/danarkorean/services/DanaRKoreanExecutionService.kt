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
import app.aaps.core.interfaces.notifications.Notification  // 强制导入！
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
import app.aaps.pump.dana.R  // 强制导入！
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

    @Inject lateinit var localAapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var danaRPlugin: DanaRPlugin
    @Inject lateinit var danaRKoreanPlugin: DanaRKoreanPlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var messageHashTableRKorean: MessageHashTableRKorean
    @Inject lateinit var profileFunction: ProfileFunction

    private val MAX_RETRY_COUNT = 3
    private val RETRY_INTERVAL_MS = 1000L
    private val BOLUS_TOLERANCE = 0.05
    private var localLastApproachingDailyLimit: Long = 0L

    override fun onCreate() {
        super.onCreate()
        mBinder = LocalBinder()
    }

    @SuppressLint("MissingPermission")
    override fun connect() {
        if (isConnecting) return
        Thread(Runnable {
            mHandshakeInProgress = false
            isConnecting = true
            getBTSocketForSelectedPump()
            if (mRfcommSocket == null || mBTDevice == null) {
                isConnecting = false
                return@Runnable
            }
            try {
                mRfcommSocket?.connect()
            } catch (e: IOException) {
                if (e.message?.contains("socket closed") == true) {
                    localAapsLogger.error("连接异常", e)
                }
            }
            if (isConnected) {
                mSerialIOThread?.disconnect("重建SerialIOThread")
                mSerialIOThread = SerialIOThread(localAapsLogger, mRfcommSocket!!, messageHashTableRKorean, danaPump)
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
                if (!checkValue.isReceived) return
            }
            mSerialIOThread?.sendMessage(statusBasicMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingtempbasalstatus)))
            mSerialIOThread?.sendMessage(tempStatusMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingextendedbolusstatus)))
            mSerialIOThread?.sendMessage(exStatusMsg)
            rxBus.send(EventPumpStatusChanged(rh.gs(R.string.gettingbolusstatus)))
            val now = System.currentTimeMillis()
            danaPump.lastConnection = now
            val profile = profileFunction.getProfile()
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
                    danaPump.reset()
                    rxBus.send(EventDanaRNewStatus())
                    rxBus.send(EventInitializationChanged())
                    return
                }
                var timeDiff = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                localAapsLogger.debug(LTag.PUMP, "泵时间差：$timeDiff 秒")
                if (abs(timeDiff) > 10) {
                    waitForWholeMinute()
                    mSerialIOThread?.sendMessage(MsgSetTime(injector, dateUtil.now() + T.secs(10).msecs()))
                    mSerialIOThread?.sendMessage(MsgSettingPumpTime(injector))
                    timeDiff = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                    localAapsLogger.debug(LTag.PUMP, "校准后泵时间差：$timeDiff 秒")
                }
                danaPump.lastSettingsRead = now
            }
            rxBus.send(EventDanaRNewStatus())
            rxBus.send(EventInitializationChanged())
            if (danaPump.dailyTotalUnits > danaPump.maxDailyTotalUnits * Constants.dailyLimitWarning) {
                localAapsLogger.debug(LTag.PUMP, "接近每日上限：${danaPump.dailyTotalUnits}/${danaPump.maxDailyTotalUnits}")
                if (System.currentTimeMillis() > localLastApproachingDailyLimit + 30 * 60 * 1000L) {
                    uiInteraction.addNotification(
                        Notification.APPROACHING_DAILY_LIMIT,
                        rh.gs(R.string.approachingdailylimit),
                        Notification.URGENT
                    )
                    pumpSync.insertAnnouncement(
                        rh.gs(R.string.approachingdailylimit) + ": ${danaPump.dailyTotalUnits}/${danaPump.maxDailyTotalUnits}U",
                        null,
                        PumpType.DANA_R_KOREAN,
                        danaRKoreanPlugin.serialNumber()
                    )
                    localLastApproachingDailyLimit = System.currentTimeMillis()
                }
            }
            doSanityCheck()
        } catch (e: Exception) {
            localAapsLogger.error("未处理异常", e)
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

    override fun loadEvents(): PumpEnactResult? = null

    override fun bolus(amount: Double, carbs: Int, carbTimeStamp: Long, t: EventOverviewBolusProgress.Treatment): Boolean {
        if (!isConnected || BolusProgressData.stopPressed) {
            t.insulin = 0.0
            return false
        }
        danaPump.apply {
            bolusingTreatment = t
            bolusDone = false
            bolusStopped = false
            bolusStopForced = false
            bolusAmountToBeDelivered = amount
            bolusProgressLastTimeStamp = System.currentTimeMillis()
            isBolusing = true
        }
        var isFinalSuccess = false
        var retryCount = 0
        var failReason = "未知错误"
        if (carbs > 0) {
            mSerialIOThread?.sendMessage(MsgSetCarbsEntry(injector, carbTimeStamp, carbs))
        }
        if (amount > 0) {
            while (retryCount < MAX_RETRY_COUNT && !isFinalSuccess && !BolusProgressData.stopPressed) {
                if (!preBolusSafetyCheck(amount)) {
                    failReason = "重试前状态异常"
                    localAapsLogger.error(LTag.PUMP, "大剂量重试 $retryCount 失败：$failReason")
                    retryCount++
                    SystemClock.sleep(RETRY_INTERVAL_MS)
                    continue
                }
                val bolusCmd = MsgBolusStart(injector, amount)
                mSerialIOThread?.sendMessage(bolusCmd)
                localAapsLogger.debug(LTag.PUMP, "大剂量尝试 $retryCount：发送注射指令（目标：${amount}U）")
                val waitStart = System.currentTimeMillis()
                var isTimeout = false
                while (!danaPump.bolusStopped && !bolusCmd.failed && !isTimeout && !BolusProgressData.stopPressed) {
                    SystemClock.sleep(100)
                    if (System.currentTimeMillis() - danaPump.bolusProgressLastTimeStamp > 15 * 1000L) {
                        danaPump.bolusStopped = true
                        danaPump.bolusStopForced = true
                        isTimeout = true
                        failReason = "通信超时"
                        localAapsLogger.error(LTag.PUMP, "大剂量尝试 $retryCount：$failReason")
                    }
                }
                when {
                    bolusCmd.failed -> {
                        failReason = "泵拒绝指令"
                        localAapsLogger.error(LTag.PUMP, "大剂量尝试 $retryCount：$failReason")
                        retryCount++
                        SystemClock.sleep(RETRY_INTERVAL_MS)
                    }
                    isTimeout || danaPump.bolusStopForced -> {
                        localAapsLogger.error(LTag.PUMP, "大剂量尝试 $retryCount：$failReason")
                        retryCount++
                        SystemClock.sleep(RETRY_INTERVAL_MS)
                    }
                    else -> {
                        if (postBolusAmountCheck(amount)) {
                            isFinalSuccess = true
                            t.insulin = amount
                            localAapsLogger.debug(LTag.PUMP, "大剂量尝试 $retryCount：成功")
                        } else {
                            failReason = "剂量不符"
                            localAapsLogger.error(LTag.PUMP, "大剂量尝试 $retryCount：$failReason")
                            retryCount++
                            SystemClock.sleep(RETRY_INTERVAL_MS)
                        }
                    }
                }
            }
            if (isFinalSuccess) {
                // 明确使用完整类名+资源ID
                uiInteraction.addNotification(
                    Notification.BOLUS_SUCCESS,
                    rh.getString(R.string.bolus_ok) + "（${amount}U）",
                    Notification.NORMAL
                )
            } else {
                t.insulin = 0.0
                danaPump.bolusAmountToBeDelivered = 0.0
                // 明确使用完整类名+资源ID
                uiInteraction.addNotification(
                    Notification.BOLUS_FAILED,
                    rh.getString(R.string.bolus_failed) + "（重试$MAX_RETRY_COUNT次失败：$failReason）",
                    Notification.URGENT
                )
            }
        }
        danaPump.isBolusing = false
        SystemClock.sleep(300)
        danaPump.bolusingTreatment = null
        commandQueue.readStatus(
            if (isFinalSuccess) rh.getString(R.string.bolus_ok) else rh.getString(R.string.bolus_failed),
            null
        )
        return isFinalSuccess
    }

    private fun preBolusSafetyCheck(targetAmount: Double): Boolean {
        if (!isConnected) {
            localAapsLogger.error(LTag.PUMP, "前置校验失败：泵已断开连接")
            return false
        }
        if (danaPump.isBolusing) {
            localAapsLogger.error(LTag.PUMP, "前置校验失败：泵正在执行其他大剂量")
            return false
        }
        val lastBolusTime = danaPump.lastBolusTime
        if (System.currentTimeMillis() - lastBolusTime < 5000L) {
            localAapsLogger.error(LTag.PUMP, "前置校验失败：5秒内已有注射记录")
            return false
        }
        if (targetAmount <= 0 || targetAmount > danaPump.maxBolus) {
            localAapsLogger.error(LTag.PUMP, "前置校验失败：剂量超出范围")
            return false
        }
        return true
    }

    private fun postBolusAmountCheck(targetAmount: Double): Boolean {
        mSerialIOThread?.sendMessage(MsgStatusBolusExtended(injector))
        SystemClock.sleep(500)
        val actualAmount = danaPump.lastBolusAmount
        return abs(actualAmount - targetAmount) <= BOLUS_TOLERANCE
    }

    override fun highTempBasal(percent: Int, durationInMinutes: Int): Boolean = false

    override fun tempBasalShortDuration(percent: Int, durationInMinutes: Int): Boolean = false

    override fun updateBasalsInPump(profile: Profile): Boolean {
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.updatingbasalrates)))
        val basal = danaPump.buildDanaRProfileRecord(profile)
        val msgSet = MsgSetSingleBasalProfile(injector, basal)
        mSerialIOThread?.sendMessage(msgSet)
        danaPump.lastSettingsRead = 0
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
