package dev.fabik.bluetoothhid.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("UnsafeOptInUsageError")
class BarCodeAnalyser(
    private val context: Context,
    private val onNothing: () -> Unit,
    private val onBarcodeDetected: (barcodes: List<Barcode>, sourceImage: Size) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        const val TAG = "BarcodeAnalyser"
    }

    private var lastAnalyzedTimeStamp = 0L

    private var isBusy = AtomicBoolean(false)

    private var scanDelay = 0
    private var formats: IntArray = intArrayOf(0)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            context.getPreference(PrefKeys.SCAN_FREQUENCY).collect {
                scanDelay = when (it) {
                    0 -> 0
                    1 -> 100
                    3 -> 1000
                    else -> 500
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            context.getPreference(PrefKeys.CODE_TYPES).collect {
                formats = it.map { v -> v.toInt() }.toIntArray()
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        if ((currentTimestamp - lastAnalyzedTimeStamp) > scanDelay
            && isBusy.compareAndSet(false, true)
        ) {
            image.image?.let { imageToAnalyze ->
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(0, *formats)
                    .build()
                val barcodeScanner = BarcodeScanning.getClient(options)
                val imageToProcess =
                    InputImage.fromMediaImage(imageToAnalyze, image.imageInfo.rotationDegrees)

                barcodeScanner.process(imageToProcess)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            onBarcodeDetected(barcodes, Size(image.width, image.height))
                        } else {
                            onNothing()
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.d(TAG, "Something went wrong $exception")
                    }
                    .addOnCompleteListener {
                        image.close()
                        isBusy.set(false)
                    }
            }
            lastAnalyzedTimeStamp = currentTimestamp
        } else {
            image.close()
        }
    }
}