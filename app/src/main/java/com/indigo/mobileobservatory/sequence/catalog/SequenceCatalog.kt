package com.indigo.mobileobservatory.sequence.catalog

import com.indigo.mobileobservatory.sequence.NinaNode
import com.indigo.mobileobservatory.sequence.NinaValue
import com.indigo.mobileobservatory.sequence.SequenceSlot
import com.indigo.mobileobservatory.sequence.putExpression

object SequenceCatalog {
    val types: List<SequenceTypeSpec> by lazy { buildCatalog() }

    fun spec(id: String): SequenceTypeSpec? = types.firstOrNull { it.id == id }

    fun specByClass(className: String): SequenceTypeSpec? = spec(className)

    fun create(id: String): NinaNode = spec(id)?.factory?.invoke()
        ?: throw IllegalArgumentException("Unknown sequence catalog id $id")

    fun isSet(className: String): Boolean =
        spec(className)?.isSet == true || className.endsWith("Container")
}

private fun buildCatalog(): List<SequenceTypeSpec> = buildList {
    add(area("SequenceRootContainer", "序列", "Sequence", listed = false))
    add(area("StartAreaContainer", "开始", "Start", listed = false))
    add(area("TargetAreaContainer", "目标", "Targets", listed = false))
    add(area("EndAreaContainer", "结束", "End", listed = false))

    add(setSpec("SequentialContainer", "顺序指令集", "Sequential Instruction Set", SupportLevel.Execute))
    add(deepSkySpec())
    add(setSpec("ParallelContainer", "并行指令集", "Parallel Instruction Set", SupportLevel.Execute))
    add(
        setSpec(
            "ConditionalContainer",
            "条件指令集",
            "Conditional Instruction Set",
            SupportLevel.Retain,
            FieldSpec("PredicateExpression", FieldKind.Text, "条件表达式", "Predicate")
        )
    )
    add(
        setSpec(
            "LinkedTemplateContainer",
            "链接模板",
            "Linked Template",
            SupportLevel.Retain,
            FieldSpec("TemplateReference", FieldKind.Text, "模板", "Template")
        )
    )

    add(item("SequenceItem.Camera.CoolCamera", "相机", "Camera", "冷却相机", "Cool Camera", SupportLevel.Execute, SequenceDevice.CameraCooling,
        FieldSpec("Temperature", FieldKind.Expression, "目标温度", "Temperature", defaultNumber = 0.0, unit = "°C"),
        FieldSpec("Duration", FieldKind.Expression, "最短时长", "Duration", defaultNumber = 0.0, unit = "min")))
    add(item("SequenceItem.Camera.WarmCamera", "相机", "Camera", "相机回温", "Warm Camera", SupportLevel.Execute, SequenceDevice.CameraCooling,
        FieldSpec("Duration", FieldKind.Expression, "最短时长", "Duration", defaultNumber = 0.0, unit = "min")))
    add(item("SequenceItem.Camera.DewHeater", "相机", "Camera", "防结露加热", "Dew Heater", SupportLevel.Pause, SequenceDevice.DewHeater,
        FieldSpec("OnOff", FieldKind.Bool, "打开", "On", defaultBool = false)))
    add(item("SequenceItem.Camera.SetReadoutMode", "相机", "Camera", "设置读出模式", "Set Readout Mode", SupportLevel.Retain, SequenceDevice.Camera,
        FieldSpec("Mode", FieldKind.Number, "模式", "Mode", defaultNumber = 0.0)))
    add(item("SequenceItem.Camera.SetUSBLimit", "相机", "Camera", "设置 USB 限制", "Set USB Limit", SupportLevel.Pause, SequenceDevice.Camera,
        FieldSpec("USBLimit", FieldKind.Number, "USB 限制", "USB limit", defaultNumber = 40.0)))

    add(takeExposureSpec(subframe = false))
    add(takeManySpec())
    add(smartExposureSpec())
    add(takeExposureSpec(subframe = true))

    add(item("SequenceItem.FilterWheel.SwitchFilter", "滤镜轮", "Filter Wheel", "切换滤镜", "Switch Filter", SupportLevel.Execute, SequenceDevice.FilterWheel,
        FieldSpec("ComboBoxText", FieldKind.Text, "滤镜", "Filter", defaultText = "L")))

    add(item("SequenceItem.Focuser.MoveFocuserAbsolute", "电调", "Focuser", "移动电调", "Move Focuser", SupportLevel.Execute, SequenceDevice.Focuser,
        FieldSpec("Position", FieldKind.Expression, "位置", "Position", defaultNumber = 0.0)))
    add(item("SequenceItem.Focuser.MoveFocuserRelative", "电调", "Focuser", "相对移动电调", "Move Focuser Relative", SupportLevel.Execute, SequenceDevice.Focuser,
        FieldSpec("RelativePosition", FieldKind.Expression, "相对位置", "Relative position", defaultNumber = 0.0)))
    add(item("SequenceItem.Focuser.MoveFocuserByTemperature", "电调", "Focuser", "根据温度移动电调", "Move Focuser By Temperature", SupportLevel.Pause, SequenceDevice.FocuserTemperature,
        FieldSpec("Slope", FieldKind.Expression, "斜率", "Slope", defaultNumber = 1.0, integral = false),
        FieldSpec("Intercept", FieldKind.Expression, "截距", "Intercept", defaultNumber = 0.0),
        FieldSpec("Absolute", FieldKind.Bool, "绝对", "Absolute", defaultBool = false)))
    add(item("SequenceItem.Autofocus.RunAutofocus", "电调", "Focuser", "自动对焦", "Run Autofocus", SupportLevel.Execute, SequenceDevice.Focuser))

    add(item("SequenceItem.Guider.StartGuiding", "导星", "Guider", "开始导星", "Start Guiding", SupportLevel.Execute, SequenceDevice.Guider,
        FieldSpec("ForceCalibration", FieldKind.Bool, "强制校准", "Force calibration", defaultBool = false)))
    add(item("SequenceItem.Guider.StopGuiding", "导星", "Guider", "停止导星", "Stop Guiding", SupportLevel.Execute, SequenceDevice.Guider))
    add(item("SequenceItem.Guider.Dither", "导星", "Guider", "抖动", "Dither", SupportLevel.Execute, SequenceDevice.Guider))

    add(item("SequenceItem.Telescope.SlewScopeToRaDec", "望远镜", "Telescope", "指向赤经/赤纬", "Slew To Ra/Dec", SupportLevel.Execute, SequenceDevice.Mount,
        FieldSpec("RAHours", FieldKind.Number, "赤经时", "RA hours", defaultNumber = 0.0, integral = false),
        FieldSpec("DecDegrees", FieldKind.Number, "赤纬", "Dec", defaultNumber = 0.0, integral = false),
        FieldSpec("Inherited", FieldKind.Bool, "使用目标坐标", "Use target", defaultBool = false)))
    add(item("SequenceItem.Telescope.SlewScopeToAltAz", "望远镜", "Telescope", "指向高度角/方位角", "Slew To Alt/Az", SupportLevel.Execute, SequenceDevice.Mount,
        FieldSpec("Alt", FieldKind.Expression, "高度角", "Altitude", defaultNumber = 45.0, integral = false),
        FieldSpec("Az", FieldKind.Expression, "方位角", "Azimuth", defaultNumber = 0.0, integral = false)))
    add(item("SequenceItem.Telescope.SetTracking", "望远镜", "Telescope", "设置跟踪", "Set Tracking", SupportLevel.Execute, SequenceDevice.Mount,
        FieldSpec("TrackingMode", FieldKind.Number, "跟踪模式", "Tracking mode", defaultNumber = 0.0)))
    add(item("SequenceItem.Telescope.FindHome", "望远镜", "Telescope", "回零位", "Find Home", SupportLevel.Execute, SequenceDevice.Mount))
    add(item("SequenceItem.Telescope.ParkScope", "望远镜", "Telescope", "停放望远镜", "Park Scope", SupportLevel.Pause, SequenceDevice.Mount))
    add(item("SequenceItem.Telescope.UnparkScope", "望远镜", "Telescope", "解除停放望远镜", "Unpark Scope", SupportLevel.Pause, SequenceDevice.Mount))
    add(item("SequenceItem.Platesolving.Center", "望远镜", "Telescope", "指向并居中", "Slew and center", SupportLevel.Execute, SequenceDevice.Mount,
        FieldSpec("Inherited", FieldKind.Bool, "使用目标坐标", "Use target", defaultBool = true)))
    add(item("SequenceItem.Platesolving.CenterAndRotate", "望远镜", "Telescope", "指向，居中并旋转", "Slew, center and rotate", SupportLevel.Execute, SequenceDevice.Rotator,
        FieldSpec("PositionAngle", FieldKind.Expression, "位置角", "Position angle", defaultNumber = 0.0),
        FieldSpec("Inherited", FieldKind.Bool, "使用目标坐标", "Use target", defaultBool = true)))
    add(item("SequenceItem.Platesolving.SolveAndSync", "望远镜", "Telescope", "解析并同步", "Solve and Sync", SupportLevel.Execute, SequenceDevice.Mount))
    add(item("SequenceItem.Platesolving.SolveAndRotate", "望远镜", "Telescope", "解析并旋转", "Solve and Rotate", SupportLevel.Execute, SequenceDevice.Rotator,
        FieldSpec("PositionAngle", FieldKind.Expression, "位置角", "Position angle", defaultNumber = 0.0),
        FieldSpec("Inherited", FieldKind.Bool, "使用目标坐标", "Use target", defaultBool = true)))
    add(item("SequenceItem.Rotator.MoveRotatorMechanical", "旋转器", "Rotator", "转到机械角", "Rotate to mechanical angle", SupportLevel.Execute, SequenceDevice.Rotator,
        FieldSpec("MechanicalAngle", FieldKind.Expression, "机械角", "Mechanical angle", defaultNumber = 0.0)))

    add(item("SequenceItem.FlatDevice.OpenCover", "平场设备", "Flat Device", "打开平场镜头盖", "Open Flat Panel Cover", SupportLevel.Execute, SequenceDevice.Cover))
    add(item("SequenceItem.FlatDevice.CloseCover", "平场设备", "Flat Device", "关闭平场镜头盖", "Close Flat Panel Cover", SupportLevel.Execute, SequenceDevice.Cover))
    add(item("SequenceItem.FlatDevice.ToggleLight", "平场设备", "Flat Device", "切换灯光", "Toggle Light", SupportLevel.Pause, SequenceDevice.FlatPanel,
        FieldSpec("OnOff", FieldKind.Bool, "打开", "On", defaultBool = false)))
    add(item("SequenceItem.FlatDevice.SetBrightness", "平场设备", "Flat Device", "设置亮度", "Set Brightness", SupportLevel.Pause, SequenceDevice.FlatPanel,
        FieldSpec("Brightness", FieldKind.Expression, "亮度", "Brightness", defaultNumber = 50.0)))
    add(item("SequenceItem.FlatDevice.TrainedFlatExposure", "平场设备", "Flat Device", "受过训练的平场曝光", "Trained Flat Exposure", SupportLevel.Retain, SequenceDevice.FlatPanel, isSet = true))
    add(item("SequenceItem.FlatDevice.TrainedDarkFlatExposure", "平场设备", "Flat Device", "经过训练的暗场曝光", "Trained Dark Flat Exposure", SupportLevel.Retain, SequenceDevice.FlatPanel, isSet = true))
    add(item("SequenceItem.FlatDevice.AutoExposureFlat", "平场设备", "Flat Device", "自动调节平场曝光", "Auto Exposure Flat", SupportLevel.Retain, SequenceDevice.FlatPanel, isSet = true))
    add(item("SequenceItem.FlatDevice.AutoBrightnessFlat", "平场设备", "Flat Device", "自动调节平场板亮度", "Auto Brightness Flat", SupportLevel.Retain, SequenceDevice.FlatPanel, isSet = true))
    add(item("SequenceItem.FlatDevice.SkyFlat", "平场设备", "Flat Device", "暮光天光平场", "Sky Flat", SupportLevel.Retain, SequenceDevice.FlatPanel, isSet = true))

    add(item("SequenceItem.Utility.Annotation", "工具", "Utility", "批注", "Annotation", SupportLevel.Execute, extraFields = arrayOf(
        FieldSpec("Text", FieldKind.Text, "文字", "Text", defaultText = ""))))
    add(item("SequenceItem.Utility.MessageBox", "工具", "Utility", "消息框", "Message Box", SupportLevel.Execute, extraFields = arrayOf(
        FieldSpec("Text", FieldKind.Text, "文字", "Text", defaultText = ""))))
    add(item("SequenceItem.Utility.WaitForTimeSpan", "工具", "Utility", "等待时间", "Wait for Time Span", SupportLevel.Execute, extraFields = arrayOf(
        FieldSpec("Time", FieldKind.Expression, "秒", "Seconds", defaultNumber = 60.0, min = 1.0))))
    add(waitForTimeSpec())
    add(waitAltitudeSpec("WaitForAltitude", "等待到达高度", "Wait for Altitude", 30.0))
    add(waitAltitudeSpec("WaitUntilAboveHorizon", "等待至目标高于地平线", "Wait until Above horizon", 0.0))
    add(sunMoonWait("WaitForSunAltitude", "等待太阳达到高度", "Wait for Sun Altitude"))
    add(sunMoonWait("WaitForMoonAltitude", "等待月球达到高度", "Wait for Moon Altitude"))
    add(item("SequenceItem.Utility.WaitUntil", "工具", "Utility", "等到条件成立", "Wait Until", SupportLevel.Retain, extraFields = arrayOf(
        FieldSpec("Predicate", FieldKind.Text, "条件表达式", "Predicate"))))
    add(item("SequenceItem.Utility.ExternalScript", "工具", "Utility", "外部脚本", "External Script", SupportLevel.Retain, extraFields = arrayOf(
        FieldSpec("Script", FieldKind.Text, "路径", "Path"))))
    add(item("SequenceItem.Utility.SaveSequence", "工具", "Utility", "保存序列", "Save Sequence", SupportLevel.Retain, extraFields = arrayOf(
        FieldSpec("FilePath", FieldKind.Text, "路径", "Path"))))
    add(item("SequenceItem.Utility.LoadImagingLayout", "工具", "Utility", "加载成像布局", "Load Imaging Layout", SupportLevel.Retain, extraFields = arrayOf(
        FieldSpec("FilePath", FieldKind.Text, "路径", "Path"))))

    add(item("SequenceItem.Dome.OpenDomeShutter", "圆顶", "Dome", "打开圆顶天窗", "Open Dome Shutter", SupportLevel.Retain, SequenceDevice.Dome))
    add(item("SequenceItem.Dome.CloseDomeShutter", "圆顶", "Dome", "关闭圆顶天窗", "Close Dome Shutter", SupportLevel.Retain, SequenceDevice.Dome))
    add(item("SequenceItem.Dome.ParkDome", "圆顶", "Dome", "停放圆顶", "Park Dome", SupportLevel.Retain, SequenceDevice.Dome))
    add(item("SequenceItem.Dome.FindHomeDome", "圆顶", "Dome", "圆顶回零位", "Find Dome Home", SupportLevel.Retain, SequenceDevice.Dome))
    add(item("SequenceItem.Dome.SlewDomeAzimuth", "圆顶", "Dome", "转到方位角", "Slew Dome Azimuth", SupportLevel.Retain, SequenceDevice.Dome,
        FieldSpec("Azimuth", FieldKind.Expression, "方位角", "Azimuth", defaultNumber = 0.0)))
    add(item("SequenceItem.Dome.EnableDomeSynchronization", "圆顶", "Dome", "启用圆顶跟随", "Enable Dome Sync", SupportLevel.Retain, SequenceDevice.Dome))
    add(item("SequenceItem.Dome.DisableDomeSynchronization", "圆顶", "Dome", "关闭圆顶跟随", "Disable Dome Sync", SupportLevel.Retain, SequenceDevice.Dome))
    add(item("SequenceItem.Dome.SynchronizeDome", "圆顶", "Dome", "同步圆顶", "Synchronize Dome", SupportLevel.Retain, SequenceDevice.Dome))

    add(item("SequenceItem.SafetyMonitor.WaitUntilSafe", "安全监视器", "Safety Monitor", "等待到安全", "Wait Until Safe", SupportLevel.Retain, SequenceDevice.SafetyMonitor))
    add(item("SequenceItem.Switch.SetSwitchValue", "开关", "Switch", "设置开关值", "Set Switch Value", SupportLevel.Retain, SequenceDevice.Switch,
        FieldSpec("Value", FieldKind.Expression, "值", "Value", defaultNumber = 0.0)))

    add(item("SequenceItem.Connect.ConnectEquipment", "连接", "Connect", "连接设备", "Connect Equipment", SupportLevel.Retain))
    add(item("SequenceItem.Connect.DisconnectEquipment", "连接", "Connect", "断开设备", "Disconnect Equipment", SupportLevel.Retain))
    add(item("SequenceItem.Connect.ConnectAllEquipment", "连接", "Connect", "连接全部设备", "Connect All Equipment", SupportLevel.Retain))
    add(item("SequenceItem.Connect.DisconnectAllEquipment", "连接", "Connect", "断开全部设备", "Disconnect All Equipment", SupportLevel.Retain))
    add(item("SequenceItem.Connect.SwitchProfile", "连接", "Connect", "切换配置", "Switch Profile", SupportLevel.Retain))

    add(item("SequenceItem.Expressions.Constant", "符号", "Symbols", "定义常量", "Define Constant", SupportLevel.Retain))
    add(item("SequenceItem.Expressions.Variable", "符号", "Symbols", "定义变量", "Define Variable", SupportLevel.Retain))
    add(item("SequenceItem.Expressions.GlobalConstant", "符号", "Symbols", "定义全局常量", "Define Global Constant", SupportLevel.Retain))
    add(item("SequenceItem.Expressions.GlobalVariable", "符号", "Symbols", "定义全局变量", "Define Global Variable", SupportLevel.Retain))
    add(item("SequenceItem.Expressions.ResetVariable", "符号", "Symbols", "设置变量", "Set Variable", SupportLevel.Retain))
    add(item("SequenceItem.Expressions.ResetVariableToDate", "符号", "Symbols", "变量设为日期", "Set Variable to Date/Time", SupportLevel.Retain))

    add(condition("LoopCondition", "循环指定次数", "Loop For Iterations", SupportLevel.Execute,
        FieldSpec("Iterations", FieldKind.Expression, "次数", "Iterations", defaultNumber = 2.0, min = 1.0),
        extra = { it["CompletedIterations"] = NinaValue.Num(0.0, true) }))
    add(altitudeCondition("AltitudeCondition", "循环至目标低于高度", "Loop until Altitude Below", 30.0, SupportLevel.Execute))
    add(timeCondition())
    add(condition("TimeSpanCondition", "循环一段时间", "Loop for Time Span", SupportLevel.Execute,
        FieldSpec("Hours", FieldKind.Number, "时", "Hours", defaultNumber = 0.0),
        FieldSpec("Minutes", FieldKind.Number, "分钟", "Minutes", defaultNumber = 1.0),
        FieldSpec("Seconds", FieldKind.Number, "秒", "Seconds", defaultNumber = 0.0)))
    add(altitudeCondition("AboveHorizonCondition", "目标高于地平线时循环", "Loop while Altitude Above Horizon", 0.0, SupportLevel.Execute))
    add(sunMoonCondition("SunAltitudeCondition", "循环至太阳到达高度", "Loop until Sun Altitude", SupportLevel.Execute))
    add(sunMoonCondition("MoonAltitudeCondition", "循环至月球到达高度", "Loop until Moon Altitude", SupportLevel.Execute))
    add(condition("MoonIlluminationCondition", "月相", "Moon Illumination", SupportLevel.Execute,
        FieldSpec("UserMoonIllumination", FieldKind.Expression, "照度", "Illumination", defaultNumber = 0.0),
        FieldSpec("Comparator", FieldKind.Number, "比较", "Comparator", defaultNumber = 3.0)))
    add(condition("SafetyMonitorCondition", "安全时循环", "Loop while Safe", SupportLevel.Retain))
    add(condition("LoopWhileUnsafe", "不安全时循环", "Loop while Unsafe", SupportLevel.Retain))
    add(condition("LoopWhile", "条件成立时循环", "Loop While", SupportLevel.Retain,
        FieldSpec("PredicateExpression", FieldKind.Text, "条件表达式", "Predicate")))

    add(trigger("Trigger.MeridianFlip.MeridianFlipTrigger", "触发器", "Triggers", "中天翻转", "Meridian Flip", SupportLevel.Execute, SequenceDevice.Mount))
    add(trigger("Trigger.Platesolving.CenterAfterDriftTrigger", "触发器", "Triggers", "偏移后居中", "Center After Drift", SupportLevel.Execute, SequenceDevice.Mount,
        listOf("Center"),
        FieldSpec("DistanceArcMinutes", FieldKind.Expression, "偏移角分", "Distance arcmin", defaultNumber = 10.0),
        FieldSpec("AfterExposures", FieldKind.Expression, "每 N 张", "After exposures", defaultNumber = 1.0, min = 1.0)))
    add(trigger("Trigger.Autofocus.AutofocusAfterExposures", "电调", "Focuser", "拍摄 # 张后自动对焦", "AF After # Exposures", SupportLevel.Execute, SequenceDevice.Focuser,
        listOf("RunAutofocus"),
        FieldSpec("AfterExposures", FieldKind.Expression, "每 N 张", "After exposures", defaultNumber = 5.0, min = 1.0)))
    add(trigger("Trigger.Autofocus.AutofocusAfterFilterChange", "电调", "Focuser", "切换滤镜后自动对焦", "AF After Filter Change", SupportLevel.Execute, SequenceDevice.Focuser,
        listOf("RunAutofocus")))
    add(trigger("Trigger.Autofocus.AutofocusAfterHFRIncreaseTrigger", "电调", "Focuser", "HFR增加后自动对焦", "AF After HFR Increase", SupportLevel.Execute, SequenceDevice.Focuser,
        listOf("RunAutofocus"),
        FieldSpec("Amount", FieldKind.Expression, "百分比", "Percent", defaultNumber = 5.0),
        FieldSpec("SampleSize", FieldKind.Expression, "样本大小", "Sample size", defaultNumber = 10.0),
        FieldSpec("TrendPerFilter", FieldKind.Bool, "按滤镜", "Per filter", defaultBool = true)))
    add(trigger("Trigger.Autofocus.AutofocusAfterTemperatureChangeTrigger", "电调", "Focuser", "温度变化后自动对焦", "AF After Temperature Change", SupportLevel.Execute, SequenceDevice.Focuser,
        listOf("RunAutofocus"),
        FieldSpec("Amount", FieldKind.Expression, "温度", "Temperature", defaultNumber = 5.0, unit = "°C")))
    add(trigger("Trigger.Autofocus.AutofocusAfterTimeTrigger", "电调", "Focuser", "特定时间后自动对焦", "AF After Time", SupportLevel.Execute, SequenceDevice.Focuser,
        listOf("RunAutofocus"),
        FieldSpec("Amount", FieldKind.Expression, "分钟", "Minutes", defaultNumber = 30.0)))
    add(trigger("Trigger.Guider.DitherAfterExposures", "导星", "Guider", "曝光之后抖动", "Dither after Exposures", SupportLevel.Execute, SequenceDevice.Guider,
        listOf("Dither"),
        FieldSpec("AfterExposures", FieldKind.Expression, "每 N 张", "After exposures", defaultNumber = 3.0, min = 0.0, max = 32.0)))
    add(trigger("Trigger.Guider.RestoreGuiding", "导星", "Guider", "恢复导星", "Restore Guiding", SupportLevel.Execute, SequenceDevice.Guider,
        listOf("StartGuiding")))
    add(trigger("Trigger.MeridianFlip.ProgrammableMeridianFlipTrigger", "触发器", "Triggers", "可编程中天翻转", "Programmable Meridian Flip", SupportLevel.Retain, SequenceDevice.Mount))
    add(trigger("Trigger.Connect.ReconnectTrigger", "连接", "Connect", "重连设备", "Reconnect Equipment", SupportLevel.Retain))
    add(trigger("Trigger.Connect.ReconnectOnDownloadFailure", "连接", "Connect", "下载失败时重连相机", "Reconnect Camera On Download Failure", SupportLevel.Retain, SequenceDevice.Camera))
    add(trigger("Trigger.Dome.SynchronizeDomeTrigger", "圆顶", "Dome", "同步圆顶 (触发器)", "Synchronize Dome Trigger", SupportLevel.Retain, SequenceDevice.Dome))
    add(trigger("Trigger.SafetyMonitor.TriggerOnUnsafe", "安全监视器", "Safety Monitor", "不安全时触发", "Trigger On Unsafe", SupportLevel.Retain, SequenceDevice.SafetyMonitor))
    add(trigger("Trigger.Utility.CustomTrigger", "触发器", "Triggers", "自定义触发器", "Custom Trigger", SupportLevel.Retain))
}

private fun item(
    path: String,
    groupZh: String,
    groupEn: String,
    titleZh: String,
    titleEn: String,
    level: SupportLevel,
    device: SequenceDevice? = null,
    vararg extraFields: FieldSpec,
    isSet: Boolean = false
): SequenceTypeSpec {
    val id = path.substringAfterLast('.')
    val type = ninaType(path)
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Item,
        groupZh = groupZh,
        groupEn = groupEn,
        titleZh = titleZh,
        titleEn = titleEn,
        level = level,
        fields = extraFields.toList(),
        device = device,
        isSet = isSet
    ) {
        if (isSet) {
            catalogContainer(type, titleZh)
        } else {
            val fields = LinkedHashMap<String, NinaValue>()
            extraFields.forEach { it.write(fields) }
            catalogInstruction(type, fields)
        }
    }
}

private fun condition(
    id: String,
    titleZh: String,
    titleEn: String,
    level: SupportLevel,
    vararg extraFields: FieldSpec,
    extra: (LinkedHashMap<String, NinaValue>) -> Unit = {}
): SequenceTypeSpec {
    val type = ninaType("Conditions.$id")
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Condition,
        groupZh = "循环条件",
        groupEn = "Conditions",
        titleZh = titleZh,
        titleEn = titleEn,
        level = level,
        fields = extraFields.toList()
    ) {
        val fields = LinkedHashMap<String, NinaValue>()
        extraFields.forEach { it.write(fields) }
        extra(fields)
        catalogInstruction(type, fields)
    }
}

private fun trigger(
    path: String,
    groupZh: String,
    groupEn: String,
    titleZh: String,
    titleEn: String,
    level: SupportLevel,
    device: SequenceDevice? = null,
    runnerIds: List<String> = emptyList(),
    vararg extraFields: FieldSpec
): SequenceTypeSpec {
    val id = path.substringAfterLast('.')
    val type = ninaType(path)
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Trigger,
        groupZh = groupZh,
        groupEn = groupEn,
        titleZh = titleZh,
        titleEn = titleEn,
        level = level,
        fields = extraFields.toList(),
        device = device
    ) {
        val fields = LinkedHashMap<String, NinaValue>()
        extraFields.forEach { it.write(fields) }
        val items = runnerIds.map { SequenceCatalog.create(it) }
        fields["TriggerRunner"] = NinaValue.Obj(triggerRunner(items))
        catalogInstruction(type, fields)
    }
}

private fun area(id: String, titleZh: String, titleEn: String, listed: Boolean): SequenceTypeSpec {
    val type = ninaType("Container.$id")
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "* 指令集 *",
        groupEn = "Instruction sets",
        titleZh = titleZh,
        titleEn = titleEn,
        level = SupportLevel.Execute,
        isSet = true,
        listed = listed,
        hiddenByDefault = false
    ) { catalogContainer(type, titleZh) }
}

private fun setSpec(
    id: String,
    titleZh: String,
    titleEn: String,
    level: SupportLevel,
    vararg extraFields: FieldSpec
): SequenceTypeSpec {
    val type = ninaType("Container.$id")
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "* 指令集 *",
        groupEn = "Instruction sets",
        titleZh = titleZh,
        titleEn = titleEn,
        level = level,
        fields = extraFields.toList(),
        isSet = true
    ) { catalogContainer(type, titleZh) }
}

private fun deepSkySpec(): SequenceTypeSpec {
    val type = ninaType("Container.DeepSkyObjectContainer")
    return SequenceTypeSpec(
        id = "DeepSkyObjectContainer",
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "* 指令集 *",
        groupEn = "Instruction sets",
        titleZh = "深空目标指令集",
        titleEn = "Deep Sky Object Instruction Set",
        level = SupportLevel.Execute,
        fields = listOf(
            FieldSpec("RAHours", FieldKind.Number, "赤经时", "RA hours", defaultNumber = 0.0, integral = false),
            FieldSpec("DecDegrees", FieldKind.Number, "赤纬", "Dec", defaultNumber = 0.0, integral = false),
            FieldSpec(
                "PositionAngle",
                FieldKind.Number,
                "位置角",
                "Position angle",
                defaultNumber = 0.0,
                integral = false,
                unit = "°"
            )
        ),
        isSet = true
    ) {
        val coordinates = inputCoordinates()
        val target = catalogInstruction(
            "NINA.Astrometry.InputTarget, NINA.Astrometry",
            linkedMapOf(
                "TargetName" to NinaValue.Text("Target"),
                "PositionAngle" to NinaValue.Num(0.0, true),
                "Expanded" to NinaValue.Bool(true),
                "InputCoordinates" to NinaValue.Obj(coordinates)
            )
        )
        val node = catalogContainer(type, "Target")
        node.fields["Target"] = NinaValue.Obj(target)
        node.fields["ExposureInfoListExpanded"] = NinaValue.Bool(false)
        node
    }
}

private fun takeExposureSpec(subframe: Boolean): SequenceTypeSpec {
    val id = if (subframe) "TakeSubframeExposure" else "TakeExposure"
    val titleZh = if (subframe) "开始子帧曝光" else "开始曝光"
    val titleEn = if (subframe) "Take Subframe Exposure" else "Take Exposure"
    val fields = mutableListOf(
        FieldSpec("ExposureTime", FieldKind.Expression, "曝光秒", "Seconds", defaultNumber = 60.0, min = 0.0, max = 3600.0),
        FieldSpec("Gain", FieldKind.Expression, "增益", "Gain", defaultNumber = -1.0),
        FieldSpec("Offset", FieldKind.Expression, "偏置", "Offset", defaultNumber = -1.0),
        FieldSpec("ImageType", FieldKind.Text, "类型", "Image type", defaultText = "LIGHT"),
        FieldSpec("Binning", FieldKind.Text, "像素合并", "Binning", defaultText = "1x1")
    )
    if (subframe) {
        fields += FieldSpec("ROIPct", FieldKind.Expression, "ROI %", "ROI %", defaultNumber = 100.0, min = 1.0, max = 100.0)
    }
    val type = ninaType("SequenceItem.Imaging.$id")
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "相机",
        groupEn = "Camera",
        titleZh = titleZh,
        titleEn = titleEn,
        level = if (subframe) SupportLevel.Retain else SupportLevel.Execute,
        fields = fields,
        device = SequenceDevice.Camera
    ) {
        val map = LinkedHashMap<String, NinaValue>()
        fields.forEach { it.write(map) }
        map["Binning"] = NinaValue.Obj(binningMode())
        map["ExposureCount"] = NinaValue.Num(0.0, true)
        catalogInstruction(type, map)
    }
}

private fun smartExposureSpec(): SequenceTypeSpec {
    val type = ninaType("SequenceItem.Imaging.SmartExposure")
    return SequenceTypeSpec(
        id = "SmartExposure",
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "相机",
        groupEn = "Camera",
        titleZh = "智能曝光",
        titleEn = "Smart Exposure",
        level = SupportLevel.Execute,
        fields = listOf(FieldSpec("Iterations", FieldKind.Expression, "次数", "Iterations", defaultNumber = 1.0, min = 1.0)),
        device = SequenceDevice.Camera,
        isSet = true
    ) {
        val node = catalogContainer(type, "智能曝光", expanded = false)
        putExpression(node.fields, "Iterations", 1.0)
        attachChildren(node, "Items", listOf(SequenceCatalog.create("SwitchFilter"), SequenceCatalog.create("TakeExposure")))
        val loop = SequenceCatalog.create("LoopCondition")
        putExpression(loop.fields, "Iterations", 1.0)
        attachChildren(node, "Conditions", listOf(loop))
        attachChildren(node, "Triggers", listOf(SequenceCatalog.create("DitherAfterExposures")))
        node
    }
}

private fun takeManySpec(): SequenceTypeSpec {
    val type = ninaType("SequenceItem.Imaging.TakeManyExposures")
    return SequenceTypeSpec(
        id = "TakeManyExposures",
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "相机",
        groupEn = "Camera",
        titleZh = "多次曝光",
        titleEn = "Take Many Exposures",
        level = SupportLevel.Execute,
        fields = listOf(FieldSpec("Iterations", FieldKind.Expression, "次数", "Iterations", defaultNumber = 1.0, min = 1.0)),
        device = SequenceDevice.Camera,
        isSet = true
    ) {
        val node = catalogContainer(type, "多次曝光", expanded = false)
        putExpression(node.fields, "Iterations", 1.0)
        attachChildren(node, "Items", listOf(SequenceCatalog.create("TakeExposure")))
        val loop = SequenceCatalog.create("LoopCondition")
        putExpression(loop.fields, "Iterations", 1.0)
        attachChildren(node, "Conditions", listOf(loop))
        node
    }
}

private fun clockFields() = listOf(
    FieldSpec("Hours", FieldKind.Number, "时", "Hours", defaultNumber = 6.0),
    FieldSpec("Minutes", FieldKind.Number, "分", "Minutes", defaultNumber = 0.0),
    FieldSpec("Seconds", FieldKind.Number, "秒", "Seconds", defaultNumber = 0.0),
    FieldSpec("MinutesOffset", FieldKind.Number, "偏移分钟", "Offset minutes", defaultNumber = 0.0),
    FieldSpec("SelectedProvider", FieldKind.Text, "时间来源", "Time source", defaultText = "TimeProvider")
)

private fun waitForTimeSpec(): SequenceTypeSpec {
    val type = ninaType("SequenceItem.Utility.WaitForTime")
    val fields = clockFields()
    return SequenceTypeSpec(
        id = "WaitForTime",
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "工具",
        groupEn = "Utility",
        titleZh = "等待到达时刻",
        titleEn = "Wait for Time",
        level = SupportLevel.Execute,
        fields = fields
    ) {
        val map = LinkedHashMap<String, NinaValue>()
        fields.forEach { it.write(map) }
        map["SelectedProvider"] = NinaValue.Obj(timeProvider())
        catalogInstruction(type, map)
    }
}

private fun waitAltitudeSpec(id: String, titleZh: String, titleEn: String, offset: Double): SequenceTypeSpec {
    val type = ninaType("SequenceItem.Utility.$id")
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "工具",
        groupEn = "Utility",
        titleZh = titleZh,
        titleEn = titleEn,
        level = SupportLevel.Execute,
        fields = listOf(FieldSpec("Offset", FieldKind.Number, "高度", "Altitude", defaultNumber = offset, proxyPath = "Data.Offset")),
        device = SequenceDevice.Mount
    ) {
        catalogInstruction(type, linkedMapOf("Data" to waitLoopData(offset)))
    }
}

private fun sunMoonWait(id: String, titleZh: String, titleEn: String): SequenceTypeSpec {
    val type = ninaType("SequenceItem.Utility.$id")
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Item,
        groupZh = "工具",
        groupEn = "Utility",
        titleZh = titleZh,
        titleEn = titleEn,
        level = SupportLevel.Execute,
        fields = listOf(
            FieldSpec("Offset", FieldKind.Number, "高度", "Altitude", defaultNumber = 0.0, proxyPath = "Data.Offset"),
            FieldSpec("Comparator", FieldKind.Number, "比较", "Comparator", defaultNumber = 3.0, proxyPath = "Data.Comparator")
        )
    ) {
        catalogInstruction(type, linkedMapOf("Data" to waitLoopData(0.0, comparator = 3)))
    }
}

private fun altitudeCondition(id: String, titleZh: String, titleEn: String, offset: Double, level: SupportLevel): SequenceTypeSpec {
    val type = ninaType("Conditions.$id")
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Condition,
        groupZh = "循环条件",
        groupEn = "Conditions",
        titleZh = titleZh,
        titleEn = titleEn,
        level = level,
        fields = listOf(FieldSpec("Offset", FieldKind.Number, "高度", "Altitude", defaultNumber = offset, proxyPath = "Data.Offset"))
    ) {
        catalogInstruction(type, linkedMapOf("Data" to waitLoopData(offset)))
    }
}

private fun sunMoonCondition(id: String, titleZh: String, titleEn: String, level: SupportLevel): SequenceTypeSpec {
    val type = ninaType("Conditions.$id")
    return SequenceTypeSpec(
        id = id,
        type = type,
        slot = SequenceSlot.Condition,
        groupZh = "循环条件",
        groupEn = "Conditions",
        titleZh = titleZh,
        titleEn = titleEn,
        level = level,
        fields = listOf(
            FieldSpec("Offset", FieldKind.Number, "高度", "Altitude", defaultNumber = 0.0, proxyPath = "Data.Offset"),
            FieldSpec("Comparator", FieldKind.Number, "比较", "Comparator", defaultNumber = 3.0, proxyPath = "Data.Comparator")
        )
    ) {
        catalogInstruction(type, linkedMapOf("Data" to waitLoopData(0.0, comparator = 3)))
    }
}

private fun timeCondition(): SequenceTypeSpec {
    val type = ninaType("Conditions.TimeCondition")
    val fields = clockFields()
    return SequenceTypeSpec(
        id = "TimeCondition",
        type = type,
        slot = SequenceSlot.Condition,
        groupZh = "循环条件",
        groupEn = "Conditions",
        titleZh = "循环到某时刻",
        titleEn = "Loop Until Time",
        level = SupportLevel.Execute,
        fields = fields
    ) {
        val map = LinkedHashMap<String, NinaValue>()
        fields.forEach { it.write(map) }
        map["SelectedProvider"] = NinaValue.Obj(timeProvider())
        catalogInstruction(type, map)
    }
}
