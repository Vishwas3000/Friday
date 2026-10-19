#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr int CONTEXT = 1024;
constexpr int BATCH = 256;

struct Writer {
    std::atomic<bool> cancelled{false};
    Clock::time_point deadline;
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    ~Writer() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
    const char * status() const {
        if (cancelled.load(std::memory_order_relaxed)) return "cancelled";
        if (Clock::now() >= deadline) return "timeout";
        return nullptr;
    }
};

Writer & writer(jlong handle) {
    if (!handle) throw std::invalid_argument("Writer is closed");
    return *reinterpret_cast<Writer *>(handle);
}

bool abort_compute(void * data) { return static_cast<Writer *>(data)->status() != nullptr; }
bool load_progress(float, void * data) { return !abort_compute(data); }

void fail(JNIEnv * env, const std::exception & e) {
    if (!env->ExceptionCheck()) {
        const char * type = dynamic_cast<const std::invalid_argument *>(&e)
            ? "java/lang/IllegalArgumentException" : "java/lang/IllegalStateException";
        env->ThrowNew(env->FindClass(type), e.what());
    }
}

// JNI's modified UTF-8 corrupts supplementary characters; use Java's standard UTF-8 codec.
std::string utf8(JNIEnv * env, jstring value) {
    if (!value) throw std::invalid_argument("Writer string is missing");
    auto cls = env->FindClass("java/lang/String");
    auto method = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    auto charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, method, charset));
    if (env->ExceptionCheck() || !bytes) throw std::runtime_error("UTF-8 conversion failed");
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cls);
    return result;
}

jstring java_string(JNIEnv * env, const std::string & value) {
    auto cls = env->FindClass("java/lang/String");
    auto constructor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    auto charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(cls, constructor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cls);
    return result;
}

jobjectArray result(JNIEnv * env, const std::string & text, const std::string & status,
                    int input, int output, long prefill, long decode) {
    std::vector<std::string> values{text, status, std::to_string(input), std::to_string(output),
                                    std::to_string(prefill), std::to_string(decode)};
    auto array = env->NewObjectArray(static_cast<jsize>(values.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < values.size(); ++i) {
        auto s = java_string(env, values[i]);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return array;
}

long millis(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

void budget(Writer & w, jlong timeout) {
    if (timeout < 1 || timeout > 30000) throw std::invalid_argument("Writer timeout must be 1..30000 ms");
    w.deadline = Clock::now() + std::chrono::milliseconds(timeout);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_layatest_agent_WriterNative_create(JNIEnv * env, jobject) {
    try {
        static std::once_flag init;
        std::call_once(init, llama_backend_init);
        return reinterpret_cast<jlong>(new Writer());
    } catch (const std::exception & e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_layatest_agent_WriterNative_load(JNIEnv * env, jobject, jlong handle, jstring path, jlong timeout) {
    try {
        auto & w = writer(handle);
        budget(w, timeout);
        if (w.model || w.ctx) throw std::invalid_argument("Writer already loaded");
        if (w.status()) throw std::runtime_error(w.status());
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.progress_callback = load_progress;
        mp.progress_callback_user_data = &w;
        const auto file = utf8(env, path);
        w.model = llama_model_load_from_file(file.c_str(), mp);
        if (w.status()) throw std::runtime_error(w.status());
        if (!w.model) throw std::runtime_error("Could not load Writer GGUF");
        auto cp = llama_context_default_params();
        cp.n_ctx = CONTEXT;
        cp.n_batch = BATCH;
        cp.n_ubatch = BATCH;
        cp.n_seq_max = 1;
        cp.n_threads = 4;
        cp.n_threads_batch = 4;
        cp.offload_kqv = false;
        cp.op_offload = false;
        cp.abort_callback = abort_compute;
        cp.abort_callback_data = &w;
        w.ctx = llama_init_from_model(w.model, cp);
        if (w.status()) throw std::runtime_error(w.status());
        if (!w.ctx) throw std::runtime_error("Could not create Writer context");
    } catch (const std::exception & e) { fail(env, e); }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_layatest_agent_WriterNative_generate(JNIEnv * env, jobject, jlong handle, jstring prompt,
                                            jint max_tokens, jstring grammar, jlong timeout) {
    try {
        auto & w = writer(handle);
        budget(w, timeout);
        if (!w.model || !w.ctx) throw std::invalid_argument("Writer is not loaded");
        if (max_tokens < 1 || max_tokens > 256) throw std::invalid_argument("Writer output limit must be 1..256");
        const auto text = utf8(env, prompt);
        const auto rules = utf8(env, grammar);
        const auto * vocab = llama_model_get_vocab(w.model);
        std::vector<llama_token> input(CONTEXT);
        int n = llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), input.data(), CONTEXT, false, true);
        if (n <= 0 || n + max_tokens > CONTEXT) throw std::invalid_argument("Writer context limit exceeded");
        input.resize(n);
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto * constrained = llama_sampler_init_grammar(vocab, rules.c_str(), "root");
        if (!constrained) throw std::invalid_argument("Writer grammar is invalid");
        llama_sampler_chain_add(sampler.get(), constrained);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        llama_memory_clear(llama_get_memory(w.ctx), true);
        auto prefill_start = Clock::now();
        for (int pos = 0; pos < n; pos += BATCH) {
            if (w.status()) return result(env, "", w.status(), n, 0, millis(prefill_start), 0);
            int rc = llama_decode(w.ctx, llama_batch_get_one(input.data() + pos, std::min(BATCH, n - pos)));
            if (rc != 0) {
                if (w.status()) return result(env, "", w.status(), n, 0, millis(prefill_start), 0);
                throw std::runtime_error("Writer prefill failed");
            }
        }
        const long prefill_ms = millis(prefill_start);
        auto decode_start = Clock::now();
        std::string output;
        int count = 0;
        std::string status = "length";
        while (count < max_tokens) {
            if (w.status()) { status = w.status(); break; }
            llama_token token = llama_sampler_sample(sampler.get(), w.ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) { status = "eos"; break; }
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (size < 0) {
                piece.resize(-size);
                size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            }
            if (size < 0) throw std::runtime_error("Writer token decoding failed");
            output.append(piece.data(), size);
            ++count;
            if (llama_decode(w.ctx, llama_batch_get_one(&token, 1)) != 0) {
                if (w.status()) { status = w.status(); break; }
                throw std::runtime_error("Writer decoding failed");
            }
        }
        if (w.status()) status = w.status();
        // Partial output is diagnostics only: Kotlin exposes text solely after EOS + JSON validation.
        return result(env, status == "eos" ? output : "", status, n, count, prefill_ms, millis(decode_start));
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_layatest_agent_WriterNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Writer *>(handle)->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_layatest_agent_WriterNative_resetCancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Writer *>(handle)->cancelled.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_layatest_agent_WriterNative_free(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Writer *>(handle);
}
