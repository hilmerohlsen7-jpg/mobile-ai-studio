package com.mobileaistudio.app

import java.io.File

data class ModelInfo(
    val id: String,
    val name: String,
    val file: String,
    val url: String,
    val sizeMB: Int,
    val note: String,
    val recommended: Boolean = false
)

object Catalog {
    private const val QWEN = "https://huggingface.co/Qwen"

    val all = listOf(
        ModelInfo(
            id = "qwen25-coder-0.5b",
            name = "Qwen2.5-Coder 0.5B",
            file = "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            url = "$QWEN/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            sizeMB = 398,
            note = "Small and quick, made for code. Best start for phones with 4 GB RAM or less.",
            recommended = true
        ),
        ModelInfo(
            id = "qwen25-0.5b",
            name = "Qwen2.5 0.5B",
            file = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "$QWEN/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMB = 398,
            note = "Small and quick, for everyday chat."
        ),
        ModelInfo(
            id = "llama32-1b",
            name = "Llama 3.2 1B",
            file = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeMB = 808,
            note = "Meta's small model. Good at general chat."
        ),
        ModelInfo(
            id = "qwen25-coder-1.5b",
            name = "Qwen2.5-Coder 1.5B",
            file = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            url = "$QWEN/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            sizeMB = 986,
            note = "Better code than the 0.5B, but slower and needs more memory."
        ),
        ModelInfo(
            id = "qwen25-1.5b",
            name = "Qwen2.5 1.5B",
            file = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "$QWEN/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMB = 986,
            note = "Smarter everyday chat, slower and needs more memory."
        )
    )

    fun byId(id: String): ModelInfo? = all.firstOrNull { it.id == id }

    /** Ids starting with "file:" are models the user imported. */
    fun fileFor(dir: File, id: String): File? {
        if (id.startsWith("file:")) {
            val name = id.removePrefix("file:")
            if (name.contains('/') || name.contains('\\') || name.startsWith(".")) return null
            return File(dir, name)
        }
        val m = byId(id) ?: return null
        return File(dir, m.file)
    }

    /** A real model file starts with the four letters GGUF. */
    fun looksLikeGguf(f: File): Boolean {
        return try {
            f.inputStream().use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 && String(head, Charsets.US_ASCII) == "GGUF"
            }
        } catch (e: Exception) {
            false
        }
    }
}
