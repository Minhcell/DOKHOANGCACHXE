package com.hi.khoangcachxe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.WindowManager
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
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    companion object {
        const val MAX_RANGE_M = 110f
        const val ROI_W = 0.34f
        const val ROI_H = 0.42f
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var detNear: ObjectDetector? = null
    private var detFar: ObjectDetector? = null
    private val busy = AtomicBoolean(false)

    private val estimator = DistanceEstimator()

    private var focalPx = 0f
    private var lastBox: Rect? = null
    private var lastSeen = 0L
    private var tone: ToneGenerator? = null

    private val widthHist = ArrayDeque<Pair<Long, Float>>()

    // Chế độ: false = vật thể (mặc định), true = xe đang chạy
    private var modeVehicle = false
    private var lastBeepViolation = 0L
    
    // GPS: lấy tốc độ hiện tại (km/h)
    private var currentSpeedKmh = -1f
    private var lastLocationUpdate = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else -1f
            lastLocationUpdate = SystemClock.elapsedRealtime()
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Deprecated")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(this, "Ứng dụng cần quyền Camera", Toast.LENGTH_LONG).show()
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startGps()
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

        b.btnModeVehicle.setOnClickListener { setMode(true) }
        b.btnModeObject.setOnClickListener { setMode(false) }

        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (need.isEmpty()) { startCamera(); startGps() } else permLauncher.launch(need.toTypedArray())
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun setMode(vehicle: Boolean) {
        modeVehicle = vehicle
        estimator.reset()
        widthHist.clear()
        lastBox = null
        lastSeen = 0L
        b.tvMain.text = "-- m"
        b.tvDetail.text = ""
        b.overlay.setResult(null, 0, 0, "", false)
        b.tvModeInfo.text = if (vehicle) "Chế độ xe chạy" else "Chế độ vật thể"
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

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
                b.tvDetail.text = "Quay camera vào vật để đo khoảng cách..."
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
                b.tvInfo.text = String.format("Tầm: 1-110m · %dx%d · f=%.0fpx", w, h, fp)
            }
        }

        val near = detNear
        val far = detFar
        if (near == null || far == null) { busy.set(false); return }

        val rw = (w * ROI_W).toInt()
        val rh = (h * ROI_H).toInt()
        val rx = (w - rw) / 2
        val ry = (h - rh) / 2
        val roi = Rect(rx, ry, rx + rw, ry + rh)

        near.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { nearObjs ->
                val crop = try { Bitmap.createBitmap(bmp, roi.left, roi.top, rw, rh) } catch (e: Exception) { null }
                if (crop == null) {
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
        val bestNear = pickBest(nearObjs, w, h)
        val best: DetectedObject?

        best = if (bestNear != null && bestNear.boundingBox.width() > w * 0.09f) {
            bestNear
        } else {
            pickBestFar(farObjs, roi, w, h)
        }

        runOnUiThread { render(best, w, h) }
        busy.set(false)
    }

    private fun pickBest(objs: List<DetectedObject>, w: Int, h: Int): DetectedObject? {
        var best: DetectedObject? = null
        var bestScore = 0f

        for (o in objs) {
            val r = o.boundingBox
            val bw = r.width().toFloat()
            val bh = r.height().toFloat()
            if (bw < 8f || bh < 6f || bw > w * 0.92f) continue

            val cx = r.exactCenterX() / w
            if (cx < 0.15f || cx > 0.85f) continue
            if (r.bottom < h * 0.25f) continue

            val label = o.labels.firstOrNull()?.text ?: "object"
            val labelLower = label.lowercase()

            // Chế độ xe: chỉ lấy loại xe
            if (modeVehicle) {
                if (!labelLower.contains("car") && !labelLower.contains("truck") && 
                    !labelLower.contains("bus") && !labelLower.contains("vehicle")) {
                    continue
                }
            }

            val score = bw / w * (1f - abs(cx - 0.5f))
            if (score > bestScore) {
                bestScore = score
                best = o
                estimator.vehicleWidthM = ObjectSize.getWidth(label)
            }
        }
        return best
    }

    private fun pickBestFar(objs: List<DetectedObject>, roi: Rect, w: Int, h: Int): DetectedObject? {
        var best: DetectedObject? = null
        var bestScore = 0f

        for (o in objs) {
            val r = o.boundingBox
            val bw = r.width().toFloat()
            val bh = r.height().toFloat()
            if (bw < 8f || bh < 6f) continue

            val mappedLeft = r.left + roi.left
            val mappedTop = r.top + roi.top
            val mappedRight = r.right + roi.left
            val mappedBottom = r.bottom + roi.top
            val mappedW = mappedRight - mappedLeft

            if (mappedW > w * 0.92f) continue

            val cx = (mappedLeft + mappedRight) / 2f / w
            if (cx < 0.15f || cx > 0.85f) continue

            val label = o.labels.firstOrNull()?.text ?: "object"
            val labelLower = label.lowercase()

            if (modeVehicle) {
                if (!labelLower.contains("car") && !labelLower.contains("truck") && 
                    !labelLower.contains("bus") && !labelLower.contains("vehicle")) {
                    continue
                }
            }

            val score = bw / w
            if (score > bestScore) {
                bestScore = score
                best = o
                estimator.vehicleWidthM = ObjectSize.getWidth(label)
            }
        }
        return best
    }

    private fun render(obj: DetectedObject?, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        b.overlay.setImageSize(w, h)

        if (obj == null) {
            if (now - lastSeen > 900) {
                lastBox = null
                estimator.reset()
                widthHist.clear()
                b.tvMain.text = "-- m"
                b.tvMain.setTextColor(Color.WHITE)
                b.tvDetail.text = "Quay camera vào vật để đo khoảng cách"
                b.overlay.setResult(null, w, h, "", false)
            }
            return
        }

        lastSeen = now
        val box = obj.boundingBox
        lastBox = box

        val bw = box.width().toFloat()
        widthHist.addLast(Pair(now, bw))
        while (widthHist.isNotEmpty() && now - widthHist.first.first > 1500) widthHist.removeFirst()

        val res = estimator.update(bw, focalPx, now)
        if (res == null) { b.overlay.setResult(box, w, h, "", false); return }

        val d = res.distanceM
        val overRange = d > MAX_RANGE_M
        val label = if (overRange) "> 110 m" else String.format("%.2f m", d)

        val objLabel = obj.labels.firstOrNull()?.text ?: "vat"
        val isVehicle = objLabel.lowercase().contains("car") || objLabel.lowercase().contains("truck") || 
                       objLabel.lowercase().contains("bus") || objLabel.lowercase().contains("vehicle")

        b.tvMain.text = label

        val detail = StringBuilder()
        detail.append("$objLabel · ± %.2f m".format(res.uncertaintyM))

        // Cảnh báo khoảng cách an toàn nếu chế độ xe và là xe
        var isAlert = false
        if (modeVehicle && isVehicle) {
            val speedKmh = if (currentSpeedKmh > 0f) currentSpeedKmh else 80f  // từ GPS, fallback 80 km/h
            val minDist = SafeDistance.getMinDistance(speedKmh)
            val violation = SafeDistance.isViolation(d, speedKmh)
            
            if (violation) {
                isAlert = true
                val spd = if (currentSpeedKmh > 0f) String.format("%.0f", currentSpeedKmh) else "?"
                detail.append(" · CẢNH BÁO @ ${spd}km/h: tối thiểu $minDist m")
                b.tvMain.setTextColor(Color.rgb(255, 60, 60))
                
                val now2 = SystemClock.elapsedRealtime()
                if (now2 - lastBeepViolation > 1000) {
                    lastBeepViolation = now2
                    try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200) } catch (e: Exception) {}
                }
            } else {
                b.tvMain.setTextColor(if (overRange) Color.rgb(160, 160, 160) else Color.WHITE)
                detail.append(" · an toàn (tối thiểu $minDist m)")
            }
        } else {
            b.tvMain.setTextColor(if (overRange) Color.rgb(160, 160, 160) else Color.WHITE)
        }

        b.tvDetail.text = detail.toString()
        b.overlay.setResult(box, w, h, label, isAlert)
    }

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

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener)
        } catch (e: Exception) {
            Toast.makeText(this, "GPS không khả dụng", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        try {
            getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        } catch (e: Exception) {}
        super.onDestroy()
        cameraExecutor.shutdown()
        detNear?.close()
        detFar?.close()
        tone?.release()
    }
}
