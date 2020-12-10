package com.example.cameraxdemo

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.MutableLiveData
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition

class TextAnalyzer(private val text: MutableLiveData<Text>) : ImageAnalysis.Analyzer {

    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val recognizer = TextRecognition.getClient()
            recognizer.process(image)
                .addOnSuccessListener {
                    text.postValue(it)
                    imageProxy.close()
                }
                .addOnFailureListener {
                    Log.e("TextAnalyzer", "Detection failed", it)
                    imageProxy.close()
                }
        }
    }
}