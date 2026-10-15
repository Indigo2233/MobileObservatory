#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

// ABI of the pinned ASISDK_ANDROID arm64 library. Keep the vendor .so unchanged.
// ASIOpenCamera dispatches 0x676C to this constructor, which incorrectly stores
// the MC PID (0x676D) at object+680. Correct the MM object before OpenCamera.
namespace zwo676 {
using Constructor = void (*)(void*);
constexpr const char* kConstructor = "_ZN17CCameraS676MM_DDRC1Ev";
constexpr std::uintptr_t kConstructorOffset = 0x14e148;
constexpr std::size_t kPidOffset = 680;
constexpr std::uint32_t kWrongPid = 0x676d;
constexpr std::uint32_t kCorrectPid = 0x676c;

constexpr unsigned char kBuildIdNote[] = {
    4, 0, 0, 0, 20, 0, 0, 0, 3, 0, 0, 0, 'G', 'N', 'U', 0,
    0x3f, 0x44, 0x10, 0xb2, 0x17, 0xf9, 0x0b, 0x86, 0xf4, 0xd5,
    0x12, 0x98, 0xf9, 0xf5, 0x3a, 0x5e, 0x50, 0x4b, 0x63, 0x79,
};

inline bool matchesBuildId(const void* note, std::size_t size) {
    return size == sizeof(kBuildIdNote) &&
           std::memcmp(note, kBuildIdNote, sizeof(kBuildIdNote)) == 0;
}

inline bool matchesConstructor(const void* constructor) {
    const auto* code = static_cast<const unsigned char*>(constructor);
    std::uint32_t load_pid = 0, store_pid = 0;
    std::memcpy(&load_pid, code + 0x198, sizeof(load_pid));
    std::memcpy(&store_pid, code + 0x1a4, sizeof(store_pid));
    return load_pid == 0x528ceda8 && store_pid == 0xb902aa68;
}

inline bool construct(
        void* camera,
        Constructor original,
        std::uint32_t* pid_before = nullptr,
        std::uint32_t* pid_after = nullptr) {
    original(camera);
    auto* pid_address = static_cast<unsigned char*>(camera) + kPidOffset;
    std::uint32_t pid = 0;
    std::memcpy(&pid, pid_address, sizeof(pid));
    if (pid_before) *pid_before = pid;
    if (pid != kWrongPid) return false;
    const std::uint32_t corrected = kCorrectPid;
    std::memcpy(pid_address, &corrected, sizeof(corrected));
    if (pid_after) *pid_after = corrected;
    return true;
}
}  // namespace zwo676
