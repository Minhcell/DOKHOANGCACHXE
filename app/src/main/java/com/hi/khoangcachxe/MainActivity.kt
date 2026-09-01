package com.hi.khoangcachxe

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.WindowManager
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.hi.khoangcachxe.databinding.ActivityMainBinding
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    companion object {
        /** Tầm đo tối đa hiển thị. */
        const val MAX_RANGE_M = 110f
        /** Bề rộng vùng cắt để soi xe ở xa, theo tỉ lệ khung hình. */
        const val ROI_W = 0.34f
        const val ROI_H = 0.42f
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var detNear: ObjectDetector? = null   // quét toàn khung: xe gần
    private var detFar: ObjectDetector? = null    // quét vùng cắt: xe xa
    private val busy = AtomicBoolean(false)

    private val estimator = DistanceEstimator()

    private var focalPx = 0f
    private var lastBox: Rect? = null
    private var lastSeen = 0L
    private var lastBeep = 0L
    private var tone: ToneGenerator? = null

    private var ownSpeedMs = -1f
    private var lastLocation: Location? = null

    /** false = đo xe phía trước, true = đo vật bất kỳ. */
    private var objectMode = false
    /** Bề ngang thật của vật cần đo, ở chế độ vật thể (mét). */
    private var objWidthM = 1.22f
    /** Vật đang được bám ở chế độ vật thể. */
    private var locked: Rect? = null
    private var pendingTapX = -1f
    private var pendingTapY = -1f

    /** Kích thước (cm) tương ứng với danh sách chọn nhanh trong arrays.xml. */
    private val presetCm = floatArrayOf(0f, 73f, 96f, 111f, 122f, 144f, 54f, 82f, 45f, 70f, 75f, 30f)

    /** Lịch sử bề ngang khung bao gần đây, dùng cho hiệu chỉnh tự động. */
    private val widthHist = ArrayDeque<Pair<Long, Float>>()

    private var calibStage = 0
    private var calibStartW = 0f
    private var calibStartLoc: Location? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(this, "Ứng dụng cần quyền Camera", Toast.LENGTH_LONG).show()
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startGps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraExecutor = Executors.newSingleThreadExecutor()
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: Exception) { null }

        val opts = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        detNear = ObjectDetection.getClient(opts)
        detFar = ObjectDetection.getClient(opts)

        setupControls()

        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (need.isEmpty()) { startCamera(); startGps() } else permLauncher.launch(need.toTypedArray())
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------- điều khiển

    private fun setupControls() {
        b.sbWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                val wm = (140 + p) / 100f
                if (!objectMode) estimator.vehicleWidthM = wm
                b.tvWidth.text = String.format("Bề ngang xe trước: %.2f m", wm)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        b.sbCalib.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                estimator.calibration = (70 + p) / 100f          // 0.70 .. 1.30
                b.tvCalib.text = String.format("Hiệu chỉnh: %.2f", estimator.calibration)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        b.sbWidth.progress = 40
        b.sbCalib.progress = 30

        b.btnCar.setOnClickListener { b.sbWidth.progress = 40 }     // 1.80 m
        b.btnSuv.setOnClickListener { b.sbWidth.progress = 55 }     // 1.95 m
        b.btnTruck.setOnClickListener { b.sbWidth.progress = 105 }  // 2.45 m
        b.btnCalib.setOnClickListener { onCalibClick() }

        b.btnMode.setOnClickListener { setMode(!objectMode) }

        b.etObjW.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val cm = e?.toString()?.toFloatOrNull() ?: return
                if (cm < 2f || cm > 1500f) return
                objWidthM = cm / 100f
                if (objectMode) {
                    estimator.vehicleWidthM = objWidthM
                    estimator.reset()
                }
            }
        })

        b.spPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos <= 0 || pos >= presetCm.size) return
                b.etObjW.setText(presetCm[pos].toInt().toString())
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.btnCaliper.setOnClickListener {
            b.overlay.caliperEnabled = !b.overlay.caliperEnabled
            b.overlay.resetCaliper()
            estimator.reset()
            locked = null
            b.btnCaliper.text =
                if (b.overlay.caliperEnabled) "Thước kẹp thủ công: BẬT" else "Thước kẹp thủ công: TẮT"
            b.tvObjHint.text =
                if (b.overlay.caliperEnabled) "Kéo hai vạch vàng trùng hai mép của vật"
                else "Chạm vào vật trên màn hình để chọn"
        }

        b.overlay.onTap = { x, y ->
            if (objectMode) { pendingTapX = x; pendingTapY = y }
        }

        b.tvInfo.text = "Tầm đo: 1 - 110 m"
    }

    /** Chuyển giữa chế độ đo xe phía trước và chế độ đo vật bất kỳ. */
    private fun setMode(toObject: Boolean) {
        objectMode = toObject
        locked = null
        lastBox = null
        pendingTapX = -1f
        b.overlay.caliperEnabled = false
        b.overlay.resetCaliper()
        b.btnCaliper.text = "Thước kẹp thủ công: TẮT"
        estimator.reset()

        if (objectMode) {
            b.btnMode.text = "Chế độ: Vật bất kỳ"
            b.panelObject.visibility = View.VISIBLE
            b.tvInfo.text = "Đo vật: nhập bề ngang thật rồi chạm vào vật"
            estimator.vehicleWidthM = objWidthM
            // vật đứng yên, người cầm máy -> làm mượt mạnh hơn
            estimator.processAccel = 0.6f
        } else {
            b.btnMode.text = "Chế độ: Xe đang chạy"
            b.panelObject.visibility = View.GONE
            b.tvInfo.text = "Tầm đo: 1 - 110 m"
            estimator.vehicleWidthM = (140 + b.sbWidth.progress) / 100f
            estimator.processAccel = 3.0f
        }
        b.tvMain.text = "-- m"
        b.overlay.setResult(null, 0, 0, "", false)
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                // Độ phân giải cao là điều kiện bắt buộc để đo xa: ở 110 m một
                // chiếc xe con chỉ rộng ~26 px trên khung 1920, còn trên khung
                // 640 thì chỉ ~9 px - không đủ để đo.
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    ).build()

                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build()
                    ).build()
                    .also { it.setSurfaceProvider(b.previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> onFrame(proxy) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                b.tvDetail.text = "Đang tìm xe phía trước..."
            } catch (e: Exception) {
                b.tvDetail.text = "Lỗi camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) { proxy.close(); return }

        val bmp: Bitmap
        try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            bmp = if (rot == 0) raw else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                raw.recycle()
                r
            }
        } catch (e: Exception) {
            busy.set(false)
            return
        } finally {
            proxy.close()
        }

        val w = bmp.width
        val h = bmp.height
        if (focalPx <= 0f) {
            focalPx = computeFocalPx(w, h)
            val fp = focalPx
            runOnUiThread {
                b.tvInfo.text = String.format(
                    "Tầm đo: 1 - 110 m · khung %dx%d · f=%.0f px", w, h, fp
                )
            }
        }

        // Thước kẹp thủ công: không cần nhận diện, chỉ lấy khoảng cách hai vạch.
        if (objectMode && b.overlay.caliperEnabled) {
            runOnUiThread { renderCaliper(w, h) }
            bmp.recycle()
            busy.set(false)
            return
        }

        val near = detNear
        val far = detFar
        if (near == null || far == null) { busy.set(false); return }

        // Vùng cắt bám theo vị trí xe lần trước; mặc định là giữa khung hình,
        // hơi cao hơn tâm một chút vì xe ở xa nằm gần đường chân trời.
        val cx = lastBox?.exactCenterX() ?: (w * 0.5f)
        val cy = lastBox?.exactCenterY() ?: (h * 0.47f)
        val rw = (w * ROI_W).roundToInt()
        val rh = (h * ROI_H).roundToInt()
        val rx = (cx - rw / 2f).roundToInt().coerceIn(0, w - rw)
        val ry = (cy - rh / 2f).roundToInt().coerceIn(0, h - rh)
        val roi = Rect(rx, ry, rx + rw, ry + rh)

        near.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { nearObjs ->
                val crop = try {
                    Bitmap.createBitmap(bmp, roi.left, roi.top, rw, rh)
                } catch (e: Exception) { null }

                if (crop == null || objectMode) {
                    crop?.recycle()
                    finishFrame(nearObjs, emptyList(), roi, w, h)
                    bmp.recycle()
                } else {
                    far.process(InputImage.fromBitmap(crop, 0))
                        .addOnSuccessListener { farObjs -> finishFrame(nearObjs, farObjs, roi, w, h) }
                        .addOnFailureListener { finishFrame(nearObjs, emptyList(), roi, w, h) }
                        .addOnCompleteListener { crop.recycle(); bmp.recycle() }
                }
            }
            .addOnFailureListener { bmp.recycle(); busy.set(false) }
    }

    private fun finishFrame(
        nearObjs: List<DetectedObject>,
        farObjs: List<DetectedObject>,
        roi: Rect,
        w: Int,
        h: Int
    ) {
        // Đưa toạ độ của vùng cắt về toạ độ khung hình đầy đủ
        val mapped = farObjs.map {
            Rect(
                it.boundingBox.left + roi.left, it.boundingBox.top + roi.top,
                it.boundingBox.right + roi.left, it.boundingBox.bottom + roi.top
            )
        }
        val nearRects = nearObjs.map { it.boundingBox }

        val best = if (objectMode) {
            pickObject(nearRects, w, h)
        } else {
            val bestNear = pickBest(nearRects, w, h)
            // Xe gần thì quét toàn khung đã đủ; chỉ khi khung bao nhỏ (xe ở xa) mới
            // ưu tiên kết quả từ vùng cắt vì ở đó xe được phóng to ~3 lần.
            if (bestNear != null && bestNear.width() > w * 0.09f) bestNear
            else pickBest(mapped, w, h) ?: bestNear
        }

        runOnUiThread { render(best, w, h) }
        busy.set(false)
    }

    private fun render(box: Rect?, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        b.overlay.setImageSize(w, h)

        if (box == null) {
            if (now - lastSeen > 900) {
                lastBox = null
                if (objectMode) locked = null
                estimator.reset()
                widthHist.clear()
                b.tvMain.text = "-- m"
                b.tvMain.setTextColor(Color.WHITE)
                b.tvDetail.text =
                    if (objectMode) "Chưa chọn được vật - chạm vào vật, hoặc bật thước kẹp"
                    else "Không thấy xe phía trước" + gpsSuffix()
                b.overlay.setResult(null, w, h, "", false)
            }
            return
        }
        lastSeen = now
        lastBox = box

        val bw = box.width().toFloat()
        widthHist.addLast(Pair(now, bw))
        while (widthHist.isNotEmpty() && now - widthHist.first.first > 1500) widthHist.removeFirst()

        val res = estimator.update(bw, focalPx, now)
        if (res == null) { b.overlay.setResult(box, w, h, "", false); return }

        val d = res.distanceM
        val overRange = d > MAX_RANGE_M
        val label = if (overRange) "> 110 m" else formatDistance(d)

        if (objectMode) {
            b.tvMain.text = label
            b.tvMain.setTextColor(if (overRange) Color.rgb(160, 160, 160) else Color.WHITE)
            b.tvDetail.text = String.format(
                "± %.2f m · bề ngang vật %.0f cm · %d px",
                res.uncertaintyM, estimator.vehicleWidthM * 100f, box.width()
            )
            b.overlay.setResult(box, w, h, label, false)
            return
        }

        val safeD = if (ownSpeedMs > 1.5f) ownSpeedMs * 2f else -1f
        val alert = !overRange &&
                ((res.ttcS in 0.1f..3.0f) || (safeD > 0 && d < safeD * 0.8f))

        b.tvMain.text = label
        b.tvMain.setTextColor(
            when {
                alert -> Color.rgb(255, 80, 80)
                overRange -> Color.rgb(160, 160, 160)
                else -> Color.WHITE
            }
        )

        val sb = StringBuilder()
        if (!overRange) sb.append(String.format("± %.1f m · ", res.uncertaintyM))
        sb.append(
            when {
                res.closingMs > 0.3f -> String.format("tiến gần %.0f km/h", res.closingMs * 3.6f)
                res.closingMs < -0.3f -> String.format("giãn ra %.0f km/h", -res.closingMs * 3.6f)
                else -> "khoảng cách ổn định"
            }
        )
        if (res.ttcS in 0.1f..30f) sb.append(String.format(" · va chạm sau %.1f s", res.ttcS))
        if (safeD > 0) sb.append(String.format(" · an toàn ≥ %.0f m", safeD))
        sb.append(gpsSuffix())
        b.tvDetail.text = sb.toString()

        b.overlay.setResult(box, w, h, label, alert)

        if (alert && now - lastBeep > 1500) {
            lastBeep = now
            try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200) } catch (e: Exception) {}
        }
    }

    /**
     * Tiêu cự quy đổi ra pixel, lấy từ thông số phần cứng của camera sau.
     * Khung 16:9 trên hầu hết máy là cắt bớt chiều dọc của cảm biến, chiều ngang
     * vẫn dùng trọn bề ngang cảm biến, nên công thức theo bề ngang là đúng.
     */
    private fun computeFocalPx(w: Int, h: Int): Float {
        try {
            val cm = getSystemService(CameraManager::class.java)
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val mm = if (w >= h) size.width else size.height
                if (mm > 0f && f > 0f) return f / mm * w
            }
        } catch (e: Exception) {
        }
        return (w / 2f) / tan(Math.toRadians(30.0)).toFloat()
    }

    /** Hiển thị cm khi ở gần, m khi ở xa. */
    private fun formatDistance(d: Float): String =
        if (d < 1f) String.format("%.0f cm", d * 100f)
        else if (d < 10f) String.format("%.2f m", d)
        else String.format("%.1f m", d)

    /** Đo bằng thước kẹp thủ công: bề ngang chính là khoảng cách hai vạch. */
    private fun renderCaliper(w: Int, h: Int) {
        b.overlay.setImageSize(w, h)
        val px = b.overlay.caliperWidthPx()
        if (px < 4f) {
            b.tvMain.text = "-- m"
            b.tvDetail.text = "Kéo hai vạch vàng trùng hai mép của vật"
            b.overlay.postInvalidate()
            return
        }
        val res = estimator.update(px, focalPx, SystemClock.elapsedRealtime())
        if (res == null) { b.overlay.postInvalidate(); return }

        val label = if (res.distanceM > MAX_RANGE_M) "> 110 m" else formatDistance(res.distanceM)
        b.tvMain.text = label
        b.tvMain.setTextColor(Color.WHITE)
        b.tvDetail.text = String.format(
            "± %.2f m · bề ngang vật %.0f cm · %.0f px",
            res.uncertaintyM, estimator.vehicleWidthM * 100f, px
        )
        b.overlay.setResult(null, w, h, label, false)
    }

    /**
     * Chọn vật ở chế độ đo vật bất kỳ.
     * Ưu tiên: vật vừa được chạm > vật đang bám > vật lớn nhất gần tâm.
     * Không áp dụng các ràng buộc dành cho xe trên đường (nằm dưới, tỉ lệ ngang).
     */
    private fun pickObject(rects: List<Rect>, w: Int, h: Int): Rect? {
        if (rects.isEmpty()) return null

        if (pendingTapX >= 0f) {
            val tx = pendingTapX
            val ty = pendingTapY
            pendingTapX = -1f
            val hit = rects.filter { it.contains(tx.toInt(), ty.toInt()) }
                .minByOrNull { it.width() * it.height() }
                ?: rects.minByOrNull {
                    val dx = it.exactCenterX() - tx
                    val dy = it.exactCenterY() - ty
                    dx * dx + dy * dy
                }
            if (hit != null) {
                locked = hit
                estimator.reset()
                return hit
            }
        }

        locked?.let { lk ->
            val near = rects.minByOrNull {
                val dx = it.exactCenterX() - lk.exactCenterX()
                val dy = it.exactCenterY() - lk.exactCenterY()
                dx * dx + dy * dy
            }
            if (near != null) {
                val dx = abs(near.exactCenterX() - lk.exactCenterX()) / w
                val dy = abs(near.exactCenterY() - lk.exactCenterY()) / h
                if (dx < 0.25f && dy < 0.25f) { locked = near; return near }
            }
        }

        return rects.filter { it.width() >= 10 && it.height() >= 10 }
            .maxByOrNull {
                val cx = it.exactCenterX() / w
                it.width().toFloat() * (1f - abs(cx - 0.5f))
            }
    }

    private fun pickBest(rects: List<Rect>, w: Int, h: Int): Rect? {
        var best: Rect? = null
        var bestScore = 0f
        for (r in rects) {
            val bw = r.width().toFloat()
            val bh = r.height().toFloat()
            if (bw < 8f || bh < 6f || bw > w * 0.92f) continue
            val ar = bw / bh
            if (ar < 0.55f || ar > 3.6f) continue
            val cx = r.exactCenterX() / w
            if (cx < 0.18f || cx > 0.82f) continue
            if (r.bottom < h * 0.30f) continue
            // gần tâm + đủ lớn; nếu đang bám một xe thì ưu tiên xe ở gần vị trí cũ
            var score = (bw / w) * (1f - abs(cx - 0.5f))
            lastBox?.let { lb ->
                val dx = abs(r.exactCenterX() - lb.exactCenterX()) / w
                val dy = abs(r.exactCenterY() - lb.exactCenterY()) / h
                if (dx < 0.15f && dy < 0.15f) score *= 2.5f
            }
            if (score > bestScore) { bestScore = score; best = r }
        }
        return best
    }

    // ------------------------------------------------- hiệu chỉnh tự động GPS

    private fun avgWidth(): Float {
        if (widthHist.isEmpty()) return 0f
        var s = 0f
        for (p in widthHist) s += p.second
        return s / widthHist.size
    }

    private fun onCalibClick() {
        val w = avgWidth()
        val loc = lastLocation
        if (w <= 0f) { toast("Chưa bám được xe phía trước"); return }
        if (loc == null) { toast("Chưa có tín hiệu GPS"); return }

        if (calibStage == 0) {
            calibStartW = w
            calibStartLoc = Location(loc)
            calibStage = 1
            b.btnCalib.text = "Bấm lần 2 (sau khi tới gần)"
            b.tvCalibInfo.text = "Đã ghi mốc 1. Chạy tới gần xe đó thêm ≥ 10 m rồi bấm lại."
            return
        }

        val start = calibStartLoc
        calibStage = 0
        b.btnCalib.text = "Hiệu chỉnh tự động bằng GPS"
        if (start == null) return

        val ds = start.distanceTo(loc)
        if (ds < 8f) { b.tvCalibInfo.text = "Quãng đường quá ngắn (${ds.roundToInt()} m), cần ≥ 10 m."; return }
        if (w <= calibStartW * 1.05f) { b.tvCalibInfo.text = "Xe trước chưa to lên rõ, thử lại."; return }

        // d = k / w  =>  d1 - d2 = k(1/w1 - 1/w2) = quãng đường đã đi
        val k = ds / (1f / calibStartW - 1f / w)
        val base = estimator.vehicleWidthM * focalPx
        if (base <= 0f) return
        val newCalib = (k / base).coerceIn(0.7f, 1.3f)
        b.sbCalib.progress = ((newCalib * 100f) - 70f).roundToInt().coerceIn(0, 60)
        b.tvCalibInfo.text = String.format(
            "Đã hiệu chỉnh: %.2f (đi được %.0f m)", newCalib, ds
        )
        estimator.reset()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun gpsSuffix(): String =
        if (ownSpeedMs >= 0f) String.format(" · xe mình %.0f km/h", ownSpeedMs * 3.6f) else ""

    // -------------------------------------------------------------------- GPS

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            ownSpeedMs = if (location.hasSpeed()) location.speed else -1f
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, gpsListener)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { getSystemService(LocationManager::class.java).removeUpdates(gpsListener) } catch (e: Exception) {}
        cameraExecutor.shutdown()
        detNear?.close()
        detFar?.close()
        tone?.release()
    }
}
