package com.rementia.openwakeword.lib.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager

/**
 * Handles embedding generation from mel-spectrograms using ONNX model.
 */
internal class EmbeddingModel(
    private val assetManager: AssetManager
) : AutoCloseable {
    
    companion object {
        private const val EMBEDDING_MODEL = "embedding_model.onnx"
    }
    
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // Loaded once. Re-reading and re-creating the session on every 80 ms chunk made the
    // pipeline fall behind real time, so the microphone buffer overflowed and audio was lost.
    private val session: OrtSession = assetManager.open(EMBEDDING_MODEL).use { env.createSession(it.readBytes()) }
    
    /**
     * Generate embeddings from mel-spectrogram windows.
     * 
     * @param input 4D array of shape [batch, height, width, channels]
     * @return 2D array of embeddings
     */
    fun generateEmbeddings(input: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
        var inputTensor: OnnxTensor? = null
        
        return try {
            inputTensor = OnnxTensor.createTensor(env, input)
            
            session.run(mapOf("input_1" to inputTensor)).use { results ->
                val rawOutput = results[0].value as Array<Array<Array<FloatArray>>>
                
                // Reshape from (41, 1, 1, 96) to (41, 96)
                Array(rawOutput.size) { i ->
                    rawOutput[i][0][0].copyOf()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate embeddings", e)
        } finally {
            inputTensor?.close()
        }
    }
    
    override fun close() {
        session.close() // env is managed globally by OrtEnvironment
    }
}