package com.example.cameraxdemo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.example.cameraxdemo.databinding.ActivityMainBinding
import com.google.mlkit.vision.face.Face
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CameraXDemo"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private val faces = MutableLiveData<List<Face>>()

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (requestPermissions()) {
            // Wait for the views to be properly laid out for DisplayMetrics
            binding.viewFinder.post {
                startCamera()
            }
        }

        binding.cameraCaptureButton.setOnClickListener { takePhoto() }
        binding.cameraSwitchButton.setOnClickListener { switchLens() }

        outputDirectory = getOutputDirectory()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permissions not granted",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun switchLens() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        // Re-bind use cases for the newly selected camera
        bindCameraUseCases()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                FILENAME_FORMAT,
                Locale.CANADA
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            })
    }

    private fun startCamera() {
        // Retreat ProcessCameraProvider associated with the current process
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Camera provider is now guaranteed to be available
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val metrics = DisplayMetrics().also { binding.viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = binding.viewFinder.display.rotation

        // Preview
        val preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also {
                // Use the camera object to link our preview use case with the view
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        // Capture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        // Analyze
        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, FaceAnalyzer(faces))
            }

        faces.observe(this, Observer {
            processFaceResult(it)
        })

        inverseMirroredOverlay()

        // Select camera
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            // Unbind use cases before rebinding
            cameraProvider?.unbindAll()
            // Up to three use cases can be bounded to lifecycle at any time
            cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalyzer
            )

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun processFaceResult(faces: List<Face>) {
        if (faces.isNotEmpty()) {
            binding.overlay.clear()
            for (i in faces.indices) {
                val face = faces[i]
                val faceGraphic = FaceContourGraphic(binding.overlay)
                binding.overlay.add(faceGraphic)
                faceGraphic.updateFace(face)
            }
        }
    }

    private fun inverseMirroredOverlay() {
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            binding.overlay.scaleX = 1.0f
        } else if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            // Invert only for front facing camera
            binding.overlay.scaleX = -1.0f
        }
    }

    private fun requestPermissions(): Boolean {
        return if (allPermissionsGranted()) {
            true
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            false
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}