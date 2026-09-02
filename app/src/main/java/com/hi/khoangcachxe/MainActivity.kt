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
    private var modeVehicle = false
    private var lastBeepViolation = 0L
    private var currentSpeedKmh = -1f
    private val locListener = GpsListener { speed -> currentSpeedKmh = speed }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera()
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
            .enableMultipleObjects().enableClassification().build()
        detNear = ObjectDetection.getClient(opts)
        detFar = ObjectDetection.getClient(opts)

        b.btnModeVehicle.setOnClickListener { modeVehicle = true; reset() }
        b.btnModeObject.setOnClickListener { modeVehicle = false; reset() }

        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (need.isEmpty()) { startCamera(); startGps() } else permLauncher.launch(need.toTypedArray())
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun reset() {
        estimator.reset()
        widthHist.clear()
        lastBox = null
        lastSeen = 0L
        b.tvMain.text = "-- m"
        b.tvDetail.text = ""
        b.overlay.setResult(null, 0, 0, "", false)
        b.tvModeInfo.text = if (modeVehicle) "Chế độ xe chạy" else "Chế độ vật thể"
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            try {
                val provider = ProcessCameraProvider.getInstance(this).get()
                val sel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()

                val prev = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
                    .build().also { it.setSurfaceProvider(b.previewView.surfaceProvider) }

                val ana = ImageAnalysis.Builder().setResolutionSelector(sel)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                ana.setAnalyzer(cameraExecutor) { onFrame(it) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, ana)
                b.tvDetail.text = "Quay camera vào vật..."
            } catch (e: Exception) {
                b.tvDetail.text = "Lỗi: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0f, locListener)
        } catch (e: Exception) {}
    }

    private fun onFrame(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) { proxy.close(); return }
        val bmp = try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            if (rot == 0) raw else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                raw.recycle(); r
            }
        } catch (e: Exception) { busy.set(false); return } finally { proxy.close() }

        val w = bmp.width; val h = bmp.height
        if (focalPx <= 0f) focalPx = computeFocalPx(w, h)

        val near = detNear; val far = detFar
        if (near == null || far == null) { busy.set(false); return }

        val rw = (w * 0.34f).toInt(); val rh = (h * 0.42f).toInt()
        val rx = (w - rw) / 2; val ry = (h - rh) / 2
        val roi = Rect(rx, ry, rx + rw, ry + rh)

        near.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { nearObjs ->
            val crop = try { Bitmap.createBitmap(bmp, roi.left, roi.top, rw, rh) } catch (e: Exception) { null }
            if (crop == null) { render(pickBest(nearObjs, w, h), w, h); bmp.recycle(); busy.set(false) }
            else far.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { farObjs -> render(bestOf(nearObjs, farObjs, roi, w, h), w, h) }
                .addOnFailureListener { render(pickBest(nearObjs, w, h), w, h) }
                .addOnCompleteListener { crop.recycle(); bmp.recycle(); busy.set(false) }
        }.addOnFailureListener { bmp.recycle(); busy.set(false) }
    }

    private fun pickBest(objs: List<DetectedObject>, w: Int, h: Int): DetectedObject? {
        var best: DetectedObject? = null; var bestScore = 0f
        for (o in objs) {
            val r = o.boundingBox; val bw = r.width().toFloat(); val bh = r.height().toFloat()
            if (bw < 8f || bh < 6f || bw > w * 0.92f) continue
            val cx = r.exactCenterX() / w
            if (cx < 0.15f || cx > 0.85f || r.bottom < h * 0.25f) continue
            val label = o.labels.firstOrNull()?.text ?: "obj"
            if (modeVehicle && !isVehicle(label)) continue
            val score = bw / w * (1f - abs(cx - 0.5f))
            if (score > bestScore) { bestScore = score; best = o; estimator.vehicleWidthM = ObjectSize.getWidth(label) }
        }
        return best
    }

    private fun bestOf(nearObjs: List<DetectedObject>, farObjs: List<DetectedObject>, roi: Rect, w: Int, h: Int): DetectedObject? {
        val bestNear = pickBest(nearObjs, w, h)
        if (bestNear != null && bestNear.boundingBox.width() > w * 0.09f) return bestNear
        var best: DetectedObject? = null; var bestScore = 0f
        for (o in farObjs) {
            val r = o.boundingBox; val bw = r.width().toFloat(); val bh = r.height().toFloat()
            if (bw < 8f || bh < 6f) continue
            val mx = r.left + roi.left; val mw = r.right + roi.left - mx
            if (mw > w * 0.92f) continue
            val cx = (mx + r.right + roi.left) / 2f / w
            if (cx < 0.15f || cx > 0.85f) continue
            val label = o.labels.firstOrNull()?.text ?: "obj"
            if (modeVehicle && !isVehicle(label)) continue
            val score = bw / w
            if (score > bestScore) { bestScore = score; best = o; estimator.vehicleWidthM = ObjectSize.getWidth(label) }
        }
        return best
    }

    private fun isVehicle(label: String): Boolean = label.lowercase().let { it.contains("car") || it.contains("truck") || it.contains("bus") }

    private fun render(obj: DetectedObject?, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        b.overlay.setImageSize(w, h)
        if (obj == null) {
            if (now - lastSeen > 900) {
                lastBox = null; estimator.reset(); widthHist.clear()
                b.tvMain.text = "-- m"; b.tvMain.setTextColor(Color.WHITE)
                b.tvDetail.text = "Quay camera vào vật..."; b.overlay.setResult(null, w, h, "", false)
            }
            return
        }
        lastSeen = now; val box = obj.boundingBox; lastBox = box
        val bw = box.width().toFloat(); widthHist.addLast(Pair(now, bw))
        while (widthHist.isNotEmpty() && now - widthHist.first.first > 1500) widthHist.removeFirst()
        val res = estimator.update(bw, focalPx, now) ?: return
        val d = res.distanceM; val label = if (d > 110) "> 110 m" else String.format("%.2f m", d)
        val objLabel = obj.labels.firstOrNull()?.text ?: "obj"
        b.tvMain.text = label
        val det = "$objLabel · ±%.1fm".format(res.uncertaintyM)
        var alert = false
        if (modeVehicle && isVehicle(objLabel)) {
            val spd = if (currentSpeedKmh > 0) currentSpeedKmh else 80f
            val min = SafeDistance.getMinDistance(spd)
            if (d < min) {
                alert = true
                b.tvDetail.text = det + " · CẢNH BÁO: min ${min.toInt()}m @ ${spd.toInt()}km/h"
                b.tvMain.setTextColor(Color.rgb(255, 60, 60))
                if (now - lastBeepViolation > 1000) { lastBeepViolation = now; try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200) } catch (e: Exception) {} }
            } else {
                b.tvDetail.text = det + " · ok (min ${min.toInt()}m)"; b.tvMain.setTextColor(Color.WHITE)
            }
        } else {
            b.tvDetail.text = det; b.tvMain.setTextColor(Color.WHITE)
        }
        b.overlay.setResult(box, w, h, label, alert)
    }

    private fun computeFocalPx(w: Int, h: Int): Float {
        try {
            val cm = getSystemService(CameraManager::class.java)
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val mm = if (w >= h) sz.width else sz.height
                if (mm > 0f && f > 0f) return f / mm * w
            }
        } catch (e: Exception) {}
        return (w / 2f) / tan(Math.toRadians(30.0)).toFloat()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { getSystemService(LocationManager::class.java).removeUpdates(locListener) } catch (e: Exception) {}
        cameraExecutor.shutdown(); detNear?.close(); detFar?.close(); tone?.release()
    }
}

class GpsListener(val onSpeed: (Float) -> Unit) : LocationListener {
    override fun onLocationChanged(location: Location) {
        onSpeed(if (location.hasSpeed()) location.speed * 3.6f else -1f)
    }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
