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

    // 新增：声明缺失的变量 bolStopped
    var bolStopped: Boolean = false
    var bolusStopForced: Boolean = false

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
                return@Runnable  // Device not found
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
                    danaPump.reset()
                    rxBus.send(EventDanaRNewStatus())
                    rxBus.send(EventInitializationChanged())
                    return
                }
                var timeDiff: Long = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                aapsLogger.debug(LTag.PUMP, "Pump time difference: $timeDiff seconds")
                if (abs(timeDiff) > 10) {
                    waitForWholeMinute() 
                    mSerialIOThread?.sendMessage(MsgSetTime(injector, dateUtil.now() + T.secs(10).msecs()))
                    mSerialIOThread?.sendMessage(MsgSettingPumpTime(injector))
                    timeDiff = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                    aapsLogger.debug(LTag.PUMP, "Pump time difference: $timeDiff seconds")
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
            aapsLogger.error("Unhandled exception", e)
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

    override fun bolus(amount: Double, carbs: Int, carbTimeStamp: Long, t: EventOverviewBolusProgress.Treatment): Boolean {
        // 1. 新增：先检查是否有正在执行的大剂量，避免并发重复执行
        if (danaPump.bolusingTreatment != null && danaPump.bolusingTreatment != t) {
            aapsLogger.warn(LTag.PUMP, "Another bolus is in progress, skip current request")
            return false
        }
        if (!isConnected) return false
        if (BolusProgressData.stopPressed) return false
        
        danaPump.bolusingTreatment = t
        danaPump.bolusDone = false // 初始化：标记大剂量未完成
        danaPump.bolusAmountToBeDelivered = amount // 同步待输注剂量到泵模型
        val MAX_RETRIES = 1 // 大剂量失败后最多重试1次
        var retryCount = 0
        var isSuccess = false
        val RETRY_DELAY = 1500L // 重试前等待1.5秒（给泵/通信恢复时间）

        while (retryCount <= MAX_RETRIES && !isSuccess) {
            val start = MsgBolusStart(injector, amount)
            // 重置单次重试的状态变量
            bolStopped = false
            bolusStopForced = false
            start.failed = false

            if (carbs > 0) {
                mSerialIOThread?.sendMessage(MsgSetCarbsEntry(injector, carbTimeStamp, carbs))
            }

            if (amount > 0) {
                if (bolStopped) {
                    aapsLogger.debug(LTag.PUMP, "Bolus stopped before sending command, skip")
                    t.insulin = 0.0
                    retryCount++
                    continue
                }

                // 发送大剂量启动指令
                mSerialIOThread?.sendMessage(start)

                // 等待大剂量执行完成/超时/中断
                val timeoutTime = System.currentTimeMillis() + 30 * 1000L
                while (!bolStopped && !start.failed && System.currentTimeMillis() < timeoutTime) {
                    SystemClock.sleep(100)
                    // 检查通信是否中断（15秒无进度更新则强制停止）
                    if (System.currentTimeMillis() - danaPump.bolusProgressLastTimeStamp > 15 * 1000L) {
                        bolStopped = true
                        bolusStopForced = true
                        start.failed = true
                        aapsLogger.debug(LTag.PUMP, "Communication stopped during bolus, force stop")
                        // 发送大剂量停止指令（确保泵实际停止输注）
                        mSerialIOThread?.sendMessage(MsgBolusStart(injector, 0.0)) 
                    }
                }

                // 处理单次重试结果
                if (start.failed) {
                    if (retryCount < MAX_RETRIES) {
                        aapsLogger.debug(LTag.PUMP, "Bolus failed, retrying (attempt ${retryCount + 1})")
                        retryCount++
                        SystemClock.sleep(RETRY_DELAY)
                    } else {
                        isSuccess = false
                        t.insulin = 0.0
                        danaPump.bolusDone = true // 失败也标记为“完成”，避免阻塞后续操作
                        aapsLogger.error(LTag.PUMP, "Bolus failed after $MAX_RETRIES retries")
                        // 2. 新增：失败时通知命令队列，避免重复重试
                        commandQueue.completeCommand(
                            Command.CommandType.BOLUS,
                            PumpEnactResult.success(false, "Bolus failed after retries")
                        )
                        // 发送“大剂量失败”事件
                        rxBus.send(EventOverviewBolusProgress(t, EventOverviewBolusProgress.Status.FAILED))
                    }
                } else {
                    isSuccess = true
                    t.insulin = amount
                    danaPump.bolusDone = true // 3. 关键修复：成功后标记大剂量已完成
                    aapsLogger.debug(LTag.PUMP, "Bolus successful, delivered $amount U")
                    // 4. 关键修复：通知命令队列“大剂量命令已完成”，避免重复执行
                    commandQueue.completeCommand(
                        Command.CommandType.BOLUS,
                        PumpEnactResult.success(true, "Bolus delivered: $amount U")
                    )
                    // 5. 关键修复：发送“大剂量完成”事件，同步全系统状态
                    rxBus.send(EventOverviewBolusProgress(t, EventOverviewBolusProgress.Status.COMPLETED))
                }
            }
        }

        // 清理：无论成功/失败，都重置执行中的治疗记录
        SystemClock.sleep(300)
        danaPump.bolusingTreatment = null
        // 读取泵状态确认结果（仅做状态同步，不触发命令重试）
        commandQueue.readStatus(rh.gs(app.aaps.core.ui.R.string.bolus_ok), null)
        return isSuccess
    }

    override fun highTempBasal(percent: Int, durationInMinutes: Int): Boolean = false

    override fun tempBasalShortDuration(percent: Int, durationInMinutes: Int): Boolean = false

    override fun updateBasalsInPump(profile: Profile): Boolean {
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.updatingbasalrates)))
        val basal: Array<Double> = danaPump.buildDanaRProfileRecord(profile)
        val msgSet = MsgSetSingleBasalProfile(injector, basal)
        mSerialIOThread?.sendMessage(msgSet)
        danaPump.lastSettingsRead = 0 // force read full settings
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
