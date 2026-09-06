package com.hi.khoangcachxe

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.WindowManager
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var detector: ObjectDetector? = null
    private val busy = AtomicBoolean(false)
    private val estimator = DistanceEstimator()
    private var focalPx = 0f
    private var lastSeen = 0L
    private var tone: ToneGenerator? = null
    private var lastBeepViolation = 0L
    private var currentSpeedKmh = -1f

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

        detector = ObjectDetection.getClient(ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects().enableClassification().build())

        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (need.isEmpty()) { startCamera(); startGps() } else permLauncher.launch(need.toTypedArray())
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            try {
                val provider = ProcessCameraProvider.getInstance(this).get()
                val sel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()

                val preview = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
                    .build().also { it.setSurfaceProvider(b.previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder().setResolutionSelector(sel)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor) { onFrame(it) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0f, object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (location.hasSpeed()) currentSpeedKmh = location.speed * 3.6f
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Suppress("DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            })
        } catch (e: Exception) {}
    }

    private fun onFrame(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) { proxy.close(); return }
        try {
            val bmp = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val img = if (rot == 0) bmp else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                bmp.recycle(); r
            }
            
            if (focalPx <= 0f) focalPx = computeFocalPx(img.width, img.height)
            
            detector?.process(InputImage.fromBitmap(img, 0))
                ?.addOnSuccessListener { objs ->
                    val best = pickFrontVehicle(objs, img.width, img.height)
                    runOnUiThread { render(best, img.width, img.height) }
                    img.recycle()
                }
                ?.addOnFailureListener { img.recycle() }
        } finally {
            proxy.close()
            busy.set(false)
        }
    }

    private fun pickFrontVehicle(objs: List<DetectedObject>, w: Int, h: Int): DetectedObject? {
        var best: DetectedObject? = null
        var bestScore = 0f
        
        for (o in objs) {
            val r = o.boundingBox
            val bw = r.width().toFloat()
            val bh = r.height().toFloat()
            if (bw < 8f || bh < 6f || bw > w * 0.92f) continue
            
            val cx = r.exactCenterX() / w
            val cy = r.exactCenterY() / h
            
            // Chỉ bám xe ở giữa khung (0.35-0.65) và phần dưới (0.4-1.0)
            if (cx < 0.35f || cx > 0.65f) continue
            if (cy < 0.4f || cy > 1.0f) continue
            
            val label = o.labels.firstOrNull()?.text ?: "obj"
            if (!isVehicle(label)) continue
            
            val score = bw / w * cy
            if (score > bestScore) {
                bestScore = score
                best = o
                estimator.vehicleWidthM = ObjectSize.getWidth(label)
            }
        }
        return best
    }

    private fun isVehicle(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("car") || l.contains("truck") || l.contains("bus") || l.contains("vehicle")
    }

    private fun render(obj: DetectedObject?, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        b.overlay.setImageSize(w, h)
        
        // Hiển thị GPS tốc độ
        val gpsSpeedStr = if (currentSpeedKmh > 0) String.format("GPS: %.0f km/h", currentSpeedKmh) else "GPS: chờ..."
        b.tvInfo.text = gpsSpeedStr
        
        if (obj == null) {
            b.tvMain.text = "-- m"
            b.tvMain.setTextColor(Color.WHITE)
            b.tvDetail.text = ""
            b.overlay.box = null
            b.overlay.distance = 0f
            b.overlay.currentSpeed = if (currentSpeedKmh > 0) currentSpeedKmh.toInt() else 80
            b.overlay.alert = false
            b.overlay.invalidate()
            return
        }
        
        lastSeen = now
        val box = obj.boundingBox
        val bw = box.width().toFloat()
        
        // Hiển thị khung bao
        b.overlay.box = box
        
        if (focalPx <= 0f) {
            b.overlay.invalidate()
            return
        }
        
        // ===== TÍNH KHOẢNG CÁCH 2 CÁCH =====
        
        // Cách 1: Từ camera (pixel -> meter)
        val res = estimator.update(bw, focalPx, now)
        val distCamera = if (res != null && res.distanceM.isFinite()) res.distanceM else 0f
        
        // Cách 2: Từ ruler (vị trí Y của xe)
        val rulerTop = 0.85f  // 0m (xe phía trước)
        val rulerBottom = 0.15f  // 110m
        val carBottomY = box.bottom.toFloat() / h
        val distRuler = if (carBottomY >= rulerBottom && carBottomY <= rulerTop) {
            (rulerTop - carBottomY) / (rulerTop - rulerBottom) * 110f
        } else if (carBottomY > rulerTop) 0f else 110f
        
        // Kết hợp: 50% camera + 50% ruler
        val distance = (distCamera * 0.5f + distRuler * 0.5f).coerceIn(0f, 110f)
        
        if (!distance.isFinite() || distance > 110f) {
            b.tvMain.text = "> 110 m"
            b.tvMain.setTextColor(Color.GRAY)
            b.tvDetail.text = ""
            b.overlay.distance = 110f
            b.overlay.alert = false
            b.overlay.invalidate()
            return
        }
        
        val objLabel = obj.labels.firstOrNull()?.text ?: "obj"
        val label = String.format("%.1f m", distance)
        
        b.tvMain.text = label
        b.tvMain.setTextColor(Color.WHITE)
        
        var detail = "$objLabel · Camera: ${distCamera.toInt()}m, Ruler: ${distRuler.toInt()}m"
        var alert = false
        
        if (currentSpeedKmh > 0) {
            val spd = currentSpeedKmh
            val min = getMinDist(spd)
            val spdInt = spd.toInt()
            val minInt = min.toInt()
            
            if (distance < min) {
                b.tvMain.setTextColor(Color.rgb(255, 60, 60))
                alert = true
                detail = "⚠️ VI PHẠM: ${distance.toInt()}m < ${minInt}m @ ${spdInt}km/h"
                
                val now2 = SystemClock.elapsedRealtime()
                if (now2 - lastBeepViolation > 1000) {
                    lastBeepViolation = now2
                    try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200) } catch (e: Exception) {}
                }
            } else {
                detail = "✓ AN TOÀN: ${distance.toInt()}m ≥ ${minInt}m @ ${spdInt}km/h"
            }
        } else {
            detail = "$objLabel · ${distance.toInt()}m"
        }
        
        b.tvDetail.text = detail
        b.overlay.distance = distance
        b.overlay.currentSpeed = if (currentSpeedKmh > 0) currentSpeedKmh.toInt() else 80
        b.overlay.alert = alert
        b.overlay.invalidate()
    }

    private fun computeFocalPx(w: Int, h: Int): Float {
        try {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) continue
                val f = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val sz = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val mm = if (w >= h) sz.width else sz.height
                if (mm > 0f && f > 0f) return f / mm * w
            }
        } catch (e: Exception) {}
        return (w / 2f) / kotlin.math.tan(Math.toRadians(30.0)).toFloat()
    }

    private fun getMinDist(speed: Float): Float = when {
        speed < 60 -> 35f
        speed < 80 -> 55f
        speed < 100 -> 70f
        else -> 100f
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
        tone?.release()
    }
}
