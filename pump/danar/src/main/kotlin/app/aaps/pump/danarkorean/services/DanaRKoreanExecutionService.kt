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
    
    // 改为新增成员变量，避免重写final父类属性
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
                return@Runnable  // 设备未找到
            }
            try {
                mRfcommSocket?.connect()
            } catch (e: IOException) {
                if (e.message?.contains("socket closed") == true) {
                    aapsLogger.error("连接异常", e)
                }
            }
            if (isConnected) {
                mSerialIOThread?.disconnect("重建SerialIOThread")
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
                aapsLogger.debug(LTag.PUMP, "泵时间差: $timeDiff 秒")
                if (abs(timeDiff) > 10) {
                    waitForWholeMinute()
                    mSerialIOThread?.sendMessage(MsgSetTime(injector, dateUtil.now() + T.secs(10).msecs()))
                    mSerialIOThread?.sendMessage(MsgSettingPumpTime(injector))
                    timeDiff = (danaPump.pumpTime - System.currentTimeMillis()) / 1000L
                    aapsLogger.debug(LTag.PUMP, "调整后泵时间差: $timeDiff 秒")
                }
                danaPump.lastSettingsRead = now
            }
            
            rxBus.send(EventDanaRNewStatus())
            rxBus.send(EventInitializationChanged())
            
            if (danaPump.dailyTotalUnits > danaPump.maxDailyTotalUnits * Constants.dailyLimitWarning) {
                aapsLogger.debug(LTag.PUMP, "接近每日限额: " + danaPump.dailyTotalUnits + "/" + danaPump.maxDailyTotalUnits)
                if (System.currentTimeMillis() > localLastApproachingDailyLimit + 30 * 60 * 1000) {
                    uiInteraction.addNotification(Notification.APPROACHING_DAILY_LIMIT, rh.gs(R.string.approachingdailylimit), Notification.URGENT)
                    pumpSync.insertAnnouncement(
                        rh.gs(R.string.approachingdailylimit) + ": " + danaPump.dailyTotalUnits + "/" + danaPump.maxDailyTotalUnits + "U",
                        null,
                        PumpType.DANA_R_KOREAN,
                        danaRKoreanPlugin.serialNumber()
                    )
                    localLastApproachingDailyLimit = System.currentTimeMillis()
                }
            }
            doSanityCheck()
        } catch (e: Exception) {
            aapsLogger.error("未处理异常", e)
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
        // 封装单次大剂量尝试逻辑
        fun attemptBolus(attempt: Int): Boolean {
            // 检查连接状态，如未连接则尝试连接
            if (!isConnected) {
                aapsLogger.debug(LTag.PUMP, "大剂量尝试 $attempt: 未连接，尝试连接...")
                connect()
                
                // 等待连接完成（最多5秒超时）
                var waitTime = 0
                while (!isConnected && waitTime < 50) {
                    SystemClock.sleep(100)
                    waitTime++
                }
                
                if (!isConnected) {
                    aapsLogger.error(LTag.PUMP, "大剂量尝试 $attempt: 连接失败")
                    return false
                }
            }

            // 检查是否需要停止
            if (BolusProgressData.stopPressed) {
                aapsLogger.debug(LTag.PUMP, "大剂量尝试 $attempt: 已按下停止按钮，终止操作")
                return false
            }

            // 初始化泵状态
            danaPump.bolusingTreatment = t
            danaPump.bolusDone = false
            val start = MsgBolusStart(injector, amount)
            danaPump.bolusStopped = false
            danaPump.bolusStopForced = false

            // 记录碳水化合物（如有）
            if (carbs > 0) {
                mSerialIOThread?.sendMessage(MsgSetCarbsEntry(injector, carbTimeStamp, carbs))
            }

            // 处理零剂量情况
            if (amount <= 0) {
                aapsLogger.debug(LTag.PUMP, "大剂量尝试 $attempt: 剂量为零，无需执行")
                return true
            }

            // 发送大剂量指令
            danaPump.bolusAmountToBeDelivered = amount
            if (danaPump.bolusStopped) {
                aapsLogger.debug(LTag.PUMP, "大剂量尝试 $attempt: 发送前已停止")
                t.insulin = 0.0
                return false
            }

            mSerialIOThread?.sendMessage(start)
            
            // 等待执行结果（15秒超时）
            val startTime = System.currentTimeMillis()
            while (!danaPump.bolusStopped && !start.failed) {
                if (System.currentTimeMillis() - startTime > 15 * 1000L) {
                    aapsLogger.error(LTag.PUMP, "大剂量尝试 $attempt: 超时未完成")
                    start.failed = true
                    break
                }
                SystemClock.sleep(100)
            }

            // 检查执行结果
            if (start.failed || danaPump.bolusStopForced) {
                aapsLogger.error(LTag.PUMP, "大剂量尝试 $attempt: 执行失败（强制停止: ${danaPump.bolusStopForced}）")
                return false
            }

            aapsLogger.debug(LTag.PUMP, "大剂量尝试 $attempt: 执行成功")
            SystemClock.sleep(300) // 等待泵确认
            return true
        }

        // 首次尝试
        val firstAttemptSuccess = attemptBolus(1)
        if (firstAttemptSuccess) {
            commandQueue.readStatus(rh.gs(app.aaps.core.ui.R.string.bolus_ok), null)
            return true
        }

        // 首次失败，清理状态后重试一次
        aapsLogger.debug(LTag.PUMP, "首次尝试失败，开始第二次尝试...")
        mSerialIOThread?.disconnect("首次大剂量失败后重试")
        danaPump.bolusStopped = true
        danaPump.bolusStopForced = false

        // 第二次尝试
        val secondAttemptSuccess = attemptBolus(2)
        if (!secondAttemptSuccess) {
            commandQueue.readStatus(rh.gs(app.aaps.core.ui.R.string.bolus_failed), null)
        }
        return secondAttemptSuccess
        }

    override fun highTempBasal(percent: Int, durationInMinutes: Int): Boolean = false

    override fun tempBasalShortDuration(percent: Int, durationInMinutes: Int): Boolean = false

    override fun updateBasalsInPump(profile: Profile): Boolean {
        if (!isConnected) return false
        rxBus.send(EventPumpStatusChanged(rh.gs(R.string.updatingbasalrates)))
        val basal: Array<Double> = danaPump.buildDanaRProfileRecord(profile)
        val msgSet = MsgSetSingleBasalProfile(injector, basal)
        mSerialIOThread?.sendMessage(msgSet)
        danaPump.lastSettingsRead = 0 // 强制重新读取设置
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
