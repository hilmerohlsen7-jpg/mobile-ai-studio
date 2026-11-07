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
    fun ensureLoaded(libDir: String): Boolean {
        if (ready) return true
        return try {
            System.loadLibrary("mobileai")
            backendInit(libDir)
            ready = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    external fun backendInit(libDir: String)

    /** Which CPU features the loaded maths code uses (for example DOTPROD, MATMUL_INT8). */
    external fun systemInfo(): String

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

    /** Loads the image reader (the "mmproj" file) for a loaded model. True if the model can now see pictures. */
    external fun loadVision(handle: Long, path: String, nThreads: Int): Boolean

    external fun hasVision(handle: Long): Boolean

    /**
     * Like generate, but the text contains "<__media__>" once for each picture, in order.
     * images are encoded JPEG or PNG bytes. Extra errors: -5 model cannot see, -6 picture unreadable.
     */
    external fun generateWithImages(
        handle: Long,
        roles: Array<String>,
        contents: Array<ByteArray>,
        images: Array<ByteArray>,
        maxTokens: Int,
        temperature: Float,
        sink: TokenSink
    ): Int

    /** [prompt tokens read, ms spent reading, tokens written, ms spent writing] for the last answer. */
    external fun lastStats(handle: Long): LongArray
}
