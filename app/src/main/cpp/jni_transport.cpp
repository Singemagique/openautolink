/*
 * jni_transport.cpp — aasdk ITransport backed by JNI byte pipe.
 *
 * Bridges TCP socket streams (Kotlin InputStream/OutputStream from the
 * shared-WiFi connection to the companion app) to aasdk's async
 * Promise-based transport interface.
 */
#include "jni_transport.h"
#include "jni_log_bridge.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstring>

#define LOG_TAG "OAL-JniTransport"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace openautolink::jni {

static constexpr size_t kReadBufferSize = 16384;
// NAT-8: only reclaim the consumed prefix once this many bytes have been read,
// so draining is amortized O(1) instead of an O(n) front-erase on every receive.
static constexpr size_t kReceiveCompactThreshold = 256 * 1024;
// NAT-9: cap unbounded receiveBuffer_ growth. If aasdk consumption falls behind
// the socket read rate, the read thread stops pulling at this backlog so TCP/USB
// flow control throttles the phone — instead of the buffer growing without bound
// until the process OOM-aborts after minutes. 8 MB is ~4s of 1080p at 15 Mbps,
// far beyond any healthy backlog, so this only engages under real congestion.
static constexpr size_t kReceiveHighWaterMark = 8 * 1024 * 1024;
// If the backlog stays pinned above the high-water this long the consumer is
// wedged; abort the transport for a clean reconnect rather than block forever.
static constexpr int kBackpressureStallAbortMs = 5000;

JniTransport::JniTransport(boost::asio::io_service& ioService, JavaVM* jvm, jobject javaTransport)
    : ioService_(ioService)
    , strand_(ioService)
    , jvm_(jvm)
    , javaTransport_(javaTransport)
{
    // Cache JNI method IDs
    JNIEnv* env = nullptr;
    jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (env) {
        jclass cls = env->GetObjectClass(javaTransport_);
        readMethodId_ = env->GetMethodID(cls, "readBytes", "(I)[B");
        writeMethodId_ = env->GetMethodID(cls, "writeBytes", "([B)V");
        env->DeleteLocalRef(cls);
    }

    // Start read thread
    readThread_ = std::thread(&JniTransport::readThreadFunc, this);
    LOGI("JniTransport created");
}

JniTransport::~JniTransport()
{
    stop();

    // Safety net: if readThread self-exited (set stopped_ directly),
    // stop() early-returned without joining. Join here to prevent
    // ~std::thread calling std::terminate on a joinable thread.
    if (readThread_.joinable()) {
        readThread_.join();
    }

    // Release JNI global ref
    if (javaTransport_) {
        JNIEnv* env = nullptr;
        bool attached = false;
        jint result = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            jvm_->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
        if (env) {
            env->DeleteGlobalRef(javaTransport_);
            javaTransport_ = nullptr;
        }
        if (attached) jvm_->DetachCurrentThread();
    }

    LOGI("JniTransport destroyed");
}

void JniTransport::receive(size_t size, ReceivePromise::Pointer promise)
{
    strand_.dispatch([this, size, promise = std::move(promise)]() mutable {
        if (stopped_) {
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            return;
        }

        aasdk::common::Data resolveData;
        bool canResolve = false;

        {
            std::lock_guard<std::mutex> lock(receiveMutex_);

            // If we already have enough buffered data, resolve immediately.
            // NAT-8: consume via a read offset (O(1)) instead of erase-from-front
            // (O(n)) — a growing buffer plus a per-read memmove compounded into a
            // throughput collapse over minutes.
            if (receiveBuffer_.size() - receiveReadPos_ >= size) {
                auto first = receiveBuffer_.begin() + receiveReadPos_;
                resolveData.assign(first, first + size);
                receiveReadPos_ += size;
                compactReceiveBuffer();
                canResolve = true;
            } else {
                // Queue the promise for later fulfillment
                receiveQueue_.push({size, std::move(promise)});
            }
        }

        if (canResolve) {
            // Freed space in the buffer — wake the read thread if backpressure
            // (NAT-9) had it throttled. Resolve outside the lock to avoid
            // re-entrancy deadlock.
            receiveCv_.notify_one();
            promise->resolve(std::move(resolveData));
        }
    });
}

void JniTransport::send(aasdk::common::Data data, SendPromise::Pointer promise)
{
    strand_.dispatch([this, data = std::move(data), promise = std::move(promise)]() mutable {
        if (stopped_) {
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            return;
        }

        // Call Java writeBytes() from strand thread
        JNIEnv* env = nullptr;
        bool attached = false;
        jint result = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            jvm_->AttachCurrentThread(&env, nullptr);
            attached = true;
        }

        if (!env || !writeMethodId_) {
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            if (attached) jvm_->DetachCurrentThread();
            return;
        }

        jbyteArray jdata = env->NewByteArray(static_cast<jsize>(data.size()));
        env->SetByteArrayRegion(jdata, 0, static_cast<jsize>(data.size()),
                                reinterpret_cast<const jbyte*>(data.data()));
        env->CallVoidMethod(javaTransport_, writeMethodId_, jdata);

        bool hadException = env->ExceptionCheck();
        if (hadException) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            env->DeleteLocalRef(jdata);
            if (attached) jvm_->DetachCurrentThread();
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            return;
        }

        env->DeleteLocalRef(jdata);
        if (attached) jvm_->DetachCurrentThread();

        promise->resolve();
    });
}

void JniTransport::stop()
{
    if (stopped_.exchange(true)) return;

    LOGI("JniTransport stopping");

    // Wake up read thread
    receiveCv_.notify_all();

    if (readThread_.joinable()) {
        readThread_.join();
    }

    // Reject all pending receive promises
    strand_.dispatch([this]() {
        std::lock_guard<std::mutex> lock(receiveMutex_);
        while (!receiveQueue_.empty()) {
            auto& [size, promise] = receiveQueue_.front();
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            receiveQueue_.pop();
        }
    });
}

void JniTransport::onDataReceived(const uint8_t* data, size_t length)
{
    if (stopped_) return;

    {
        std::lock_guard<std::mutex> lock(receiveMutex_);
        receiveBuffer_.insert(receiveBuffer_.end(), data, data + length);
    }

    // Try to fulfill pending receive promises
    processReceiveQueue();
}

void JniTransport::readThreadFunc()
{
    JNIEnv* env = nullptr;
    jvm_->AttachCurrentThread(&env, nullptr);

    LOGI("Read thread started");

    std::vector<uint8_t> localBuf(kReadBufferSize);

    while (!stopped_) {
        // Backpressure gate (NAT-9): if aasdk consumption has fallen behind and
        // the unread backlog reached the high-water mark, stop pulling from the
        // socket so TCP/USB flow control throttles the phone. Without this the
        // buffer grew without bound whenever drain-rate < fill-rate and
        // OOM-aborted the whole process after a few minutes of streaming.
        bool backpressureAborted = false;
        {
            std::unique_lock<std::mutex> lock(receiveMutex_);
            if (receiveBuffer_.size() - receiveReadPos_ >= kReceiveHighWaterMark) {
                if (!loggedBackpressure_) {
                    loggedBackpressure_ = true;
                    const size_t backlogKb =
                        (receiveBuffer_.size() - receiveReadPos_) / 1024;
                    lock.unlock();
                    OAL_LOGW(LOG_TAG, "Receive backlog %zuKB hit high-water — throttling "
                             "socket reads (consumer behind)", backlogKb);
                    lock.lock();
                }
                int stalledMs = 0;
                while (!stopped_ &&
                       receiveBuffer_.size() - receiveReadPos_ >= kReceiveHighWaterMark) {
                    receiveCv_.wait_for(lock, std::chrono::milliseconds(50));
                    stalledMs += 50;
                    if (stalledMs >= kBackpressureStallAbortMs) {
                        backpressureAborted = true;
                        break;
                    }
                }
            }
        }
        if (backpressureAborted) {
            OAL_LOGE(LOG_TAG, "Receive backlog pinned above high-water >%dms — consumer "
                     "wedged; aborting transport for clean reconnect", kBackpressureStallAbortMs);
            break;
        }
        if (stopped_) break;

        if (!readMethodId_) {
            LOGE("readBytes method not found");
            break;
        }

        // Call Java readBytes(maxSize) — blocks until data available
        jbyteArray jdata = static_cast<jbyteArray>(
            env->CallObjectMethod(javaTransport_, readMethodId_, static_cast<jint>(kReadBufferSize)));

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGW("Read thread: Java exception, stopping");
            break;
        }

        if (!jdata) {
            LOGW("Read thread: null returned (stream closed)");
            break;
        }

        jsize len = env->GetArrayLength(jdata);
        if (len <= 0) {
            env->DeleteLocalRef(jdata);
            continue;
        }

        if (static_cast<size_t>(len) > localBuf.size()) {
            localBuf.resize(len);
        }
        env->GetByteArrayRegion(jdata, 0, len, reinterpret_cast<jbyte*>(localBuf.data()));
        env->DeleteLocalRef(jdata);

        // Feed into receive buffer (per-buffer log dropped — fires hundreds of
        // times/second on the AA hot path. Re-enable behind a verbose flag if
        // you need it.)
        onDataReceived(localBuf.data(), static_cast<size_t>(len));
    }

    LOGI("Read thread exiting");
    jvm_->DetachCurrentThread();

    // Signal that the transport is dead so consumers stop sending.
    // Do NOT set stopped_ here — that would cause stop() to skip
    // joining this thread, leading to ~thread calling std::terminate.
    // Instead, reject pending promises and let stop() handle the flag.
    strand_.post([this]() {
        std::lock_guard<std::mutex> lock(receiveMutex_);
        while (!receiveQueue_.empty()) {
            auto& [size, promise] = receiveQueue_.front();
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            receiveQueue_.pop();
        }
    });
}

void JniTransport::compactReceiveBuffer()
{
    // Called with receiveMutex_ held. Reclaim consumed bytes: clear cheaply when
    // the buffer is fully drained (the common case when consumption keeps up), and
    // only memmove the tail once the dead prefix passes the threshold. Makes
    // draining amortized O(1) instead of the O(n)-per-read front-erase that
    // compounded into a throughput collapse as the buffer grew (NAT-8).
    if (receiveReadPos_ == receiveBuffer_.size()) {
        receiveBuffer_.clear();
        receiveReadPos_ = 0;
    } else if (receiveReadPos_ >= kReceiveCompactThreshold) {
        receiveBuffer_.erase(receiveBuffer_.begin(),
                             receiveBuffer_.begin() + receiveReadPos_);
        receiveReadPos_ = 0;
    }
}

void JniTransport::processReceiveQueue()
{
    strand_.dispatch([this]() {
        // Collect resolved promises outside the lock to avoid re-entrancy deadlock
        std::vector<std::pair<ReceivePromise::Pointer, aasdk::common::Data>> resolved;

        {
            std::lock_guard<std::mutex> lock(receiveMutex_);

            while (!receiveQueue_.empty()) {
                auto& [needed, promise] = receiveQueue_.front();

                if (receiveBuffer_.size() - receiveReadPos_ >= needed) {
                    auto first = receiveBuffer_.begin() + receiveReadPos_;
                    aasdk::common::Data data(first, first + needed);
                    receiveReadPos_ += needed;
                    compactReceiveBuffer();
                    resolved.emplace_back(std::move(promise), std::move(data));
                    receiveQueue_.pop();
                } else {
                    break; // Not enough data yet
                }
            }
        }

        if (!resolved.empty()) {
            // Drained backlog — wake the read thread if NAT-9 backpressure
            // paused it.
            receiveCv_.notify_one();
        }

        // Resolve outside the lock
        for (auto& [p, data] : resolved) {
            p->resolve(std::move(data));
        }
    });
}

} // namespace openautolink::jni
