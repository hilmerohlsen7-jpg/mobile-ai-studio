package com.mobileaistudio.app

/** Receives the reply piece by piece from the native engine. */
interface TokenSink {
    /** Called with UTF-8 text. Return false to stop generating. */
    fun onToken(bytes: ByteArray): Boolean

    fun isCancelled(): Boolean
}

/** Thin wrapper over libmobileai.so (llama.cpp). */
object Native {
    @Volatile
    private var ready = false

    /** Loads the engine once. Returns false on phones it cannot run on. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (ready) return true
        return try {
            System.loadLibrary("mobileai")
            backendInit()
            ready = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    external fun backendInit()

    /** Returns a handle, or 0 if the model could not be loaded. */
    external fun loadModel(path: String, nCtx: Int, nThreads: Int): Long

    external fun freeModel(handle: Long)

    /** Prompt size in tokens for these messages, or a negative error code. */
    external fun countTokens(handle: Long, roles: Array<String>, contents: Array<ByteArray>): Int

    external fun contextSize(handle: Long): Int

    /** Tokens generated, or a negative error code (-2 prompt too long, -4 engine failure). */
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<ByteArray>,
        maxTokens: Int,
        temperature: Float,
        sink: TokenSink
    ): Int
}
