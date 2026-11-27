package com.indigo.mobileobservatory.solar

import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.media.MediaRename
import com.indigo.mobileobservatory.media.MediaTrash
import com.indigo.mobileobservatory.media.MediaUsageRegistry
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.ui.viewmodel.incompleteSimulationSourcesToDelete
import com.indigo.mobileobservatory.ui.viewmodel.isCurrentSolarPreview
import com.indigo.mobileobservatory.ui.viewmodel.publishSealedSimulationSource
import com.indigo.mobileobservatory.ui.viewmodel.SolarReconstructionSettingsSession
import com.indigo.mobileobservatory.ui.screens.isSolarSourceBusy
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarScanFeatureTest {
    @Test
    fun trialTimingUsesTheAcceptedFrameIntervals() {
        val cadence = SolarFrameCadence()
        repeat(20) { cadence.observe(it * 100_000_000L) }
        cadence.finishBaseline()
        cadence.checkContinuous()
        val timing = cadence.timing(SolarTrialCoverage(20, 40, 3, 18, 8, 31))
        assertEquals(10.0, timing.framesPerSecond, 0.000001)
        assertEquals(1_400L, timing.diskCrossingMs)
    }

    @Test
    fun trialCadenceRejectsAGapBelowTheLiveFrameWatchdog() {
        val cadence = SolarFrameCadence()
        repeat(20) { cadence.observe(it * 100_000_000L) }
        cadence.finishBaseline()
        cadence.checkContinuous()
        cadence.observe(2_100_000_000L)
        assertThrows(IllegalStateException::class.java) { cadence.checkContinuous() }
    }

    @Test
    fun trialPairKeepsDirectionalTimesAndAdjustsForStartOffset() {
        val east = SolarPoint(-1_100.0, 0.0)
        val west = SolarPoint(1_100.0, 0.0)
        val westward = SolarTrialMotion(MountDirection.WEST, east, west, 8_800)
        val eastward = SolarTrialMotion(MountDirection.EAST, west, east, 8_000)

        assertTrue(westward.pairsWith(eastward))
        assertEquals(8_960L, westward.adjustedMotionMs(east.copy(west = -1_140.0)))
        assertEquals(8_000L, eastward.adjustedMotionMs(west))
        assertFalse(westward.matchesStart(east.copy(north = 31.0)))
        assertFalse(westward.withinSweep(west.copy(west = 1_146.0)))
    }

    @Test
    fun solarRelativeCoordinatesRoundTripAcrossRaWrap() {
        val date = Instant.parse("2026-03-20T14:48:00Z")
        val point = SolarPoint(1_250.0, 30.0)
        val endpoint = point.endpoint(date)
        val projected = SolarPoint.from(
            com.indigo.mobileobservatory.mount.MountCoordinates(endpoint.raHours, endpoint.decDegrees), date
        )
        assertEquals(point.west, projected.west, 0.000001)
        assertEquals(point.north, projected.north, 0.000001)
    }

    @Test
    fun preflightRequiresDiskCoverageMarginsAndStorage() {
        val base = configuration()
        val good = SolarScanPreflightEvaluator.evaluate(
            base,
            availableBytes = 10L * 1024L * 1024L * 1024L,
            calibratedForCurrentMount = true
        )
        assertTrue(good.canStart)
        assertTrue(good.issues.none { it.severity == SolarScanIssueSeverity.ERROR })

        val short = base.copy(
            endpointB = base.endpointA.copy(
                raHours = base.endpointA.raHours + 0.03
            )
        )
        val shortReport = SolarScanPreflightEvaluator.evaluate(
            short,
            availableBytes = 10L * 1024L * 1024L * 1024L,
            calibratedForCurrentMount = true
        )
        assertFalse(shortReport.canStart)
        assertTrue(shortReport.errors.any { it.code == SolarScanIssueCode.SOURCE_TOO_SHORT })

        val lowStorage = SolarScanPreflightEvaluator.evaluate(
            base,
            availableBytes = 32L,
            calibratedForCurrentMount = true
        )
        assertFalse(lowStorage.canStart)
        assertTrue(lowStorage.errors.any { it.code == SolarScanIssueCode.STORAGE_LOW })
    }

    @Test
    fun sweepReportsContactAndBlankMarginsWithoutImpossibleProfile() {
        val sweep = SolarSweepModel(
            diskRadiusArcsec = 960.0,
            startCenterArcsec = -1_200.0,
            endCenterArcsec = 1_200.0,
            accelerationFraction = 0.15,
            decelerationFraction = 0.15
        )
        val report = sweep.report()
        assertTrue(report.completeDiskCovered)
        assertTrue(report.firstContactFraction in 0.0..1.0)
        assertTrue(report.lastContactFraction in report.firstContactFraction..1.0)
        assertTrue(report.startBlankArcsec >= 239.0)
        assertTrue(report.endBlankArcsec >= 239.0)
        assertFalse(report.accelerationOverlapsDisk)
        assertFalse(report.decelerationOverlapsDisk)
        assertEquals(1_200.0, sweep.centerAt(1.0), 1e-6)
        val reverse = sweep.copy(startCenterArcsec = 1_200.0, endCenterArcsec = -1_200.0)
        val reverseReport = reverse.report()
        assertTrue(reverseReport.completeDiskCovered)
        assertTrue(reverseReport.firstContactFraction < reverseReport.lastContactFraction)
        assertEquals(-1_200.0, reverse.centerAt(1.0), 1e-6)
    }

    @Test
    fun legsAlternateDirectionAndNormalizeEverySecondResult() {
        val plans = configuration().copy(legCount = 4).legPlans()
        assertEquals(
            listOf(
                SolarScanDirection.A_TO_B,
                SolarScanDirection.B_TO_A,
                SolarScanDirection.A_TO_B,
                SolarScanDirection.B_TO_A
            ),
            plans.map { it.direction }
        )
        assertFalse(plans[0].direction.flipVertical)
        assertTrue(plans[1].direction.flipVertical)
    }

    @Test
    fun roiIncludesCurvedTraceExtremaBetweenPixelCentres() {
        val trace = SolarTrace(
            width = 101,
            constantRow = 20.0,
            linear = 0.2,
            quadratic = 3.0,
            illuminatedFirst = 0,
            illuminatedLast = 100
        )
        val extremum = (3.0 * (-0.2 / 6.0) + 0.2) * (-0.2 / 6.0) + 20.0
        assertEquals(extremum, trace.minimumRow(), 1e-9)
        assertEquals(23.2, trace.maximumRow(), 1e-9)
    }

    @Test
    fun generatedSerIsDetectedReconstructedAndCommittedWithValidPaths() = directory { root ->
        val recordings = File(root, "recordings").apply { mkdirs() }
        val captures = File(root, "captures").apply { mkdirs() }
        val source = File(recordings, "SER/simulated.ser")
        source.parentFile!!.mkdirs()
        val configuration = configuration(durationMs = 2_000, fps = 120.0)
        val simulation = SolarScanSimulator.generate(
            destination = source,
            configuration = configuration,
            direction = SolarScanDirection.A_TO_B,
            width = 96,
            height = 32
        )
        val reverseSource = File(recordings, "SER/simulated-reverse.ser")
        val reverse = SolarScanSimulator.generate(
            destination = reverseSource,
            configuration = configuration,
            direction = SolarScanDirection.B_TO_A,
            width = 96,
            height = 32
        )
        assertTrue(reverse.coverage.completeDiskCovered)
        val forwardAsymmetry = horizontalAsymmetry(source, 96, 32)
        val reverseAsymmetry = horizontalAsymmetry(reverseSource, 96, 32)
        assertTrue(kotlin.math.abs(forwardAsymmetry) > 0.03)
        assertTrue(forwardAsymmetry * reverseAsymmetry < 0.0)
        val reverseAnalysis = nativeAnalysis(reverseSource)
        assertTrue(reverseAnalysis.confidence > 0.25)
        assertEquals(simulation.frames, SERReader(source).use {
            assertTrue(it.open())
            it.header!!.frameCount
        })

        val analysis = nativeAnalysis(source)
        assertTrue("confidence=${analysis.confidence}", analysis.confidence > 0.25)
        assertTrue(analysis.validColumns >= 80)
        assertTrue(analysis.residualPixels < 1.5)

        val repository = SolarScanRepository(recordings, captures)
        val taskId = repository.newTaskId()
        val staging = repository.createStaging(taskId)
        val products = File(staging, "products/leg-0001-a-to-b").apply { mkdirs() }
        val summary = nativeReconstruct(
            source = source,
            settings = SolarReconstructionSettings(
                products = setOf(SolarOutputProduct.DISPLAY_PNG, SolarOutputProduct.LINEAR_FITS)
            ),
            outputDirectory = products
        )
        assertNotNull(summary.displayPng)
        assertNotNull(summary.linearFits)
        assertTrue(File(summary.displayPng!!).isFile)
        val fits = File(summary.linearFits!!)
        assertTrue(fits.isFile)
        val header = FitsHeaderParser.read(fits)
        assertEquals("96", header["NAXIS1"])
        assertEquals(simulation.frames.toString(), header["NAXIS2"])
        assertFalse(header.contains("EXPTIME"))
        assertEquals("MJSol'Ex", header["ORIGIN"])
        val rotated = nativeReconstruct(
            source = source,
            settings = SolarReconstructionSettings(
                rotationDegrees = 90,
                products = setOf(SolarOutputProduct.LINEAR_FITS)
            ),
            outputDirectory = File(root, "rotated").apply { mkdirs() }
        )
        assertEquals(simulation.frames, rotated.width)
        assertEquals(96, rotated.height)
        val rotatedHeader = FitsHeaderParser.read(File(rotated.linearFits!!))
        assertEquals(simulation.frames.toString(), rotatedHeader["NAXIS1"])
        assertEquals("96", rotatedHeader["NAXIS2"])

        val identity = SolarSourceIdentity.of(source)
        val record = SolarScanTaskRecord(
            id = taskId,
            createdAtUtc = Instant.now().toString(),
            completedAtUtc = Instant.now().toString(),
            title = "test scan",
            configuration = configuration.copy(roi = SolarRoi(width = 96, height = 32)),
            analysis = analysis,
            reconstructionSettings = SolarReconstructionSettings(),
            legs = listOf(
                SolarScanLegRecord(
                    1,
                    SolarScanDirection.A_TO_B,
                    identity,
                    simulation.frames,
                    0,
                    summary,
                    SolarSourceDispositionState.KEPT
                )
            ),
            sourcePolicy = SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS,
            warnings = emptyList(),
            resultDirectory = staging
        )
        val committed = repository.commit(staging, taskId, record)
        val parsed = checkNotNull(repository.readTask(committed.resultDirectory))
        val relocated = File(checkNotNull(parsed.legs.single().reconstruction?.linearFits))
        assertTrue(relocated.isFile)
        assertTrue(relocated.toPath().startsWith(committed.resultDirectory.toPath()))
        assertFalse(staging.exists())
        assertEquals(
            SolarSourceDispositionState.MOVED_TO_TRASH,
            repository.disposeCommittedSource(parsed, parsed.legs.single(), source).sourceDisposition
        )
        assertTrue(File(repository.trashDirectory, source.name).isFile)
    }

    @Test
    fun rightAlignedTwelveBitSerIsReadable() = directory { root ->
        val file = File(root, "mono12.ser")
        val width = 96
        val height = 32
        val writer = SERWriter(file)
        try {
            writer.open(width, height, PixelFormat.MONO12, cameraName = "test")
            repeat(12) { frame ->
                val bytes = ByteArray(width * height * 2)
                for (y in 0 until height) for (x in 0 until width) {
                    val value = if (x < 2 || x >= width - 2) {
                        0
                    } else {
                        val line = if (y == height / 2) 900 else 3_400
                        (line + (x + frame) % 9).coerceAtMost(4_095)
                    }
                    val index = (y * width + x) * 2
                    bytes[index] = value.toByte()
                    bytes[index + 1] = (value ushr 8).toByte()
                }
                writer.writeFrame(
                    FrameData(bytes, width, height, PixelFormat.MONO12, frame.toLong(), frame.toLong())
                )
            }
        } finally {
            writer.close()
        }
        val preview = SolarScanNative.preview(file, testObserver)
        assertEquals(width, preview.width)
        assertEquals(height, preview.height)
        val analysis = nativeAnalysis(file)
        assertEquals(height / 2.0, analysis.trace.constantRow, 1.0)
        assertTrue("lineDepth=${analysis.lineDepth}", analysis.lineDepth > 0.6)
        assertTrue(analysis.trace.illuminatedFirst >= 2)
        assertTrue(analysis.trace.illuminatedLast <= width - 3)
        assertTrue(analysis.validColumns >= 80)
        val reconstruction = nativeReconstruct(
            source = file,
            settings = SolarReconstructionSettings(
                products = setOf(SolarOutputProduct.LINEAR_FITS)
            ),
            outputDirectory = File(root, "products").apply { mkdirs() }
        )
        assertEquals(width, reconstruction.width)
        assertEquals(12, reconstruction.height)
        val fits = File(checkNotNull(reconstruction.linearFits))
        assertTrue(fits.length() > 2_880L)
        val header = FitsHeaderParser.read(fits)
        assertEquals(width.toString(), header["NAXIS1"])
        assertEquals("12", header["NAXIS2"])
    }

    @Test
    fun simulationVariesSlitIlluminationAlongSpatialAxis() = directory { root ->
        val source = File(root, "slit-motion.ser")
        val config = configuration(durationMs = 2_000, fps = 120.0)
        val simulation = SolarScanSimulator.generate(
            source,
            config,
            SolarScanDirection.A_TO_B,
            width = 96,
            height = 32
        )
        val reader = SERReader(source)
        try {
            assertTrue(reader.open())
            val chordLengths = IntArray(simulation.frames) { index ->
                val frame = checkNotNull(reader.readLinearFrame(index))
                (0 until 96).count { x -> frame.pixels[2 * 96 + x] > 0.12 }
            }
            val maximum = chordLengths.max()
            assertTrue("maximum chord=$maximum", maximum >= 80)
            assertEquals(0, chordLengths.first())
            assertEquals(0, chordLengths.last())
            val halfMaximum = maximum / 2
            val entering = chordLengths.indexOfFirst { it >= halfMaximum }
            val leaving = chordLengths.indexOfLast { it >= halfMaximum }
            assertTrue(entering > 0)
            assertTrue(leaving in entering until chordLengths.lastIndex)
            assertTrue(chordLengths[entering] < maximum)
            assertTrue(chordLengths[leaving] < maximum)
        } finally {
            reader.close()
        }
    }

    @Test
    fun pendingDispositionKeepsSourcesWhenCommittedResultsAreInvalid() = directory { root ->
        val recordings = File(root, "recordings").apply { mkdirs() }
        val captures = File(root, "captures").apply { mkdirs() }
        val firstSource = File(recordings, "SER/first.ser").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(64) { 1 })
        }
        val secondSource = File(recordings, "SER/second.ser").apply {
            writeBytes(ByteArray(64) { 2 })
        }
        val repository = SolarScanRepository(recordings, captures)
        val staging = repository.createStaging(repository.newTaskId())
        val products = File(staging, "products").apply { mkdirs() }
        val firstProduct = File(products, "first-display.png")
        val secondProduct = File(products, "second-display.png")
        writePng(firstProduct, 1, 1)
        writePng(secondProduct, 1, 1)
        val settings = SolarReconstructionSettings(
            products = setOf(SolarOutputProduct.DISPLAY_PNG)
        )
        val firstIdentity = SolarSourceIdentity.of(firstSource)
        val secondIdentity = SolarSourceIdentity.of(secondSource)
        val record = SolarScanTaskRecord(
            id = staging.name,
            createdAtUtc = Instant.now().toString(),
            completedAtUtc = Instant.now().toString(),
            title = "validation",
            configuration = configuration(),
            analysis = null,
            reconstructionSettings = settings,
            legs = listOf(
                SolarScanLegRecord(
                    number = 1,
                    direction = SolarScanDirection.A_TO_B,
                    source = firstIdentity,
                    recordingFrames = 1,
                    droppedFrames = 0,
                    reconstruction = SolarReconstructionSummary(
                        source = firstSource.name,
                        displayPng = firstProduct.absolutePath,
                        linearPng = null,
                        linearFits = null,
                        width = 1,
                        height = 1,
                        frameStride = 1,
                        sourceFrames = 1,
                        warnings = emptyList(),
                        integrity = mapOf(
                            SolarOutputProduct.DISPLAY_PNG to SolarProductIntegrity.of(firstProduct)
                        )
                    ),
                    sourceDisposition = SolarSourceDispositionState.KEPT,
                    reconstructionSettings = settings
                ),
                SolarScanLegRecord(
                    number = 2,
                    direction = SolarScanDirection.B_TO_A,
                    source = secondIdentity,
                    recordingFrames = 1,
                    droppedFrames = 0,
                    reconstruction = SolarReconstructionSummary(
                        source = secondSource.name,
                        displayPng = secondProduct.absolutePath,
                        linearPng = null,
                        linearFits = null,
                        width = 1,
                        height = 1,
                        frameStride = 1,
                        sourceFrames = 1,
                        warnings = emptyList(),
                        integrity = mapOf(
                            SolarOutputProduct.DISPLAY_PNG to SolarProductIntegrity.of(secondProduct)
                        )
                    ),
                    sourceDisposition = SolarSourceDispositionState.KEPT,
                    coverage = SolarScanCoverageReport(
                        firstContactFraction = 0.2,
                        lastContactFraction = 0.4,
                        startBlankArcsec = 10.0,
                        endBlankArcsec = 10.0,
                        completeDiskCovered = false,
                        accelerationOverlapsDisk = false,
                        decelerationOverlapsDisk = false,
                        stableCoverageFraction = 0.2
                    ),
                    reconstructionSettings = settings
                )
            ),
            sourcePolicy = SolarSourcePolicy.DELETE_AFTER_COMMIT,
            warnings = emptyList(),
            resultDirectory = staging
        )
        repository.authorizeDispositionRecovery(record.id)
        val committed = repository.commit(staging, record.id, record)
        val committedFirstProduct = checkNotNull(
            repository.readTask(committed.resultDirectory)!!
                .legs[0].reconstruction!!.displayPng
        )
        File(committedFirstProduct).writeBytes(byteArrayOf(1))

        assertEquals(1, repository.reconcilePendingDispositions().size)
        val reconciled = checkNotNull(repository.readTask(committed.resultDirectory))
        assertTrue(firstSource.isFile)
        assertTrue(secondSource.isFile)
        assertEquals(
            listOf(SolarSourceDispositionState.FAILED, SolarSourceDispositionState.FAILED),
            reconciled.legs.map { it.sourceDisposition }
        )
        assertTrue(reconciled.legs[0].failure!!.startsWith("result_validation:result_product_invalid"))
        assertEquals("result_validation:coverage_incomplete", reconciled.legs[1].failure)
    }

    @Test
    fun reconstructionSettingsAndAppliedCorrectionsRoundTrip() = directory { root ->
        val recordings = File(root, "recordings").apply { mkdirs() }
        val captures = File(root, "captures").apply { mkdirs() }
        val repository = SolarScanRepository(recordings, captures)
        val staging = repository.createStaging(repository.newTaskId())
        val product = File(staging, "products/result-display.png").apply {
            parentFile!!.mkdirs()
            writeText("png")
        }
        val settings = SolarReconstructionSettings(
            lineShiftPixels = 1.25,
            flipHorizontal = true,
            flipVertical = true,
            rotationDegrees = 90,
            frameStride = 3,
            scanCorrection = SolarScanCorrectionSettings(
                bandingAxis = SolarBandingAxis.AUTO,
                bandingWidthPixels = 15,
                alignScanRows = true,
                correctPeriodicOscillation = true
            ),
            flat = SolarFlatSettings(synthetic = true, syntheticStrength = 73),
            products = setOf(SolarOutputProduct.DISPLAY_PNG)
        )
        val corrections = SolarCorrectionReport(
            geometryApplied = true,
            bandingApplied = SolarBandingAxis.HORIZONTAL,
            bandingWidthPixels = 15,
            scanRowsAligned = true,
            oscillationPeriodPixels = 11.5,
            oscillationAmplitudePixels = 0.35,
            syntheticFlatStrength = 73
        )
        val memory = SolarMemorySampleSummary(
            headroomBytes = 0L,
            firstSampledAtUtc = Instant.now().toString(),
            sampleCount = 2,
            maxIntervalMs = 1_020L
        )
        val integrity = SolarProductIntegrity.of(product)
        val summary = SolarReconstructionSummary(
            source = "source.ser",
            displayPng = product.absolutePath,
            linearPng = null,
            linearFits = null,
            width = 8,
            height = 4,
            frameStride = 3,
            sourceFrames = 12,
            warnings = listOf("Scan corrections skipped. NoSolarLimb"),
            corrections = corrections,
            memory = memory,
            integrity = mapOf(
                SolarOutputProduct.DISPLAY_PNG to integrity
            )
        )
        val record = SolarScanTaskRecord(
            id = staging.name,
            createdAtUtc = Instant.now().toString(),
            completedAtUtc = Instant.now().toString(),
            title = "settings",
            configuration = configuration(),
            analysis = null,
            reconstructionSettings = settings,
            legs = listOf(
                SolarScanLegRecord(
                    number = 1,
                    direction = SolarScanDirection.UNKNOWN,
                    source = null,
                    recordingFrames = 12,
                    droppedFrames = 0,
                    reconstruction = summary,
                    sourceDisposition = SolarSourceDispositionState.KEPT,
                    reconstructionSettings = settings
                )
            ),
            sourcePolicy = SolarSourcePolicy.KEEP,
            warnings = emptyList(),
            resultDirectory = staging
        )

        val committed = repository.commit(staging, record.id, record)
        val restored = checkNotNull(repository.readTask(committed.resultDirectory))
        assertEquals(settings, restored.reconstructionSettings)
        assertEquals(settings, restored.legs.single().reconstructionSettings)
        assertEquals(corrections, restored.legs.single().reconstruction!!.corrections)
        assertEquals(memory, restored.legs.single().reconstruction!!.memory)
        assertEquals(
            integrity,
            restored.legs.single().reconstruction!!.integrity[SolarOutputProduct.DISPLAY_PNG]
        )
        assertEquals(0L, restored.legs.single().reconstruction!!.memory!!.headroomBytes)
    }

    @Test
    fun reconstructionDraftPersistsOnlyWhenReconstructionStarts() {
        val saved = mutableListOf<SolarReconstructionSettings>()
        val initial = SolarReconstructionSettings(frameStride = 1)
        val session = SolarReconstructionSettingsSession(initial, saved::add)
        val edited = session.edit { it.copy(frameStride = 4) }
        assertTrue(saved.isEmpty())
        session.markUsed(edited)
        assertEquals(listOf(edited), saved)
    }

    @Test
    fun cancellationKeepsOnlySealedSimulationSources() = directory { root ->
        val config = configuration(durationMs = 1_000, fps = 30.0)
        val sealed = File(root, "sealed.ser")
        val partial = File(root, "partial.ser").apply { writeText("partial") }
        SolarScanSimulator.generate(
            destination = sealed,
            configuration = config,
            direction = SolarScanDirection.A_TO_B,
            width = 64,
            height = 20
        )

        incompleteSimulationSourcesToDelete(
            listOf(sealed, partial),
            setOf(sealed)
        ).forEach(File::delete)

        assertTrue(sealed.isFile)
        assertFalse(partial.exists())
        assertEquals(64, SolarScanNative.preview(sealed, testObserver).width)
    }

    @Test
    fun stalePreviewCannotReplaceNewerSourceSelection() = directory { root ->
        val first = File(root, "first.ser")
        val second = File(root, "second.ser")
        assertFalse(
            isCurrentSolarPreview(
                first,
                second,
                jobActive = true,
                generation = 1L,
                currentGeneration = 2L
            )
        )
        assertFalse(
            isCurrentSolarPreview(
                second,
                second,
                jobActive = false,
                generation = 2L,
                currentGeneration = 2L
            )
        )
        assertTrue(
            isCurrentSolarPreview(
                second,
                second,
                jobActive = true,
                generation = 2L,
                currentGeneration = 2L
            )
        )
    }

    @Test
    fun emptySelectionIsNotReportedAsRecording() = directory { root ->
        val source = File(root, "source.ser")
        assertFalse(isSolarSourceBusy(null, null))
        assertFalse(isSolarSourceBusy(source, null))
        assertFalse(isSolarSourceBusy(null, source))
        assertTrue(isSolarSourceBusy(source, source))
    }

    @Test
    fun simulationSourcesRemainHiddenUntilFinalized() = directory { root ->
        val recordings = File(root, "recordings").apply { mkdirs() }
        val captures = File(root, "captures").apply { mkdirs() }
        val repository = SolarScanRepository(recordings, captures)
        val sourceDir = File(recordings, "SER").apply { mkdirs() }
        val stale = File(sourceDir, ".solar-stale.ser.partial").apply { writeText("stale") }
        assertEquals(1, repository.cleanupIncompleteSources())
        assertFalse(stale.exists())

        val partial = File(sourceDir, ".solar-new.ser.partial").apply { writeText("sealed") }
        val final = File(sourceDir, "solar-new.ser")
        val identity = publishSealedSimulationSource(partial, final)
        assertFalse(partial.exists())
        assertEquals("sealed", final.readText())
        assertEquals(final.name, identity.fileName)
        assertTrue(identity.matches(final))
    }

    @Test
    fun sealedSimulationSurvivesIdentityAndPublicationFailures() = directory { root ->
        val recordings = File(root, "recordings")
        val repository = SolarScanRepository(recordings, File(root, "captures"))
        val sourceDir = File(recordings, "SER").apply { mkdirs() }
        val final = File(sourceDir, "solar-new.ser")
        val partial = File(sourceDir, ".${final.name}.partial")

        writeSer(partial, frames = 3)
        val readFailure = IOException("identity unavailable")
        val failure = assertThrows(IOException::class.java) {
            publishSealedSimulationSource(partial, final) { _, _ -> throw readFailure }
        }
        assertEquals(readFailure, failure)
        assertFalse(partial.exists())
        assertTrue(final.isFile)

        // A writer also closes and adds timestamps when recording stops partway through.
        writeSer(partial, frames = 2)
        assertEquals(0, repository.cleanupIncompleteSources())
        assertTrue(partial.isFile)

        // An existing destination can prevent publication. Startup must keep the completed input.
        val sealed = File(sourceDir, ".${final.name}.sealed")
        writeSer(sealed, frames = 2)
        assertEquals(0, repository.cleanupIncompleteSources())
        assertTrue(sealed.isFile)
        assertTrue(final.delete())
        assertEquals(0, repository.cleanupIncompleteSources())
        assertFalse(sealed.exists())
        SERReader(final).use { reader ->
            assertTrue(reader.open())
            assertEquals(2, reader.header!!.frameCount)
        }
    }

    @Test
    fun restartRecoversCompletedRecordingWhenSealedRenameFailed() = directory { root ->
        val recordings = File(root, "recordings")
        val captures = File(root, "captures")
        val repository = SolarScanRepository(recordings, captures)
        val sourceDir = File(recordings, "SER").apply { mkdirs() }
        val partial = File(sourceDir, ".solar-complete.ser.partial")
        val blockedDestination = File(sourceDir, ".solar-complete.ser.sealed").apply { mkdir() }
        val interrupted = File(sourceDir, ".solar-interrupted.ser.partial")
        writeSer(partial, frames = 3)
        writeSer(interrupted, frames = 2)
        assertThrows(IOException::class.java) { repository.markSimulationSourceComplete(partial) }
        assertTrue(partial.isFile)
        assertTrue(blockedDestination.delete())

        SolarScanRepository(recordings, captures).cleanupIncompleteSources()
        assertFalse(partial.exists())
        assertTrue(interrupted.isFile)
        assertFalse(File(sourceDir, "solar-interrupted.ser").exists())
        SERReader(File(sourceDir, "solar-complete.ser")).use { reader ->
            assertTrue(reader.open())
            assertEquals(3, reader.header!!.frameCount)
        }
    }

    @Test
    fun automaticSourceDisposalHonorsMediaLease() = directory { root ->
        val (repository, task, source) = committedScan(root, SolarSourcePolicy.DELETE_AFTER_COMMIT)
        val leg = task.legs.single()
        MediaUsageRegistry.acquire(source).use {
            val result = repository.disposeCommittedSource(task, leg, source)
            assertEquals(SolarSourceDispositionState.FAILED, result.sourceDisposition)
            assertTrue(source.isFile)
        }
        val result = repository.disposeCommittedSource(task, leg, source)
        assertEquals(SolarSourceDispositionState.DELETED, result.sourceDisposition)
        assertFalse(source.exists())
    }

    @Test
    fun fitsShareLeaseProtectsBothTheFrameAndItsSequence() = directory { root ->
        val sequence = File(root, "sequence").apply { mkdir() }
        val frame = File(sequence, "first.fits").apply { writeText("first frame") }
        val trash = File(root, ".trash")
        MediaUsageRegistry.acquire(listOf(frame, sequence)).use {
            assertFalse(MediaTrash.move(sequence, trash))
            assertFalse(MediaTrash.delete(sequence))
            assertFalse(MediaTrash.move(frame, trash))
            assertTrue(frame.isFile)
        }
        assertTrue(MediaTrash.move(sequence, trash))
        assertTrue(MediaTrash.delete(File(trash, sequence.name)))
    }

    @Test
    fun playerCanMoveCurrentFileOnlyAfterReleasingOwnLease() = directory { root ->
        val trash = File(root, ".trash").apply { mkdirs() }
        val source = File(root, "current.ser").apply { writeText("current") }
        val lease = MediaUsageRegistry.acquire(source)
        assertFalse(MediaTrash.move(source, trash))
        lease.close()
        assertTrue(MediaTrash.move(source, trash))
        assertTrue(File(trash, source.name).isFile)
    }

    @Test
    fun mediaLeaseBlocksUntilExclusiveDisposalFinishes() = directory { root ->
        val file = File(root, "exclusive.ser").apply { writeText("media") }
        val exclusiveEntered = CountDownLatch(1)
        val releaseExclusive = CountDownLatch(1)
        val exclusiveDone = CountDownLatch(1)
        Thread {
            MediaUsageRegistry.withExclusiveAccess(file) {
                exclusiveEntered.countDown()
                releaseExclusive.await(3, TimeUnit.SECONDS)
                false
            }
            exclusiveDone.countDown()
        }.start()
        assertTrue(exclusiveEntered.await(2, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) {
            MediaUsageRegistry.acquire(file, waitForExclusive = false).close()
        }
        val interruptedAttempt = FutureTask {
            try {
                MediaUsageRegistry.acquireInterruptibly(file) {}.close()
                false
            } catch (_: InterruptedException) {
                true
            }
        }
        val waitingThread = Thread(interruptedAttempt).apply { isDaemon = true }
        waitingThread.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (waitingThread.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.WAITING, waitingThread.state)
        waitingThread.interrupt()
        assertTrue(interruptedAttempt.get(2, TimeUnit.SECONDS))

        val leaseRef = AtomicReference<AutoCloseable?>()
        val leaseAttempted = CountDownLatch(1)
        val leaseAcquired = CountDownLatch(1)
        Thread {
            leaseAttempted.countDown()
            leaseRef.set(MediaUsageRegistry.acquire(file))
            leaseAcquired.countDown()
        }.start()
        assertTrue(leaseAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(leaseAcquired.await(150, TimeUnit.MILLISECONDS))
        releaseExclusive.countDown()
        assertTrue(leaseAcquired.await(2, TimeUnit.SECONDS))
        assertTrue(exclusiveDone.await(2, TimeUnit.SECONDS))
        checkNotNull(leaseRef.get()).close()
    }

    @Test
    fun deferredShareLeaseCoversExternalReadWindow() = directory { root ->
        val source = File(root, "shared.ser").apply { writeText("shared") }
        val lease = MediaUsageRegistry.acquire(source)
        MediaUsageRegistry.releaseAfter(lease, 40L)
        assertTrue(MediaUsageRegistry.isInUse(source))
        val deadline = System.currentTimeMillis() + 2_000L
        while (MediaUsageRegistry.isInUse(source) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        assertFalse(MediaUsageRegistry.isInUse(source))
    }

    @Test
    fun trashCleanupRetainsLeasedExpiredEntry() = directory { root ->
        val trash = File(root, ".trash").apply { mkdirs() }
        val source = File(root, "leased.ser").apply { writeText("data") }
        assertTrue(MediaTrash.move(source, trash))
        val entry = File(trash, source.name)
        writeTrashTime(entry, System.currentTimeMillis() - MediaTrash.RETENTION_MS - 1)
        val lease = MediaUsageRegistry.acquire(entry)
        try {
            val result = MediaTrash.purgeExpired(
                trash,
                inUse = MediaUsageRegistry::isInUse
            )
            assertEquals(0, result.purged)
            assertEquals(1, result.retained)
            assertTrue(entry.isFile)
        } finally {
            lease.close()
        }
    }

    @Test
    fun cancellationAfterFirstExportStopsBeforeRemainingProducts() = directory { root ->
        val source = File(root, "cancel-export.ser")
        val config = configuration(durationMs = 1_000, fps = 30.0)
        SolarScanSimulator.generate(
            source,
            config,
            SolarScanDirection.A_TO_B,
            width = 64,
            height = 20
        )
        val output = File(root, "products").apply { mkdirs() }
        val observer = object : SolarNativeObserver {
            override fun isCancelled(): Boolean =
                output.listFiles().orEmpty().any { it.name.endsWith("-display.png") }

            override fun onProgress(value: Float) = Unit
        }
        assertThrows(IllegalStateException::class.java) {
            SolarScanNative.reconstruct(
                source,
                output,
                SolarReconstructionSettings(
                    products = setOf(
                        SolarOutputProduct.DISPLAY_PNG,
                        SolarOutputProduct.LINEAR_PNG,
                        SolarOutputProduct.LINEAR_FITS
                    )
                ),
                memoryHeadroomBytes = 4L * 1024L * 1024L * 1024L,
                chinese = false,
                observer = observer
            )
        }
        assertTrue(output.listFiles().orEmpty().any { it.name.endsWith("-display.png") })
        assertFalse(output.listFiles().orEmpty().any { it.name.endsWith("-linear.png") })
        assertFalse(output.listFiles().orEmpty().any { it.extension.equals("fit", true) })
    }

    @Test
    fun observerExceptionStopsJniCallbacksBeforeProgress() = directory { root ->
        val source = File(root, "observer.ser")
        SolarScanSimulator.generate(
            source,
            configuration(durationMs = 1_000, fps = 30.0),
            SolarScanDirection.A_TO_B,
            width = 64,
            height = 20
        )
        var progressCalls = 0
        val observer = object : SolarNativeObserver {
            override fun isCancelled(): Boolean {
                throw IllegalStateException("observer failed")
            }

            override fun onProgress(value: Float) {
                progressCalls++
            }
        }
        assertThrows(IllegalStateException::class.java) {
            SolarScanNative.analyze(source, observer)
        }
        assertEquals(0, progressCalls)
    }

    @Test
    fun zeroSystemMemoryHeadroomIsNotTreatedAsUnknown() = directory { root ->
        val source = File(root, "zero-memory.ser")
        SolarScanSimulator.generate(
            source,
            configuration(durationMs = 1_000, fps = 30.0),
            SolarScanDirection.A_TO_B,
            width = 64,
            height = 20
        )
        assertThrows(IllegalStateException::class.java) {
            SolarScanNative.reconstruct(
                source,
                File(root, "zero").apply { mkdirs() },
                SolarReconstructionSettings(
                    products = setOf(SolarOutputProduct.LINEAR_FITS)
                ),
                memoryHeadroomBytes = 0L,
                chinese = false,
                observer = testObserver
            )
        }
    }

    @Test
    fun physicalFlatAndScanSettingsReachNativeCore() = directory { root ->
        val source = File(root, "flat-settings.ser")
        SolarScanSimulator.generate(
            source,
            configuration(durationMs = 1_000, fps = 30.0),
            SolarScanDirection.A_TO_B,
            width = 64,
            height = 20
        )
        val summary = nativeReconstruct(
            source = source,
            settings = SolarReconstructionSettings(
                scanCorrection = SolarScanCorrectionSettings(
                    bandingAxis = SolarBandingAxis.AUTO,
                    bandingWidthPixels = 15,
                    alignScanRows = true,
                    correctPeriodicOscillation = true
                ),
                flat = SolarFlatSettings(physicalSourcePath = source.absolutePath),
                products = setOf(SolarOutputProduct.LINEAR_FITS)
            ),
            outputDirectory = File(root, "flat-products").apply { mkdirs() }
        )
        assertTrue(summary.corrections.physicalFlatApplied)
        assertFalse(summary.corrections.syntheticFlatStrength != null)
    }

    @Test
    fun trashRetentionDeletesOnlyTimestampedExpiredEntries() = directory { root ->
        val trash = File(root, ".trash").apply { mkdirs() }
        val old = File(root, "old.ser").apply { writeText("old") }
        val recent = File(root, "recent.ser").apply { writeText("recent") }
        val legacy = File(root, "legacy.ser").apply { writeText("legacy") }
        val sequence = File(root, "sequence").apply {
            mkdirs()
            File(this, "frame.fit").writeText("frame")
        }
        assertTrue(com.indigo.mobileobservatory.media.MediaTrash.move(old, trash))
        assertTrue(com.indigo.mobileobservatory.media.MediaTrash.move(recent, trash))
        assertTrue(com.indigo.mobileobservatory.media.MediaTrash.move(legacy, trash))
        assertTrue(com.indigo.mobileobservatory.media.MediaTrash.move(sequence, trash))
        val trashedOld = File(trash, old.name)
        val trashedRecent = File(trash, recent.name)
        val trashedLegacy = File(trash, legacy.name)
        val trashedSequence = File(trash, sequence.name)
        assertNotNull(com.indigo.mobileobservatory.media.MediaTrash.trashedAtUtcMs(trashedOld))
        assertNotNull(com.indigo.mobileobservatory.media.MediaTrash.trashedAtUtcMs(trashedRecent))
        assertNotNull(com.indigo.mobileobservatory.media.MediaTrash.trashedAtUtcMs(trashedLegacy))
        assertNotNull(com.indigo.mobileobservatory.media.MediaTrash.trashedAtUtcMs(trashedSequence))
        val now = System.currentTimeMillis()
        writeTrashTime(trashedOld, now - com.indigo.mobileobservatory.media.MediaTrash.RETENTION_MS - 1)
        writeTrashTime(trashedRecent, now - 1_000)
        writeTrashTime(trashedLegacy, null)
        writeTrashTime(
            trashedSequence,
            now - com.indigo.mobileobservatory.media.MediaTrash.RETENTION_MS - 1
        )

        val result = com.indigo.mobileobservatory.media.MediaTrash.purgeExpired(trash, now)
        assertEquals(2, result.purged)
        assertEquals(2, result.retained)
        assertFalse(trashedOld.exists())
        assertFalse(trashedSequence.exists())
        assertTrue(trashedRecent.exists())
        assertTrue(trashedLegacy.exists())
    }

    @Test
    fun recoveryRemovesOrphanStaging() = directory { root ->
        val repository = SolarScanRepository(File(root, "recordings"), File(root, "captures"))
        val orphan = repository.createStaging("solar-orphan")
        assertTrue(repository.recoverStaging().isEmpty())
        assertFalse(orphan.exists())
    }

    @Test
    fun disposalRechecksReplacedSourceUnderLockAndKeepsProductLeased() {
        for (policy in listOf(SolarSourcePolicy.DELETE_AFTER_COMMIT, SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS)) {
            for (recovering in listOf(false, true)) directory { root ->
                val (repository, task, source) = committedScan(root, policy)
                val leg = task.legs.single()
                val product = File(checkNotNull(leg.reconstruction?.displayPng))
                val disposition = FutureTask {
                    if (recovering) {
                        repository.reconcilePendingDispositions()
                        checkNotNull(repository.readTask(task.resultDirectory)).legs.single()
                    } else repository.disposeCommittedSource(task, leg, source)
                }
                val worker = Thread(disposition).apply { isDaemon = true }
                val replacement = ByteArray(64) { (63 - it).toByte() }
                synchronized(MediaTrash) {
                    worker.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (worker.state != Thread.State.BLOCKED && !disposition.isDone &&
                        System.nanoTime() < deadline
                    ) Thread.sleep(1)
                    assertEquals(Thread.State.BLOCKED, worker.state)
                    assertTrue(MediaUsageRegistry.isInUse(product))
                    assertFalse(MediaTrash.move(product, repository.trashDirectory))
                    val busy = assertThrows(IllegalStateException::class.java) {
                        MediaRename.rename(product, "renamed")
                    }
                    assertEquals(MediaRename.BUSY, busy.message)
                    MediaUsageRegistry.acquire(source).use {
                        val edited = File(root, "edit.partial").apply { writeBytes(replacement) }
                        Files.move(edited.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                val result = disposition.get(5, TimeUnit.SECONDS)
                assertEquals(SolarSourceDispositionState.FAILED, result.sourceDisposition)
                assertEquals("result_validation:source_identity", result.failure)
                assertTrue(source.readBytes().contentEquals(replacement))
                assertFalse(MediaUsageRegistry.isInUse(product))
            }
        }
    }

    @Test
    fun movingRequiredProductFirstKeepsSourceDuringCommitAndRecovery() {
        for (recovering in listOf(false, true)) directory { root ->
            val (repository, task, source) = committedScan(root, SolarSourcePolicy.DELETE_AFTER_COMMIT)
            val leg = task.legs.single()
            val product = File(checkNotNull(leg.reconstruction?.displayPng))
            assertTrue(MediaTrash.move(product, repository.trashDirectory))
            val result = if (recovering) {
                repository.reconcilePendingDispositions()
                checkNotNull(repository.readTask(task.resultDirectory)).legs.single()
            } else repository.disposeCommittedSource(task, leg, source)
            assertEquals(SolarSourceDispositionState.FAILED, result.sourceDisposition)
            assertEquals("result_validation:result_product_invalid:display_png", result.failure)
            assertTrue(source.isFile)
        }
    }

    @Test
    fun cancelledDispositionKeepsSourceAndReleasesProductLease() = directory { root ->
        val (repository, task, source) = committedScan(root, SolarSourcePolicy.DELETE_AFTER_COMMIT)
        val leg = task.legs.single()
        val product = File(checkNotNull(leg.reconstruction?.displayPng))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            repository.disposeCommittedSource(task, leg, source) {
                // Entry, before validation, and immediately after validation.
                if (++checks == 3) throw CancellationException("stop")
            }
        }
        assertTrue(source.isFile)
        assertFalse(MediaUsageRegistry.isInUse(product))
    }

    @Test
    fun replacingPublishedSerBeforeManifestKeepsEditedInput() {
        for ((policy, _) in automaticDispositions) {
            for (recovering in listOf(false, true)) directory { root ->
                val (repository, task, source) = committedScan(root, policy, beforeCommit = { original ->
                    val edited = File(original.parentFile, ".edited.partial")
                    writeSer(edited, frames = 2)
                    Files.move(edited.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
                })
                assertEquals(3, task.legs.single().recordingFrames)
                if (recovering) {
                    repository.reconcilePendingDispositions()
                } else {
                    repository.disposeCommittedSources(task) {}
                }
                val leg = checkNotNull(repository.readTask(task.resultDirectory)).legs.single()
                assertEquals(SolarSourceDispositionState.FAILED, leg.sourceDisposition)
                assertEquals("result_validation:source_identity", leg.failure)
                SERReader(source).use { reader ->
                    assertTrue(reader.open())
                    assertEquals(2, reader.header!!.frameCount)
                }
                assertFalse(File(repository.trashDirectory, source.name).exists())
            }
        }
    }

    @Test
    fun stopAfterPublicationMakesRemainingDispositionsTerminal() {
        for ((policy, completedState) in automaticDispositions) {
            for (finishFirstLeg in listOf(false, true)) directory { root ->
                val (repository, task, first) = committedScan(root, policy, legCount = 2)
                val second = File(first.parentFile, checkNotNull(task.legs[1].source).fileName)
                assertThrows(CancellationException::class.java) {
                    repository.disposeCommittedSources(task) {
                        if (!finishFirstLeg || !first.exists()) throw CancellationException("stop")
                    }
                }
                assertTrue(repository.reconcilePendingDispositions().isEmpty())
                val restored = checkNotNull(repository.readTask(task.resultDirectory))
                assertTrue(second.isFile)
                assertEquals(!finishFirstLeg, first.isFile)
                assertEquals(
                    listOf(
                        if (finishFirstLeg) completedState else SolarSourceDispositionState.SKIPPED,
                        SolarSourceDispositionState.SKIPPED
                    ),
                    restored.legs.map { it.sourceDisposition }
                )
                assertEquals(SolarScanRepository.DISPOSITION_CANCELLED, restored.legs[1].failure)
                assertTrue(restored.legs[1].sourceDispositionCancelled)
                assertTrue(restored.legs.all { File(checkNotNull(it.reconstruction?.displayPng)).isFile })
            }
        }
    }

    @Test
    fun stopKeepsEarlierDispositionFailureAndSuppressesItsRetry() = directory { root ->
        val (repository, task, first) = committedScan(root, SolarSourcePolicy.DELETE_AFTER_COMMIT, legCount = 2)
        val failedLeg = task.legs.first().copy(
            sourceDisposition = SolarSourceDispositionState.FAILED,
            failure = "result_validation:source_identity"
        )
        repository.keepPendingSourcesAfterStop(task.copy(legs = listOf(failedLeg, task.legs[1])), task)
        assertTrue(repository.reconcilePendingDispositions().isEmpty())
        val restored = checkNotNull(repository.readTask(task.resultDirectory))
        assertEquals(failedLeg.failure, restored.legs.first().failure)
        assertTrue(restored.legs.all { it.sourceDispositionCancelled })
        assertTrue(first.isFile)
    }

    @Test
    fun stopRevokesAutomaticRecoveryEvenWhenResultStatusCannotBeSaved() {
        for ((policy, _) in automaticDispositions) directory { root ->
            val (repository, task, source) = committedScan(root, policy)
            val unavailable = File(root, "temporarily-unavailable")
            Files.move(task.resultDirectory.toPath(), unavailable.toPath())
            assertThrows(IllegalStateException::class.java) {
                repository.keepPendingSourcesAfterStop(task)
            }
            Files.move(unavailable.toPath(), task.resultDirectory.toPath())
            val restarted = SolarScanRepository(File(root, "recordings"), File(root, "captures"))
            assertTrue(restarted.reconcilePendingDispositions().isEmpty())
            assertTrue(source.isFile)
            assertEquals(
                SolarSourceDispositionState.KEPT,
                checkNotNull(restarted.readTask(task.resultDirectory)).legs.single().sourceDisposition
            )
        }
    }

    @Test
    fun stopDuringFailedFinalManifestWriteRevokesRecovery() = directory { root ->
        val (repository, task, source) = committedScan(root, SolarSourcePolicy.DELETE_AFTER_COMMIT)
        val unavailable = File(root, "temporarily-unavailable")
        var stopped = false
        MediaUsageRegistry.acquire(source).use {
            assertThrows(IllegalStateException::class.java) {
                repository.disposeCommittedSources(
                    task,
                    dispositionWarnings = {
                        Files.move(task.resultDirectory.toPath(), unavailable.toPath())
                        stopped = true
                        emptyList()
                    }
                ) {
                    if (stopped) throw CancellationException("stop")
                }
            }
        }
        Files.move(unavailable.toPath(), task.resultDirectory.toPath())
        assertTrue(repository.reconcilePendingDispositions().isEmpty())
        assertTrue(source.isFile)
    }

    @Test
    fun restartReconciliationCompletesSourceDispositionAfterPublication() {
        for ((policy, completedState) in automaticDispositions) directory { root ->
            val (repository, task, source) = committedScan(root, policy)
            assertEquals(1, repository.reconcilePendingDispositions().size)
            assertEquals(
                completedState,
                checkNotNull(repository.readTask(task.resultDirectory)).legs.single().sourceDisposition
            )
            assertFalse(source.exists())
            assertEquals(
                policy == SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS,
                File(repository.trashDirectory, source.name).isFile
            )
        }
    }

    @Test
    fun stopRevokesRecoveryBeforeWorkerHandlesCancellation() {
        for ((policy, _) in automaticDispositions) {
            for (stopBeforeCommit in listOf(false, true)) directory { root ->
                val (repository, task, source) = committedScan(
                    root,
                    policy,
                    beforePublication = if (stopBeforeCommit) {
                        { repo, taskId -> repo.revokeRecoveryPermit(taskId) }
                    } else null
                )
                if (!stopBeforeCommit) repository.revokeRecoveryPermit(task.id)

                // Restart without persisting a cancellation outcome, as if the worker was killed.
                val restarted = SolarScanRepository(File(root, "recordings"), File(root, "captures"))
                assertTrue(restarted.reconcilePendingDispositions().isEmpty())
                assertTrue(source.isFile)
                assertFalse(File(repository.trashDirectory, source.name).exists())
                val committed = checkNotNull(restarted.readTask(task.resultDirectory))
                assertEquals(SolarSourceDispositionState.KEPT, committed.legs.single().sourceDisposition)
            }
        }
    }

    @Test
    fun restartWithoutPermitReconcilesMissingSourcesAndKeepsRemainingSources() {
        for ((policy, _) in automaticDispositions) directory { root ->
            val (repository, task, first) = committedScan(root, policy, legCount = 2)
            val second = File(first.parentFile, checkNotNull(task.legs[1].source).fileName)
            repository.disposeCommittedSource(task, task.legs.first(), first)
            assertFalse(first.exists())
            val replacement = ByteArray(64) { (63 - it).toByte() }
            if (policy == SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS) first.writeBytes(replacement)
            repository.revokeRecoveryPermit(task.id)

            assertEquals(listOf(task.resultDirectory), repository.reconcilePendingDispositions())
            val restored = checkNotNull(repository.readTask(task.resultDirectory))
            val firstLeg = restored.legs.first()
            if (policy == SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS) {
                assertEquals(SolarSourceDispositionState.MOVED_TO_TRASH, firstLeg.sourceDisposition)
                assertTrue(first.readBytes().contentEquals(replacement))
            } else {
                assertTrue(firstLeg.sourceMissing)
                first.writeBytes(ByteArray(64) { it.toByte() })
                repository.reconcilePendingDispositions()
                assertFalse(checkNotNull(repository.readTask(task.resultDirectory)).legs.first().sourceMissing)
                assertTrue(first.isFile)
            }
            assertEquals(SolarSourceDispositionState.KEPT, restored.legs[1].sourceDisposition)
            assertTrue(second.isFile)
        }
    }

    @Test
    fun startupPrunesOnlyOrphanedPermits() = directory { root ->
        val (repository, task, _) = committedScan(root, SolarSourcePolicy.DELETE_AFTER_COMMIT)
        val permits = File(root, "captures/.solar-disposition")
        repository.authorizeDispositionRecovery("solar-orphan")
        repository.authorizeDispositionRecovery("solar-running")
        repository.authorizeDispositionRecovery("solar-staged")
        repository.createStaging("solar-staged")

        repository.cleanOrphanedRecoveryPermits(storageMounted = { false }) { "solar-running" }
        assertTrue(File(permits, "solar-orphan.pending").isFile)

        repository.cleanOrphanedRecoveryPermits(storageMounted = { true }) { "solar-running" }

        assertFalse(File(permits, "solar-orphan.pending").exists())
        assertTrue(File(permits, "solar-running.pending").isFile)
        assertTrue(File(permits, "solar-staged.pending").isFile)
        assertTrue(File(permits, "${task.id}.pending").isFile)
    }

    @Test
    fun stopAtFileCommitRollsBackPreparedTrashMetadataAndKeepsSource() {
        for ((policy, _) in automaticDispositions) directory { root ->
            val (repository, task, source) = committedScan(root, policy)
            assertThrows(CancellationException::class.java) {
                repository.disposeCommittedSources(
                    task,
                    commitDisposition = {
                        if (policy == SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS) {
                            assertTrue(repository.trashDirectory.listFiles().orEmpty().any { it.name.startsWith(".entry-") })
                        }
                        repository.revokeRecoveryPermit(task.id)
                        throw CancellationException("stop before file mutation")
                    }
                ) {}
            }
            assertTrue(source.isFile)
            assertTrue(repository.trashDirectory.listFiles().orEmpty().isEmpty())
            assertTrue(repository.reconcilePendingDispositions().isEmpty())
        }
    }

    private val automaticDispositions = listOf(
        SolarSourcePolicy.DELETE_AFTER_COMMIT to SolarSourceDispositionState.DELETED,
        SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS to SolarSourceDispositionState.MOVED_TO_TRASH
    )

    private fun committedScan(
        root: File,
        policy: SolarSourcePolicy,
        legCount: Int = 1,
        beforeCommit: ((File) -> Unit)? = null,
        beforePublication: ((SolarScanRepository, String) -> Unit)? = null
    ): Triple<SolarScanRepository, SolarScanTaskRecord, File> {
        val recordings = File(root, "recordings").apply { mkdirs() }
        val captures = File(root, "captures").apply { mkdirs() }
        val sourceDir = File(recordings, "SER").apply { mkdirs() }
        val source = File(sourceDir, "task-leg-1.ser")
        val configuration = configuration().copy(sourcePolicy = policy)
        val repository = SolarScanRepository(recordings, captures)
        val taskId = repository.newTaskId()
        repository.authorizeDispositionRecovery(taskId)
        val staging = repository.createStaging(taskId)
        val product = File(staging, "products/task-leg-display.png")
        product.parentFile!!.mkdirs()
        writePng(product, 8, 1)
        val settings = SolarReconstructionSettings(
            products = setOf(SolarOutputProduct.DISPLAY_PNG)
        )
        val summary = SolarReconstructionSummary(
            source = source.name,
            displayPng = product.absolutePath,
            linearPng = null,
            linearFits = null,
            width = 8,
            height = 1,
            frameStride = 1,
            sourceFrames = 1,
            warnings = emptyList()
        )
        val record = SolarScanTaskRecord(
            taskId,
            Instant.now().toString(),
            Instant.now().toString(),
            "recovery",
            configuration,
            null,
            settings,
            (1..legCount).map { number ->
                val legSource = File(sourceDir, "task-leg-$number.ser")
                val identity = if (beforeCommit != null) {
                    val partial = File(sourceDir, ".${legSource.name}.partial")
                    writeSer(partial, frames = 3)
                    publishSealedSimulationSource(partial, legSource)
                } else {
                    legSource.writeBytes(ByteArray(64) { it.toByte() })
                    SolarSourceIdentity.of(legSource)
                }
                SolarScanLegRecord(
                    number,
                    SolarScanDirection.A_TO_B,
                    identity,
                    if (beforeCommit == null) 1 else 3,
                    0,
                    summary.copy(source = legSource.name, sourceFrames = if (beforeCommit == null) 1 else 3),
                    SolarSourceDispositionState.KEPT,
                    reconstructionSettings = settings
                )
            },
            policy,
            emptyList(),
            staging
        )
        beforeCommit?.invoke(source)
        beforePublication?.invoke(repository, taskId)
        val committed = repository.commit(staging, taskId, record)
        val task = checkNotNull(repository.readTask(committed.resultDirectory))
        return Triple(repository, task, source)
    }

    private fun writeSer(file: File, frames: Int) {
        val writer = SERWriter(file)
        try {
            writer.open(8, 1, PixelFormat.MONO8, cameraName = "test")
            repeat(frames) { index ->
                writer.writeFrame(FrameData(ByteArray(8), 8, 1, PixelFormat.MONO8, index.toLong(), index.toLong()))
            }
        } finally {
            writer.close()
        }
    }

    private fun configuration(durationMs: Long = 6_000, fps: Double = 200.0): SolarScanConfiguration =
        SolarScanConfiguration(
            endpointA = SolarScanEndpoint(12.0, 0.0),
            endpointB = SolarScanEndpoint(12.9, 0.0),
            scanDurationMs = durationMs,
            diskCrossingMs = durationMs * 3 / 4,
            frameRate = fps,
            exposureUs = 2_000,
            roi = SolarRoi(width = 320, height = 48)
        )

    private fun horizontalAsymmetry(file: File, width: Int, height: Int): Double {
        val reader = SERReader(file)
        try {
            assertTrue(reader.open())
            val frame = checkNotNull(reader.readLinearFrame(reader.header!!.frameCount / 2))
            require(height >= 3)
            val row = 2
            val left = frame.pixels[row * width + width / 4]
            val right = frame.pixels[row * width + width * 3 / 4]
            return (left - right).toDouble()
        } finally {
            reader.close()
        }
    }

    private fun nativeAnalysis(file: File): SolarSpectrumAnalysis {
        val native = SolarScanNative.analyze(file, testObserver)
        return SolarSpectrumAnalysis(
            trace = SolarTrace(
                width = native.width,
                constantRow = native.traceConstant,
                linear = native.traceLinear,
                quadratic = native.traceQuadratic,
                illuminatedFirst = native.illuminatedFirst,
                illuminatedLast = native.illuminatedLast
            ),
            confidence = native.confidence,
            lineDepth = native.lineDepth,
            suggestedRoi = SolarRoi(
                y = native.roiTop,
                width = native.width,
                height = native.roiHeight
            ),
            validColumns = native.validColumns,
            residualPixels = native.residualPixels
        )
    }

    private fun nativeReconstruct(
        source: File,
        settings: SolarReconstructionSettings,
        outputDirectory: File
    ): SolarReconstructionSummary {
        val native = SolarScanNative.reconstruct(
            source,
            outputDirectory,
            settings,
            memoryHeadroomBytes = 4L * 1024L * 1024L * 1024L,
            chinese = false,
            observer = testObserver
        )
        return SolarReconstructionSummary(
            source = source.name,
            displayPng = native.displayPng,
            linearPng = native.linearPng,
            linearFits = native.linearFits,
            width = native.width,
            height = native.height,
            frameStride = native.frameStride,
            sourceFrames = native.sourceFrames,
            warnings = native.warnings.toList(),
            corrections = SolarCorrectionReport(
                geometryApplied = native.geometryCorrectionApplied,
                bandingApplied = when (native.scanBandingApplied) {
                    1 -> SolarBandingAxis.VERTICAL
                    2 -> SolarBandingAxis.HORIZONTAL
                    3 -> SolarBandingAxis.AUTO
                    else -> SolarBandingAxis.OFF
                },
                bandingWidthPixels = native.scanBandingWidthPixels.takeIf { it > 0 },
                scanRowsAligned = native.scanRowsAligned,
                oscillationPeriodPixels = native.oscillationPeriodPixels
                    .takeIf { native.oscillationDetected },
                oscillationAmplitudePixels = native.oscillationAmplitudePixels
                    .takeIf { native.oscillationDetected },
                syntheticFlatStrength = native.syntheticFlatStrength.takeIf { it > 0 },
                physicalFlatApplied = native.physicalFlatApplied
            ),
            memory = SolarMemorySampleSummary(
                headroomBytes = 4L * 1024L * 1024L * 1024L,
                firstSampledAtUtc = Instant.now().toString(),
                sampleCount = 1,
                maxIntervalMs = 0L
            ),
            integrity = settings.products.associateWith { product ->
                val path = when (product) {
                    SolarOutputProduct.DISPLAY_PNG -> checkNotNull(native.displayPng)
                    SolarOutputProduct.LINEAR_PNG -> checkNotNull(native.linearPng)
                    SolarOutputProduct.LINEAR_FITS -> checkNotNull(native.linearFits)
                }
                SolarProductIntegrity.of(File(path))
            }
        )
    }

    private val testObserver = object : SolarNativeObserver {
        override fun isCancelled(): Boolean = false
        override fun onProgress(value: Float) = Unit
    }

    private fun writeTrashTime(file: File, timestamp: Long?) {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(file.name.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val marker = File(file.parentFile, ".entry-$key.json")
        val json = JSONObject().put("original", file.absolutePath)
        if (timestamp != null) json.put("trashedAtUtc", timestamp)
        marker.writeText(json.toString())
    }

    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("solar-scan").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writePng(file: File, width: Int, height: Int) {
        file.parentFile?.mkdirs()
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed, Deflater()).use { output ->
            repeat(height) {
                output.write(0)
                output.write(ByteArray(width))
            }
        }
        file.outputStream().buffered().use { output ->
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val header = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeInt(width)
                    data.writeInt(height)
                    data.writeByte(8)
                    data.writeByte(0)
                    data.writeByte(0)
                    data.writeByte(0)
                    data.writeByte(0)
                }
            }.toByteArray()
            writePngChunk(output, "IHDR", header)
            writePngChunk(output, "IDAT", compressed.toByteArray())
            writePngChunk(output, "IEND", ByteArray(0))
        }
    }

    private fun writePngChunk(output: java.io.OutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        DataOutputStream(output).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
            flush()
        }
    }
}
