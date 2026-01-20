/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reilandeubank.unprocess.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.Toast
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.reilandeubank.unprocess.utils.computeExifOrientation
import com.reilandeubank.unprocess.utils.getPreviewOutputSize
import com.reilandeubank.unprocess.utils.OrientationLiveData
import com.reilandeubank.unprocess.CameraActivity
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.Date
import java.util.Locale
import kotlin.RuntimeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.OutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.LinearLayout
import android.hardware.camera2.CameraMetadata

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics
        get() = cameraManager.getCameraCharacteristics(currentCameraId)

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // "Shutter closed" animation: Set overlay to opaque black and keep it
            // This simulates the shutter closing and processing, hiding the freeze
            fragmentCameraBinding.overlay.background = Color.BLACK.toDrawable()
            fragmentCameraBinding.overlay.alpha = 1.0f
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Job used to manage camera initialization */
    private var cameraJob: kotlinx.coroutines.Job? = null

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    private var currentCameraId: String = ""
    private var isJpeg: Boolean = false
    private var flashMode: Int = CaptureRequest.CONTROL_AE_MODE_ON

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentCameraId = args.cameraId
        isJpeg = args.convertToJpeg
        updateModeToggleUI()
        updateFlashUI()
        setupLensSelector()

        fragmentCameraBinding.modeToggle?.setOnClickListener {
            isJpeg = !isJpeg
            updateModeToggleUI()
        }

        fragmentCameraBinding.flashToggle?.setOnClickListener {
            flashMode = if (flashMode == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
                CaptureRequest.CONTROL_AE_MODE_ON
            } else {
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            }
            updateFlashUI()
            
            // Try to update the existing session if possible
            try {
                if (::session.isInitialized) {
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                            set(CaptureRequest.CONTROL_AE_MODE, flashMode)
                            // We don't use TORCH for standard flash toggle, common camera apps use strobe
                        }
                    session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
                } else {
                    initializeCamera()
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to update flash mode on existing session, re-initializing", exc)
                initializeCamera()
            }
        }

        val navOffsetListener = androidx.core.view.OnApplyWindowInsetsListener { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.translationX = (-systemBars.right).toFloat()
            v.translationY = (-systemBars.bottom).toFloat()
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(fragmentCameraBinding.captureButton, navOffsetListener)
        fragmentCameraBinding.galleryButton?.let {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(it, navOffsetListener)
        }

        fragmentCameraBinding.galleryButton?.setOnClickListener {
            openRecentPhoto()
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit
            override fun surfaceCreated(holder: SurfaceHolder) {
                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }

        // Capture button listener is handled in initializeCamera once the session is ready
    }

    private fun updateModeToggleUI() {
        fragmentCameraBinding.modeToggle?.text = if (isJpeg) "JPEG" else "RAW"
    }

    private fun updateFlashUI() {
        val iconRes = if (flashMode == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
            R.drawable.ic_flash_on
        } else {
            R.drawable.ic_flash_off
        }
        fragmentCameraBinding.flashToggle?.text = ""
        fragmentCameraBinding.flashToggle?.setIconResource(iconRes)
    }

    private var allCameraIds: List<String> = emptyList()

    private fun setupLensSelector() {
        val container = fragmentCameraBinding.lensSelectorContainer
        container?.removeAllViews()

        val ids = mutableListOf<String>()
        Log.d(TAG, "All cameras found on device: ${cameraManager.cameraIdList.joinToString()}")
        
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            Log.d(TAG, "Camera ID: $id, Facing: $facing, Focal Lengths: ${focalLengths?.joinToString()}")
            
            // Check if it's a logical camera and has physical IDs
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                characteristics.physicalCameraIds
            } else {
                emptySet()
            }
            
            Log.d(TAG, "Camera ID: $id, Facing: $facing, Physical IDs: ${physicalIds.joinToString()}")
            
            if (physicalIds.isNotEmpty()) {
                // Only include physical IDs that can be opened directly (are in the main camera list)
                val openablePhysicalIds = physicalIds.filter { cameraManager.cameraIdList.contains(it) }
                if (openablePhysicalIds.isNotEmpty()) {
                    ids.addAll(openablePhysicalIds)
                } else {
                    // Fallback: If no physical cameras are directly openable, use the logical camera ID
                    ids.add(id)
                }
            } else {
                ids.add(id)
            }
        }
        
        // Detect unique cameras based on ID and focal lengths
        val uniqueCameras = mutableListOf<Triple<String, Float, Boolean>>()
        ids.distinct().forEach { id ->
            val ch = cameraManager.getCameraCharacteristics(id)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val isLogical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ch.physicalCameraIds.isNotEmpty()
            } else false
            
            Log.d(TAG, "Processing ID for selector: $id, focal: $focal, isLogical: $isLogical")
            uniqueCameras.add(Triple(id, focal, isLogical))
        }
        
        // Filter out the logical camera if its physical counterparts are already present
        val filteredCameras = uniqueCameras.filter { triple ->
            if (triple.third) { // If it's logical
                val physicalsOfThis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cameraManager.getCameraCharacteristics(triple.first).physicalCameraIds
                } else emptySet()
                // If any of its physical children are in our list, exclude the logical one to avoid duplicates
                physicalsOfThis.none { pId -> uniqueCameras.any { it.first == pId } }
            } else true
        }

        val finalCameras = mutableListOf<Triple<String, Float, String>>() // ID, Focal, Label
        
        // 1. Sort the unique cameras by focal length
        val sortedCameras = filteredCameras.sortedBy { it.second }
        
        // 2. Build labels with more detail and avoid duplicates
        val usedLabels = mutableSetOf<String>()
        sortedCameras.forEach { triple ->
            val id = triple.first
            val focal = triple.second
            val ch = cameraManager.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            
            var label = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                "F"
            } else {
                when {
                    focal == 0f -> "CAM $id"
                    focal < 2.5f -> "0.5x"
                    focal < 3.5f -> "0.6x"
                    focal < 9f -> "1x"
                    focal < 12f -> "2x"
                    focal < 20f -> "3x"
                    focal < 30f -> "5x"
                    else -> "${(focal / 4.3f).toInt()}x"
                }
            }
            
            // If label is duplicate, add index or ID
            if (usedLabels.contains(label)) {
                label = "$label ($id)"
            }
            usedLabels.add(label)
            finalCameras.add(Triple(id, focal, label))
        }

        allCameraIds = finalCameras.map { it.first }
        
        Log.d(TAG, "Final processed cameras: ${finalCameras.joinToString()}")

        if (allCameraIds.isEmpty()) {
            fragmentCameraBinding.lensSelectorCard?.visibility = View.GONE
            return
        }
        fragmentCameraBinding.lensSelectorCard?.visibility = View.VISIBLE

        finalCameras.forEach { (id, _, label) ->
            val button = com.google.android.material.button.MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    44.dpToPx(),
                    44.dpToPx()
                ).apply {
                    setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
                }
                setPadding(0, 0, 0, 0)
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minHeight = 0
                cornerRadius = 22.dpToPx()
                strokeWidth = 0
                
                setOnClickListener {
                    if (currentCameraId != id) {
                        switchCamera(id)
                    }
                }
            }
            container?.addView(button)
        }
        updateLensHighlight()
    }

    private fun releaseResources() {
        try {
            if (::session.isInitialized) {
                session.stopRepeating()
                session.close()
            }
            if (::camera.isInitialized) {
                camera.close()
            }
            if (::imageReader.isInitialized) {
                imageReader.close()
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Error releasing camera resources", exc)
        } catch (exc: java.lang.IllegalStateException) {
             // Ignored: Session has been closed; further changes are illegal.
             Log.w(TAG, "Session already closed: ${exc.message}")
        }
    }

    private fun reEnableUI() {
        fragmentCameraBinding.captureButton.isEnabled = true
        val count = fragmentCameraBinding.lensSelectorContainer?.childCount ?: 0
        for (i in 0 until count) {
            fragmentCameraBinding.lensSelectorContainer?.getChildAt(i)?.isEnabled = true
        }
    }

    private fun updateLensHighlight() {
        val container = fragmentCameraBinding.lensSelectorContainer ?: return
        for (i in 0 until container.childCount) {
            val button = container.getChildAt(i) as com.google.android.material.button.MaterialButton
            val cameraId = allCameraIds.getOrNull(i)
            if (cameraId == currentCameraId) {
                button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.secondary)) 
                button.setTextColor(Color.BLACK)
                button.alpha = 1.0f
            } else {
                button.setBackgroundColor(Color.parseColor("#33FFFFFF")) // 20% white
                button.setTextColor(Color.WHITE)
                button.alpha = 0.8f
            }
            button.strokeWidth = 0
        }
    }

    private fun switchCamera(newId: String) {
        if (currentCameraId == newId) return
        
        Log.d(TAG, "Switching camera to $newId")
        currentCameraId = newId
        
        // Update highlight immediately for responsive feel
        updateLensHighlight()
        
        // Cancel any pending camera operations and start fresh
        initializeCamera()
    }

    private fun openRecentPhoto() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%DCIM/Camera%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val query = requireContext().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        query?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                val contentUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "image/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
            } else {
                // Fallback if no images found in DCIM/Camera
                val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "No gallery app found", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            // Fallback if query fails
            val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() {
        cameraJob?.cancel()
        
        // Disable UI during initialization
        fragmentCameraBinding.captureButton.isEnabled = false
        // Disable lens buttons to prevent rapid switching
        // Disable lens buttons to prevent rapid switching
        val count = fragmentCameraBinding.lensSelectorContainer?.childCount ?: 0
        for (i in 0 until count) {
            fragmentCameraBinding.lensSelectorContainer?.getChildAt(i)?.isEnabled = false
        }

        cameraJob = lifecycleScope.launch(Dispatchers.Main) {
            // Release existing resources before opening a new camera
            releaseResources()
            
            // Wait a bit for the system to settle
            kotlinx.coroutines.delay(100)

            try {
                // Open the selected camera
                camera = openCamera(cameraManager, currentCameraId, cameraHandler)

                // Initialize an image reader which will be used to capture still photos
                Log.d(TAG, "Initializing image reader")
                val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw RuntimeException("Camera $currentCameraId does not support stream configuration map")
                
                // Determine compatible format (fallback to JPEG if requested format not supported)
                val supportedSizes = streamMap.getOutputSizes(args.pixelFormat)
                val format = if (supportedSizes.isNullOrEmpty()) {
                    Log.w(TAG, "Requested format ${args.pixelFormat} not supported by camera $currentCameraId, falling back to JPEG")
                    ImageFormat.JPEG
                } else {
                    args.pixelFormat
                }
                
                val finalSizes = if (format == args.pixelFormat) supportedSizes else streamMap.getOutputSizes(format)
                val size = finalSizes?.maxByOrNull { it.height * it.width } 
                    ?: throw RuntimeException("No supported sizes found for format $format on camera $currentCameraId")
                
                imageReader = ImageReader.newInstance(
                    size.width, size.height, format, IMAGE_BUFFER_SIZE
                )

                // Get the largest preview size that matches the capture aspect ratio
                // This ensures the viewfinder shows the full sensor field of view
                val captureRatio = size.width.toFloat() / size.height.toFloat()
                
                // Log all available preview sizes for debugging
                val allPreviewSizes = streamMap.getOutputSizes(SurfaceHolder::class.java)
                Log.d(TAG, "Capture size: ${size.width}x${size.height}, ratio: $captureRatio")
                
                // Filter to sizes matching the capture aspect ratio
                val matchingPreviewSizes = allPreviewSizes.filter { previewSize ->
                    val ratio = previewSize.width.toFloat() / previewSize.height.toFloat()
                    Math.abs(ratio - captureRatio) < 0.01f || Math.abs((1f/ratio) - captureRatio) < 0.01f
                }.sortedByDescending { it.width * it.height }
                
                // Use the largest matching size, or fall back to the capture size itself
                val previewSize = matchingPreviewSizes.firstOrNull() ?: size
                
                val previewRatio = previewSize.width.toFloat() / previewSize.height.toFloat()
                Log.d(TAG, "Selected preview: ${previewSize.width}x${previewSize.height}, ratio: $previewRatio")
                
                // Set the viewfinder to the preview size
                fragmentCameraBinding.viewFinder.setAspectRatio(
                    previewSize.width,
                    previewSize.height
                )

                // Match the container to the same aspect ratio
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val rotation = fragmentCameraBinding.viewFinder.display.rotation
                val deviceRotation = when (rotation) {
                    Surface.ROTATION_0 -> 0
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                
                // Determine if we need to swap width/height based on sensor and device orientation
                val totalRotation = (sensorOrientation + deviceRotation) % 360
                val needsSwap = (totalRotation == 90 || totalRotation == 270)
                
                val ratio = if (needsSwap) {
                    "${previewSize.height}:${previewSize.width}"
                } else {
                    "${previewSize.width}:${previewSize.height}"
                }
                
                Log.d(TAG, "Container ratio: $ratio (sensor: $sensorOrientation°, device: $deviceRotation°, total: $totalRotation°, swap: $needsSwap)")

                val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
                constraintSet.clone(fragmentCameraBinding.root as androidx.constraintlayout.widget.ConstraintLayout)
                constraintSet.setDimensionRatio(R.id.view_finder_container, ratio)
                constraintSet.applyTo(fragmentCameraBinding.root as androidx.constraintlayout.widget.ConstraintLayout)
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to initialize camera components", exc)
                reEnableUI()
                return@launch
            }

            // Creates list of Surfaces where the camera will output frames
            val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

            // Start a capture session using our open camera and list of Surfaces where frames will go
            try {
                session = createCaptureSession(camera, targets, cameraHandler)
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to create capture session", exc)
                reEnableUI()
                return@launch
            }

            reEnableUI()

            val captureRequest = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply { 
                addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                set(CaptureRequest.CONTROL_AE_MODE, flashMode)
            }

            // This will keep sending the capture request as frequently as possible until the
            // session is torn down or session.stopRepeating() is called
            session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

            // Listen to the capture button
            fragmentCameraBinding.captureButton.setOnClickListener {
                val button = it
                button.isEnabled = false

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        takePhoto().use { result ->
                            Log.d(TAG, "Result received: $result")

                            val output = saveResult(result)
                            Log.d(TAG, "Image saved: ${output.absolutePath}")

                            lifecycleScope.launch(Dispatchers.Main) {
                                navController.navigate(
                                    CameraFragmentDirections
                                        .actionCameraToJpegViewer(output.absolutePath)
                                        .setOrientation(result.orientation)
                                        .setDepth(
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                                    result.format == ImageFormat.DEPTH_JPEG
                                        )
                                )
                            }
                        }
                    } catch (exc: Exception) {
                        Log.e(TAG, "Photo capture failed", exc)
                        lifecycleScope.launch(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                            // Revert overlay if capture failed
                            fragmentCameraBinding.overlay.background = null
                        }
                    } finally {
                        lifecycleScope.launch(Dispatchers.Main) {
                            button.isEnabled = true
                        }
                    }
                }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) = cont.resume(device)

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "Camera $cameraId has been disconnected")
                    // Do not finish activity, just warn
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val msg = when (error) {
                        ERROR_CAMERA_DEVICE -> "Fatal (device)"
                        ERROR_CAMERA_DISABLED -> "Device policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }
                    val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                    Log.e(TAG, exc.message, exc)
                    if (cont.isActive) cont.resumeWithException(exc)
                }
            }, handler)
        } catch (e: Exception) {
             if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply { 
            addTarget(imageReader.surface)
            
            // Set flash mode for capture
            if (flashMode == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
                // Use FLASH_MODE_SINGLE for reliable flash triggering
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                fragmentCameraBinding.viewFinder.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // TODO(owahltinez): b/142011420
                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)
                        
                        Log.d(TAG, "EXIF orientation: rotation=$rotation°, mirrored=$mirrored, exifOrientation=$exifOrientation, cameraId=$currentCameraId")

                        // Build the result and resume progress
                        cont.resume(
                            CombinedCaptureResult(
                                image, result, exifOrientation, imageReader.imageFormat
                            )
                        )

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            // Only expecting RAW sensor data
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                try {
                    if (isJpeg) {
                        // Get RAW image data
                        val rawImage = result.image
                        val rawBuffer = rawImage.planes[0].buffer
                        val rawBytes = ByteArray(rawBuffer.remaining())
                        rawBuffer.get(rawBytes)

                        // Create a temporary DNG file
                        val tempDngFile = File(requireContext().cacheDir, "temp.dng")
                        FileOutputStream(tempDngFile).use { outputStream ->
                            dngCreator.writeImage(outputStream, rawImage)
                        }

                        // TODO: Right now, using android's basic bitmap conversion,
                        //  may want to use RenderScript or other RAW processing library
                        val bitmap = BitmapFactory.decodeFile(tempDngFile.absolutePath)
                        tempDngFile.delete() // Clean up temp file

                        // Save as JPEG
                        val filename = "IMG_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(Date())
                        }.jpg"

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DCIM}/Camera"
                                )
                            }

                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: throw IOException("Failed to create MediaStore entry")

                            resolver.openOutputStream(uri)?.use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                            }

                            // Add EXIF orientation data using the URI
                            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                                ExifInterface(pfd.fileDescriptor).apply {
                                    setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                    saveAttributes()
                                }
                            }

                            // Create a reference file in the DCIM directory
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera")
                            val savedFile = File(appFolder, filename)
                            cont.resume(savedFile)
                        } else {
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera").apply {
                                if (!exists()) mkdirs()
                            }
                            val file = File(appFolder, filename)

                            FileOutputStream(file).use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                            }

                            // Add EXIF orientation data
                            ExifInterface(file.absolutePath).apply {
                                setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                saveAttributes()
                            }

                            cont.resume(file)
                        }

                        bitmap.recycle()
                    } else {
                        dngCreator.setOrientation(result.orientation)
                        val filename = "RAW_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(Date())
                        }.dng"

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // Android 10 and above: Use MediaStore
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DCIM}/Camera"
                                )
                            }

                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: throw IOException("Failed to create MediaStore entry")

                            val outputStream = resolver.openOutputStream(uri)
                                ?: throw IOException("Failed to open output stream")

                            outputStream.use { stream ->
                                dngCreator.writeImage(stream, result.image)
                            }

                            // Create a reference file in the DCIM directory
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera")
                            val savedFile = File(appFolder, filename)
                            cont.resume(savedFile)

                        } else {
                            // Below Android 10: Use direct file access
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera").apply {
                                if (!exists()) {
                                    mkdirs()
                                }
                            }
                            val file = File(appFolder, filename)

                            FileOutputStream(file).use { outputStream ->
                                dngCreator.writeImage(outputStream, result.image)
                            }
                        }
                    }

                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to external storage", exc)
                    cont.resumeWithException(exc)
                }
            }

            // Handle JPEG format directly
            ImageFormat.JPEG -> {
                try {
                    val image = result.image
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    val filename = "IMG_${
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    }.jpg"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                        }
                        val resolver = requireContext().contentResolver
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                            ?: throw IOException("Failed to create MediaStore entry")

                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        
                        resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                            ExifInterface(pfd.fileDescriptor).apply {
                                setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                saveAttributes()
                            }
                        }
                        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                        cont.resume(File(File(dcim, "Camera"), filename))
                    } else {
                        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                        val file = File(File(dcim, "Camera").apply { if (!exists()) mkdirs() }, filename)
                        FileOutputStream(file).use { it.write(bytes) }
                        ExifInterface(file.absolutePath).apply {
                            setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                            saveAttributes()
                        }
                        cont.resume(file)
                    }
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }
    }
}
