// Small JNI wrapper around llama.cpp for the Mobile AI Studio app.
// Text crosses the JNI boundary as raw UTF-8 byte arrays (never NewStringUTF),
// so emoji and other 4-byte characters are safe.
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include <chrono>
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MobileAI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MobileAI", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, "[I] " __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) (fprintf(stderr, "[E] " __VA_ARGS__), fputc('\n', stderr))
#endif

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    mtmd_context *mm = nullptr;  // image reader, only for models loaded with a vision file
    // Tokens whose results are already in the memory of the context, so the next prompt can skip re-reading them.
    std::vector<llama_token> cached;
    // Timing of the last answer: prompt tokens read, ms to read them, tokens written, ms to write them.
    int64_t st_prompt_tokens = 0, st_prompt_ms = 0, st_gen_tokens = 0, st_gen_ms = 0;
};

inline int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Error codes returned to Kotlin
constexpr jint ERR_NO_ENGINE = -1;
constexpr jint ERR_TOO_LONG = -2;
constexpr jint ERR_PROMPT = -3;
constexpr jint ERR_DECODE = -4;

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
    }
}

// Bytes at the end of `s` that belong to an unfinished UTF-8 character are held back.
size_t utf8_complete_prefix(const std::string &s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 3 && back <= n; back++) {
        const unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte, keep looking back
        const size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return need > back ? n - back : n;
    }
    return n;
}

std::string bytes_to_string(JNIEnv *env, jbyteArray arr) {
    if (arr == nullptr) return std::string();
    const jsize len = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(len), '\0');
    if (len > 0) env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

// Applies the model's chat template. Returns false if it could not be built.
bool build_prompt(JNIEnv *env, Engine *e, jobjectArray roles, jobjectArray contents, std::string &out) {
    const jsize n = env->GetArrayLength(roles);
    if (n <= 0 || env->GetArrayLength(contents) != n) return false;

    std::vector<std::string> role_s, content_s;
    for (jsize i = 0; i < n; i++) {
        jstring r = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        const char *rc = env->GetStringUTFChars(r, nullptr);
        role_s.emplace_back(rc);
        env->ReleaseStringUTFChars(r, rc);
        env->DeleteLocalRef(r);

        jbyteArray c = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        content_s.push_back(bytes_to_string(env, c));
        env->DeleteLocalRef(c);
    }
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    size_t total = 0;
    for (jsize i = 0; i < n; i++) {
        msgs[i].role = role_s[i].c_str();
        msgs[i].content = content_s[i].c_str();
        total += content_s[i].size() + role_s[i].size();
    }

    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    if (len < 0) {
        // The model's own template is not one llama.cpp understands: fall back to ChatML.
        len = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) return false;
    if (static_cast<size_t>(len) > buf.size()) {
        buf.resize(static_cast<size_t>(len) + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        if (len < 0) {
            len = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (len < 0 || static_cast<size_t>(len) > buf.size()) return false;
    }
    out.assign(buf.data(), static_cast<size_t>(len));
    return true;
}

bool tokenize(Engine *e, const std::string &text, std::vector<llama_token> &out) {
    int32_t cap = static_cast<int32_t>(text.size()) + 16;
    out.resize(static_cast<size_t>(cap));
    int32_t n = llama_tokenize(e->vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), cap, true, true);
    if (n < 0) {
        if (n == INT32_MIN) return false;
        cap = -n;
        out.resize(static_cast<size_t>(cap));
        n = llama_tokenize(e->vocab, text.c_str(), static_cast<int32_t>(text.size()), out.data(), cap, true, true);
        if (n < 0) return false;
    }
    out.resize(static_cast<size_t>(n));
    return true;
}

// Samples and streams tokens after the prompt has been read. Returns the number of tokens made.
jint run_loop(JNIEnv *env, Engine *e, llama_sampler *smpl, jobject sink, jmethodID on_token, int32_t budget) {
    jint result = 0;
    std::string pending;
    std::vector<char> piece(256);
    bool stop = false;
    while (!stop && result < budget) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;

        int32_t n = llama_token_to_piece(e->vocab, tok, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (n < 0) {
            piece.resize(static_cast<size_t>(-n));
            n = llama_token_to_piece(e->vocab, tok, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (n > 0) pending.append(piece.data(), static_cast<size_t>(n));

        const size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray(static_cast<jsize>(ok));
            env->SetByteArrayRegion(arr, 0, static_cast<jsize>(ok), reinterpret_cast<const jbyte *>(pending.data()));
            const jboolean go = env->CallBooleanMethod(sink, on_token, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                stop = true;
            } else if (!go) {
                stop = true;
            }
        }
        result++;
        if (stop) break;

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, batch) != 0) {
            result = result > 0 ? result : ERR_DECODE;
            break;
        }
    }

    return result;
}

llama_sampler *make_sampler(Engine *e, float temperature) {
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    return smpl;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_mobileaistudio_app_Native_backendInit(JNIEnv *env, jobject, jstring jdir) {
    llama_log_set(log_cb, nullptr);
    const char *dir = env->GetStringUTFChars(jdir, nullptr);
#ifdef GGML_BACKEND_DL
    ggml_backend_load_all_from_path(dir);
#endif
    env->ReleaseStringUTFChars(jdir, dir);
    llama_backend_init();
}

JNIEXPORT jstring JNICALL Java_com_mobileaistudio_app_Native_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL Java_com_mobileaistudio_app_Native_loadModel(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;  // map the file instead of copying it: saves RAM on small phones
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        LOGE("could not load model");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGE("could not create context");
        llama_model_free(model);
        return 0;
    }
    Engine *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL Java_com_mobileaistudio_app_Native_freeModel(JNIEnv *, jobject, jlong handle) {
    Engine *e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return;
    if (e->mm) mtmd_free(e->mm);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

// Number of prompt tokens for these messages, or a negative error code.
JNIEXPORT jint JNICALL Java_com_mobileaistudio_app_Native_countTokens(JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents) {
    Engine *e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return ERR_NO_ENGINE;
    std::string prompt;
    if (!build_prompt(env, e, roles, contents, prompt)) return ERR_PROMPT;
    std::vector<llama_token> toks;
    if (!tokenize(e, prompt, toks)) return ERR_PROMPT;
    return static_cast<jint>(toks.size());
}

JNIEXPORT jint JNICALL Java_com_mobileaistudio_app_Native_contextSize(JNIEnv *, jobject, jlong handle) {
    Engine *e = reinterpret_cast<Engine *>(handle);
    return e == nullptr ? 0 : static_cast<jint>(llama_n_ctx(e->ctx));
}

// Streams the reply through sink.onToken(bytes). Returns tokens generated, or a negative error code.
JNIEXPORT jint JNICALL Java_com_mobileaistudio_app_Native_generate(JNIEnv *env, jobject, jlong handle, jobjectArray roles,
                                                                    jobjectArray contents, jint max_tokens, jfloat temperature,
                                                                    jobject sink) {
    Engine *e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return ERR_NO_ENGINE;

    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_token = env->GetMethodID(sink_cls, "onToken", "([B)Z");
    jmethodID is_cancelled = env->GetMethodID(sink_cls, "isCancelled", "()Z");
    if (on_token == nullptr || is_cancelled == nullptr) return ERR_PROMPT;

    std::string prompt;
    if (!build_prompt(env, e, roles, contents, prompt)) return ERR_PROMPT;
    std::vector<llama_token> toks;
    if (!tokenize(e, prompt, toks) || toks.empty()) return ERR_PROMPT;

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(e->ctx));
    const int32_t n_prompt = static_cast<int32_t>(toks.size());
    if (n_prompt + 8 > n_ctx) return ERR_TOO_LONG;
    const int32_t budget = std::min<int32_t>(max_tokens, n_ctx - n_prompt);

    // Re-use what is already in memory: the start of the prompt is usually the same as last time.
    int32_t common = 0;
    {
        const size_t lim = std::min<size_t>(e->cached.size(), static_cast<size_t>(n_prompt - 1));
        while (static_cast<size_t>(common) < lim && e->cached[common] == toks[common]) common++;
    }
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (common > 0) {
        if (!llama_memory_seq_rm(mem, 0, common, -1)) {
            llama_memory_clear(mem, true);
            common = 0;
        }
    } else {
        llama_memory_clear(mem, true);
    }
    e->cached.clear();

    llama_sampler *smpl = make_sampler(e, temperature);

    jint result = 0;
    const int64_t t0 = now_ms();

    // Read the prompt in chunks so cancel works even during a long prompt.
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(e->ctx));
    for (int32_t i = common; i < n_prompt; i += n_batch) {
        const int32_t n_eval = std::min(n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(toks.data() + i, n_eval);
        if (llama_decode(e->ctx, batch) != 0) {
            llama_sampler_free(smpl);
            return ERR_DECODE;
        }
        if (env->CallBooleanMethod(sink, is_cancelled)) {
            llama_sampler_free(smpl);
            return 0;
        }
    }

    const int64_t t1 = now_ms();
    result = run_loop(env, e, smpl, sink, on_token, budget);
    const int64_t t2 = now_ms();
    llama_sampler_free(smpl);
    e->st_prompt_tokens = n_prompt - common;
    e->st_prompt_ms = t1 - t0;
    e->st_gen_tokens = result > 0 ? result : 0;
    e->st_gen_ms = t2 - t1;
    // Keep the prompt (not the answer) for next time.
    if (result >= 0 && llama_memory_seq_rm(mem, 0, n_prompt, -1)) e->cached = toks;
    else llama_memory_clear(mem, true);
    return result;
}

JNIEXPORT jlongArray JNICALL Java_com_mobileaistudio_app_Native_lastStats(JNIEnv *env, jobject, jlong handle) {
    Engine *e = reinterpret_cast<Engine *>(handle);
    jlongArray arr = env->NewLongArray(4);
    jlong v[4] = {0, 0, 0, 0};
    if (e != nullptr) {
        v[0] = e->st_prompt_tokens;
        v[1] = e->st_prompt_ms;
        v[2] = e->st_gen_tokens;
        v[3] = e->st_gen_ms;
    }
    env->SetLongArrayRegion(arr, 0, 4, v);
    return arr;
}

// Loads the image reader (the "mmproj" file) that belongs to the loaded model.
JNIEXPORT jboolean JNICALL Java_com_mobileaistudio_app_Native_loadVision(JNIEnv *env, jobject, jlong handle, jstring jpath, jint n_threads) {
    Engine *e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return JNI_FALSE;
    if (e->mm) {
        mtmd_free(e->mm);
        e->mm = nullptr;
    }
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu = false;
    mp.n_threads = n_threads;
    mp.print_timings = false;
    mp.warmup = false;
    e->mm = mtmd_init_from_file(path, e->model, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (e->mm == nullptr || !mtmd_support_vision(e->mm)) {
        LOGE("could not load the vision file");
        if (e->mm) mtmd_free(e->mm);
        e->mm = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_mobileaistudio_app_Native_hasVision(JNIEnv *, jobject, jlong handle) {
    Engine *e = reinterpret_cast<Engine *>(handle);
    return (e != nullptr && e->mm != nullptr) ? JNI_TRUE : JNI_FALSE;
}

constexpr jint ERR_NO_VISION = -5;
constexpr jint ERR_IMAGE = -6;

// Like generate, but the prompt contains the marker mtmd_default_marker() once per image, in order.
// images is an array of encoded pictures (JPEG or PNG bytes).
JNIEXPORT jint JNICALL Java_com_mobileaistudio_app_Native_generateWithImages(JNIEnv *env, jobject, jlong handle, jobjectArray roles,
                                                                              jobjectArray contents, jobjectArray images, jint max_tokens,
                                                                              jfloat temperature, jobject sink) {
    Engine *e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return ERR_NO_ENGINE;
    if (e->mm == nullptr) return ERR_NO_VISION;

    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_token = env->GetMethodID(sink_cls, "onToken", "([B)Z");
    jmethodID is_cancelled = env->GetMethodID(sink_cls, "isCancelled", "()Z");
    if (on_token == nullptr || is_cancelled == nullptr) return ERR_PROMPT;

    std::string prompt;
    if (!build_prompt(env, e, roles, contents, prompt)) return ERR_PROMPT;

    std::vector<mtmd_bitmap *> bitmaps;
    const jsize n_img = images == nullptr ? 0 : env->GetArrayLength(images);
    const mtmd_helper_init_opt opt = mtmd_helper_init_opt_default();
    for (jsize i = 0; i < n_img; i++) {
        jbyteArray arr = static_cast<jbyteArray>(env->GetObjectArrayElement(images, i));
        const std::string data = bytes_to_string(env, arr);
        env->DeleteLocalRef(arr);
        mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_buf(e->mm, reinterpret_cast<const unsigned char *>(data.data()), data.size(), false, opt);
        if (w.video_ctx) mtmd_helper_video_free(w.video_ctx);
        if (w.bitmap == nullptr) {
            for (mtmd_bitmap *b : bitmaps) mtmd_bitmap_free(b);
            return ERR_IMAGE;
        }
        bitmaps.push_back(w.bitmap);
    }

    mtmd_input_text txt;
    txt.text = prompt.c_str();
    txt.text_len = prompt.size();
    txt.add_special = true;
    txt.parse_special = true;
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    std::vector<const mtmd_bitmap *> bm(bitmaps.begin(), bitmaps.end());
    const int32_t tr = mtmd_tokenize(e->mm, chunks, &txt, bm.data(), bm.size());
    for (mtmd_bitmap *b : bitmaps) mtmd_bitmap_free(b);
    if (tr != 0) {
        mtmd_input_chunks_free(chunks);
        return tr == 2 ? ERR_IMAGE : ERR_PROMPT;
    }

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(e->ctx));
    const int32_t n_prompt = static_cast<int32_t>(mtmd_helper_get_n_tokens(chunks));
    if (n_prompt + 8 > n_ctx) {
        mtmd_input_chunks_free(chunks);
        return ERR_TOO_LONG;
    }
    const int32_t budget = std::min<int32_t>(max_tokens, n_ctx - n_prompt);

    e->cached.clear();
    llama_memory_clear(llama_get_memory(e->ctx), true);
    if (env->CallBooleanMethod(sink, is_cancelled)) {
        mtmd_input_chunks_free(chunks);
        return 0;
    }

    llama_pos new_past = 0;
    const int32_t er = mtmd_helper_eval_chunks(e->mm, e->ctx, chunks, 0, 0, static_cast<int32_t>(llama_n_batch(e->ctx)), true, &new_past);
    mtmd_input_chunks_free(chunks);
    if (er != 0) {
        LOGE("image evaluation failed: %d", er);
        return ERR_DECODE;
    }
    if (env->CallBooleanMethod(sink, is_cancelled)) return 0;

    llama_sampler *smpl = make_sampler(e, temperature);
    const jint result = run_loop(env, e, smpl, sink, on_token, budget);
    llama_sampler_free(smpl);
    return result;
}

}  // extern "C"
