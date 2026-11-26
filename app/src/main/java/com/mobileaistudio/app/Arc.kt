package com.mobileaistudio.app

/** Opens RAR files of every kind (including RAR5) with libarchive. */
object Arc {
    @Volatile private var ok: Boolean? = null

    @Synchronized
    fun available(): Boolean {
        ok?.let { return it }
        val r = try { System.loadLibrary("arc"); true } catch (e: Throwable) { false }
        ok = r
        return r
    }

    /** Returns null when it worked, or a message. */
    @JvmStatic external fun extract(src: String, dest: String, password: String): String?
}
