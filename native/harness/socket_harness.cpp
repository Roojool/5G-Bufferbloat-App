#include <jni.h>
#include <arpa/inet.h>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

// Separate debug JNI surface. No TUN, route, persistent callback, or production ABI.
namespace {
jlongArray longs(JNIEnv* env, const std::vector<jlong>& values) {
    auto out = env->NewLongArray(static_cast<jsize>(values.size()));
    if (out) env->SetLongArrayRegion(out, 0, static_cast<jsize>(values.size()), values.data());
    return out;
}
// Stable field order belongs to this harness schema, not to the kernel layout.
std::vector<jlong> decode(const tcp_info& info, size_t length, int error) {
    std::vector<jlong> out{error, static_cast<jlong>(length)};
#define FIELD(name) out.push_back(!error && offsetof(tcp_info, name) + sizeof(info.name) <= length ? static_cast<jlong>(info.name) : -1)
    FIELD(tcpi_state); FIELD(tcpi_rto); FIELD(tcpi_snd_mss); FIELD(tcpi_rcv_mss);
    FIELD(tcpi_unacked); FIELD(tcpi_retrans); FIELD(tcpi_rtt); FIELD(tcpi_rttvar);
    FIELD(tcpi_snd_cwnd); FIELD(tcpi_rcv_rtt); FIELD(tcpi_rcv_space); FIELD(tcpi_total_retrans);
#undef FIELD
    return out;
}
}
#define JNI(name) extern "C" JNIEXPORT name
#define METHOD(name) Java_com_bufferbloatshaper_harness_SocketNative_##name

JNI(jint) METHOD(open)(JNIEnv*, jobject, jboolean ipv6) {
    int fd = socket(ipv6 ? AF_INET6 : AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, IPPROTO_TCP);
    return fd < 0 ? -errno : fd;
}
JNI(jint) METHOD(close)(JNIEnv*, jobject, jint fd) {
    // Never retry close after EINTR: the descriptor may already have been released.
    return ::close(fd) == 0 ? 0 : errno;
}
JNI(jint) METHOD(connect)(JNIEnv* env, jobject, jint fd, jstring address, jint port) {
    const char* text = env->GetStringUTFChars(address, nullptr);
    if (!text) return ENOMEM;
    sockaddr_storage storage{};
    socklen_t size;
    auto* v4 = reinterpret_cast<sockaddr_in*>(&storage);
    auto* v6 = reinterpret_cast<sockaddr_in6*>(&storage);
    if (inet_pton(AF_INET, text, &v4->sin_addr) == 1) {
        v4->sin_family = AF_INET; v4->sin_port = htons(port); size = sizeof(*v4);
    } else if (inet_pton(AF_INET6, text, &v6->sin6_addr) == 1) {
        v6->sin6_family = AF_INET6; v6->sin6_port = htons(port); size = sizeof(*v6);
    } else {
        env->ReleaseStringUTFChars(address, text); return EINVAL;
    }
    env->ReleaseStringUTFChars(address, text);
    return ::connect(fd, reinterpret_cast<sockaddr*>(&storage), size) == 0 ? 0 : errno;
}
JNI(jint) METHOD(poll)(JNIEnv*, jobject, jint fd, jboolean writing) {
    pollfd p{fd, static_cast<short>(writing ? POLLOUT : POLLIN), 0};
    int result = ::poll(&p, 1, 50);
    return result < 0 ? -errno : (result == 0 ? 0 : p.revents);
}
JNI(jint) METHOD(socketError)(JNIEnv*, jobject, jint fd) {
    int value = 0; socklen_t length = sizeof(value);
    return getsockopt(fd, SOL_SOCKET, SO_ERROR, &value, &length) == 0 ? value : errno;
}
JNI(jint) METHOD(read)(JNIEnv* env, jobject, jint fd, jbyteArray target, jint limit) {
    if (limit < 1 || limit > 16384 || env->GetArrayLength(target) < limit) return -EINVAL;
    char buffer[16384];
    auto count = recv(fd, buffer, limit, 0);
    if (count < 0) return -errno;
    if (count) env->SetByteArrayRegion(target, 0, count, reinterpret_cast<jbyte*>(buffer));
    return static_cast<jint>(count);
}
JNI(jlongArray) METHOD(option)(JNIEnv* env, jobject, jint fd, jint kind, jboolean set, jint requested) {
    int level = SOL_SOCKET, option = SO_RCVBUF;
    bool available = true;
    if (kind == 1) {
#ifdef TCP_WINDOW_CLAMP
        level = IPPROTO_TCP; option = TCP_WINDOW_CLAMP;
#else
        available = false;
#endif
    } else if (kind != 0) available = false;
    if (!available) return longs(env, {0, -1, -1, -1, 0}); // no syscall attempted, no fabricated errno
    int setError = -1;
    if (set) setError = setsockopt(fd, level, option, &requested, sizeof(requested)) == 0 ? 0 : errno;
    int value = 0; socklen_t length = sizeof(value);
    int getError = getsockopt(fd, level, option, &value, &length) == 0 ? 0 : errno;
    return longs(env, {1, setError, getError, getError || length != sizeof(value) ? -1 : value, length});
}
JNI(jlongArray) METHOD(info)(JNIEnv* env, jobject, jint fd) {
    tcp_info info{};
    socklen_t length = sizeof(info); // reset on every call; kernel may return a prefix
    int error = getsockopt(fd, IPPROTO_TCP, TCP_INFO, &info, &length) == 0 ? 0 : errno;
    return longs(env, decode(info, length, error));
}
JNI(jlongArray) METHOD(decodeFixture)(JNIEnv* env, jobject, jint length, jint error) {
    tcp_info info{};
    info.tcpi_state = 7; info.tcpi_rtt = 123456; info.tcpi_total_retrans = 19;
    return longs(env, decode(info, length < 0 ? 0 : static_cast<size_t>(length), error));
}
JNI(jintArray) METHOD(fieldEnds)(JNIEnv* env, jobject) {
#define END(name) offsetof(tcp_info, name) + sizeof(tcp_info::name)
    const jint ends[] = {END(tcpi_state), END(tcpi_rto), END(tcpi_snd_mss), END(tcpi_rcv_mss),
        END(tcpi_unacked), END(tcpi_retrans), END(tcpi_rtt), END(tcpi_rttvar), END(tcpi_snd_cwnd),
        END(tcpi_rcv_rtt), END(tcpi_rcv_space), END(tcpi_total_retrans)};
#undef END
    auto out = env->NewIntArray(12);
    if (out) env->SetIntArrayRegion(out, 0, 12, ends);
    return out;
}
