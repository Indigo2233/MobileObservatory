# WF-10：持久化索引、按需加载与可采证基线

2026-10-14。本文件记录本轮实现；旧 [宽场解算计划](PHONE_WIDE_FIELD_SOLVER_IMPLEMENTATION.md) 的“首次同时构建三个桶、持久化待办”描述属于改造前基线。交互仍遵循 [交互规范](INTERACTION_GUIDELINES.md)，真实镜筒坐标、标定和到位按 [指向方案](PHONE_PLATE_SOLVE_PLAN.md) 验证。

**代码与工具可交付；≥30 组真实独立参考 WCS、真机温度/功耗/并行采集和两名新手证据没有被补造。** 本机查到的是 `emulator-5560`，数据集目录只有 README，没有合格实拍清单。索引、合成测试、模拟器采样不等于实拍准确度或续航。

## 1. 索引实现和边界

新增 [WideFieldTriangleIndex](../app/src/main/java/com/indigo/mobileobservatory/pointing/WideFieldTriangleIndex.kt)，[PhoneBrightStarCatalog](../app/src/main/java/com/indigo/mobileobservatory/pointing/PhoneBrightStarCatalog.kt) 绑定资源，[WideFieldSolver](../app/src/main/java/com/indigo/mobileobservatory/pointing/WideFieldSolver.kt) 查询实际使用的桶。

- 仍使用现有 `≤4.5` 等锚星、近邻数量 A/B/C＝12/10/8、最长边 50°/32°/22°、量化步长 0.35°。保存原来每个 key 的插入次序，匹配残差、候选预算和歧义拒绝门限保留。
- 二进制 `WFIX v1` 使用网络字节序，绑定目录几何 SHA-256、星表数、桶和生成参数；payload 另有 SHA-256。检测格式、身份、长度、排序、星点引用、重复顶点和载荷。资源缺失/损坏/过期时只重建当前桶，不拿旧坐标继续解算。
- 数据存为 `LongArray` key 和三列 `IntArray` 星表索引，避免在 APK 加载时重建成一大套三角形对象。使用同一份目录向量；候选六种方向保持原顺序。
- 目录级运行时缓存保留空间验证索引；三角形按每个 scale 的实际视场懒加载，最多缓存两个桶，越界使用 LRU 释放。局部 IMU 成功时不打开三角形资源。
- 没有把 Double 改成 Float，也没有降低科学处理门限。数组容量数字不代表峰值堆内存；并发求解暂时引用被逐出的不可变桶时，实际峰值仍需测量。

离线资源由 [generate_wide_field_index.py](../tools/generate_wide_field_index.py) 再生，无联网/第三方 Python 依赖。输入 HYG 数据及许可沿用 `THIRD_PARTY_HYG.txt`。以下数字来自实际生成输出，不是手机性能测量：

| 桶 | 行数 | 文件字节 | 运行时 key/索引数组容量字节 |
|---|---:|---:|---:|
| WF-A | 61,050 | 1,465,304 | 1,221,000 |
| WF-B | 41,587 | 998,192 | 831,740 |
| WF-C | 25,208 | 605,096 | 504,160 |
| 总文件 | 127,845 | 3,068,592 | 不同时常驻三个桶 |

[manifest.json](../app/src/main/assets/catalog/wf-index/manifest.json) 记录 CSV/几何/载荷/文件哈希、重复 key、最长 key 列表、无序物理三角形数、格式与文件大小。共享 key 包含同一个物理三角形的不同锚星排列，不宣称这些计数等于实际天空误解率。

```powershell
python tools/generate_wide_field_index.py
python tools/generate_wide_field_index.py --check
```

## 2. J2000 image-up 位置角

此前 IMU 局部分支直接把二维拟合角当成 J2000 位置角。该角是在 prior 的 ENU tangent basis 中，基底和符号都不同，不能供安装外参使用。

当前 [ImuAssistedWideFieldSolver](../app/src/main/java/com/indigo/mobileobservatory/pointing/ImuAssistedWideFieldSolver.kt) 取二维变换的 image-up 列，经过 gnomonic unproject 的切向导数投影到求解中心，获得 ENU image-up；统一 Solver 再通过 `CameraAttitudePrior.fieldRotationDeg()` 转为 J2000 PA。盲解和局部解均定义为 `atan2(up·east, up·north)`，结果归一化至 `[0,360)`。局部原拟合角只保留作明确的局部诊断。

新增 [WideFieldPositionAngleTest](../app/src/test/java/com/indigo/mobileobservatory/pointing/WideFieldPositionAngleTest.kt)，用直接 J2000 星场投影构造已知 roll，在不同地点/时刻比较局部与盲解。图像反射仍由采集入口显式转换并写入 parity，不在 Solver 静默尝试两个不同安装含义。

## 3. 实际采样接口

[WideFieldSolveDiagnostics](../app/src/main/java/com/indigo/mobileobservatory/pointing/WideFieldSolveDiagnostics.kt) 为每次求解记录 local/blind/index 耗时、候选三角形、实际验证次数、搜索 pass、搜索/常驻桶、数组容量和资源读取/回退原因。`WideFieldCaptureMetrics` 接收采集调用点的真实 camera ID、镜头、曝光/ISO/帧数、开 session/拍摄/提星耗时、parity 和已保存 FITS 路径；未知项保持 null。

[WideFieldSolveProbe](../app/src/main/java/com/indigo/mobileobservatory/pointing/WideFieldSolveProbe.kt) 默认关闭。只有应用私有 `files/wf-baseline/enabled` 文件存在时，才记录 JSONL、读取已保存图像的 SHA-256。目录最多保留当前/上一份各约 4 MiB 日志，不操作原图；导出后及时关闭。一般模式不会生成诊断流或每次复制像素。

采样包含 Android/型号、曝光中点、输入/输出 FOV、J2000 中心/PA、匹配与失败、阶段计时、ART 可用计数、Java/native 堆快照、最大已观察 Java 堆、battery 温度、charge counter/current、thermal status。电池温度与系统热等级不冒充 CPU 温度。不可读字段为 null。

`elapsedMs` 只度量 Solver；capture/extraction 由调用点分别填真实值。开启探针后的文件 hash/JSON 成本也必须在整体流水线性能实验中计入，不能拿这个 Solver 数值当成拍摄到显示全链路耗时。

## 4. 本地采集、导出和汇总

[wf_baseline.py](../tools/wf_baseline.py) 提供明确选设备的 `enable/disable/export/scaffold/summarize/sample/summarize-resources`。`run-as` 适用于可调试包；非调试发布包需以后在应用内提供等价的私有诊断导出，不绕过 Android 数据权限。

```powershell
python tools/wf_baseline.py enable --serial DEVICE_SERIAL --package com.indigo.mobileobservatory
# 在应用实际拍摄/求解。每 5–6 组导出一次，旧 M0 目录受 24 文件/300 MiB 保留上限约束。
python tools/wf_baseline.py export --serial DEVICE_SERIAL --package com.indigo.mobileobservatory --output build/reports/wf10/private --include-images
python tools/wf_baseline.py disable --serial DEVICE_SERIAL --package com.indigo.mobileobservatory
python tools/wf_baseline.py scaffold --events build/reports/wf10/private/solver-events.jsonl --output build/reports/wf10/private/manifest.json
# 为每组补真实条件、source revision、captureGroupId、独立参考及其输入/产物哈希。
python tools/wf_baseline.py summarize --events build/reports/wf10/private/solver-events.previous.jsonl build/reports/wf10/private/solver-events.jsonl --manifest build/reports/wf10/private/manifest.json --output build/reports/wf10/summary.json --require-real-gate
```

没有上一份 JSONL 时从命令删掉该参数。`scaffold` 默认 `UNVERIFIED`、独立参考 false、环境/版本未知，不能自动计为真实数据。重复文件名但内容变化不会无声覆盖已导出的图片；过期已删除图像留缺失状态。原始图和位置/时间留私有目录，不加入公开仓库。

参考要求：同一输入 SHA-256、独立的 ASTAP W08 或 astrometry.net，记录版本、参考产物 SHA-256。支持显式 J2000/ICRS JSON 结果以及 TAN/TAN-SIP WCS 的 FITS header；其他投影/未知历元被拒绝。WCS 在 WF 使用的零基 `(width/2,height/2)` 对应 FITS 一基 `+1` 求中心；image-up 是原始行向下坐标中的 `−Y`。不能直接把任意 CRVAL 或 CD2 角当作画面中心/PA。

每组清单至少填写：

```json
{
  "sampleId": "phoneA_001",
  "captureGroupId": "independent-capture-001",
  "evidenceType": "REAL_CAPTURE",
  "testedRevision": "actual-tested-source-revision",
  "image": "actual-capture.fit",
  "imageYAxis": "DOWN",
  "equivalentFocalLengthMm": 24,
  "sky": {"place": "urban", "moon": false},
  "reference": {
    "source": "ASTAP_W08",
    "version": "actual-reference-version",
    "independent": true,
    "forImageSha256": "actual-input-sha256",
    "artifact": "actual-reference.wcs",
    "artifactSha256": "actual-reference-sha256"
  }
}
```

这只是字段模板，没有提交假样本。汇总验证图像、探针事件和参考的绑定、物理设备声明、不同组和不同图像 hash、镜头/地点/月相条件。缺参考、模拟器、重复图像、未知结果、产物改变都不计入合格组。至少 30 组，四种城乡/月相各 ≥6 组、14/24/35/50 mm 覆盖和 0.5 s 限制案例分别列出缺项。

接受错误判定默认阈值：中心 0.5°、PA 2°、FOV 相对误差 10%，报告保留具体阈值；它们是本次基线分类规则，不是镜筒到位容差或精度承诺。报告列出被接受的不匹配，绝不因为没有参考把“误解率”填成 0。零数据的成功率/误解率/误差均未知；`--require-real-gate` 在缺数据时退出 2。

资源采样不依赖成功解算，可以在空闲、手机引导、同时导星/EAA/录像状态分别运行：

```powershell
python tools/wf_baseline.py sample --serial DEVICE_SERIAL --package com.indigo.mobileobservatory --phase phone_guidance --duration 10800 --interval 5 --output build/reports/wf10/private/resources.jsonl
python tools/wf_baseline.py summarize-resources --input build/reports/wf10/private/resources.jsonl --output build/reports/wf10/resource-summary.json
```

它查询实际 `dumpsys battery/meminfo/thermalservice`，保留原始温度传感器名称/类型与来源。模拟器、供电状态未知/插电、采样时长不足、断续采样、charge counter 缺失/重置以及不可读内存/温度都产生缺证据状态。两条相隔三小时的记录不能作为连续三小时实验。报告区分原始 charge counter 差值与可解释的物理设备不插电消耗；模拟器/充电数据的 `consumedMicroampHours` 为 null。阶段名称是操作者标注；与主相机/导星并发影响需要另配真实帧数/队列丢帧及实际观察，不能仅凭一个标签宣称通过。

## 5. 统计怎么解释

| 输出 | 实际意义 | 不能推断什么 |
|---|---|---|
| with/without index loads P95 | 这一轮有/无桶读取时的 Solver 耗时 | 不等于完整进程冷启动或整条拍摄 P95 |
| allocation bytes/sec | 相邻事件 ART 累计分配量差除以实际间隔 | 包含两次解算之间其他任务，不能全部归因 Solver |
| GC / blocking GC time delta | 相邻事件的 ART 累计时间变化 | 不冒充单次 GC 暂停 P95 |
| 观察到的 Java/native 堆、PSS/RSS | 被采到的时点及最大值 | 采样间尖峰可能遗漏，不宣称真实高水位 |
| battery/thermal | 真实来源可读数值，未知项 null | 电池温度不等于 CPU 温度，插电不能证明续航 |
| 成功率/误解率 | 有绑定独立参考的合格求解尝试 | 采集失败/运动拒收等进入 Solver 前的失败不在该分母；非整个观测流程成功率 |
| queue drop rate | 当前暂无可靠输入计数，保留 null | 不从 JSONL 缺行反推丢帧率 |

不同优化版本必须使用同一输入/参考与环境，区分 index 读取、无读取、IMU/盲解路径及设备状态，记录版本、P95、观察到的堆/分配/GC 和真实并发指标。新的消耗曲线不代替 ≥30 个独立星空案例，也不代替两名新手流程。

## 6. 验证与缺失证据

已执行：生成 A/B/C 资源；Python 工具 **16 项测试通过**（哈希/参考绑定、重复组、错误 PA/中心、零数据未知率、非有限输入、计数器重置、TAN 中心与 PA、模拟器/供电/短时长/断续记录不可当续航、生成可再生）。新 Kotlin codec/LRU/真实资源一致性及跨地点时间 PA 测试交由本轮统一 Gradle 执行；[探针 Android 测试](../app/src/androidTest/java/com/indigo/mobileobservatory/pointing/WideFieldBaselineInstrumentedTest.kt)只验证 opt-in/私有文件 hash/未知参考机制。

未完成的真实门槛：合格独立实拍组 **0/30**；未找到物理 Android 设备数据、3 小时不插电实验、实际器材并发/丢帧数据、未参与开发的两名新手记录。这些输出必须保持缺失，不勾选精度/续航/现场观测验收。实现与合成测试可以评审/提交，产品宣传仍须保持实验性边界。
