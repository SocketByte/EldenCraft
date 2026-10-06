#pragma once
#include "frame_protocol.hpp"
#include "scene_protocol.hpp"
#include <windows.h>
#include <array>
#include <atomic>

namespace eldencraft::frames {
// Windows x64 only. Producer publishes aligned 64-bit sequence counters with a
// full fence. Volatile aligned reads plus the fence prevent compiler/CPU reorder.
// Pixels are never trusted until the slot seqlock is re-validated after copying:
// poll() copies into private memory, while acquire()/commit() lets the add-on copy
// straight into its GPU upload heap and drop the upload when the copy was torn.
class MappingReader {
    HANDLE mapping_{}, process_{};
    const std::uint8_t *view_{};
    const wchar_t *name_;
    std::uint32_t pid_{};
    std::uint64_t stride_{};
    std::uint64_t next_open_{};
    Freshness freshness_;
    struct Pending {
        const std::uint8_t *desc{}, *base{};
        std::uint64_t sequence{}, publication{};
        Descriptor descriptor; Scene scene;
    } pending_;
    bool acquired_{};
    static std::uint64_t counter(const std::uint8_t *p) {
        const auto value = *reinterpret_cast<const volatile std::uint64_t *>(p);
        std::atomic_thread_fence(std::memory_order_seq_cst);
        return value;
    }
    bool open(std::uint64_t now) {
        if (view_) return true;
        if (now < next_open_) return false;
        next_open_ = now + 1000;
        mapping_ = OpenFileMappingW(FILE_MAP_READ, FALSE, name_);
        if (!mapping_) return false;
        view_ = static_cast<const std::uint8_t *>(MapViewOfFile(mapping_, FILE_MAP_READ, 0, 0, header_bytes));
        Header header;
        if (!view_ || !decode_header({view_, header_bytes}, header)) { close(); return false; }
        stride_=header.stride;UnmapViewOfFile(view_);view_=nullptr;
        // Map the exact validated size; a header claiming absent avatar planes is rejected by Win32.
        view_=static_cast<const std::uint8_t *>(MapViewOfFile(mapping_,FILE_MAP_READ,0,0,header_bytes+stride_*slots));
        if(!view_){close();return false;}
        pid_ = header.pid;
        process_ = OpenProcess(SYNCHRONIZE, FALSE, pid_);
        if (!process_ || WaitForSingleObject(process_, 0) != WAIT_TIMEOUT) { close(); return false; }
        return true;
    }
    // Validates the newest coherent publication without touching pixel planes.
    bool begin(std::uint64_t now) {
        acquired_=false;
        if (!open(now)) return false;
        if (WaitForSingleObject(process_, 0) != WAIT_TIMEOUT) { close(); return false; }
        Header header;
        if (!decode_header({view_, header_bytes}, header) || header.pid != pid_||header.stride!=stride_) { close(); return false; }
        const auto publication = counter(view_ + 32);
        if (publication != header.publication) return false;
        if (publication == 0 || header.latest < 0) { freshness_.invalidate(); return false; }
        if (publication < freshness_.publication()) { close(); return false; }
        if (publication == freshness_.publication()) return false;
        const auto *desc = view_ + descriptor_offset + descriptor_bytes * header.latest;
        const auto sequence = counter(desc);
        if (sequence & 1) return false;
        std::array<std::uint8_t, descriptor_bytes> descriptor_copy{};
        std::memcpy(descriptor_copy.data(), desc, descriptor_copy.size());
        Descriptor parsed;
        if (!decode_descriptor(descriptor_copy, parsed,stride_) || parsed.sequence != sequence) return false;
        Scene next_scene;
        if(parsed.flags&16) {
            std::array<std::uint8_t,scene_bytes> extra{};
            std::memcpy(extra.data(),view_+scene_offset+scene_bytes*header.latest,extra.size());
            if(!decode_scene(extra,next_scene))return false;
            if(bool(parsed.flags&32)!=bool(next_scene.avatar_mode))return false;
        }
        pending_={desc,view_+header_bytes+stride_*header.latest,sequence,publication,parsed,next_scene};
        acquired_=true;return true;
    }
    // Seqlock re-validation after the caller copied every plane it needs.
    bool coherent() const {
        std::atomic_thread_fence(std::memory_order_seq_cst);
        return acquired_ && counter(pending_.desc) == pending_.sequence && counter(view_ + 32) == pending_.publication
            && read<std::uint32_t>({view_, header_bytes}, 44) == pid_;
    }
public:
    Scene scene;
    std::vector<std::uint8_t> world,depth,avatar,avatar_depth;
    explicit MappingReader(const wchar_t *name=mapping_name):name_(name){}
    MappingReader(const MappingReader &) = delete;
    MappingReader &operator=(const MappingReader &) = delete;
    ~MappingReader() { close(); }
    void close() {
        if (view_) UnmapViewOfFile(view_);
        if (mapping_) CloseHandle(mapping_);
        if (process_) CloseHandle(process_);
        view_ = nullptr; mapping_ = process_ = nullptr; pid_ = 0;stride_=0;acquired_=false;
        freshness_.reset();
        scene={};world.clear();depth.clear();avatar.clear();avatar_depth.clear();
    }
    bool fresh(std::uint64_t now) const { return freshness_.fresh(now); }
    // Metadata inspection never touches pixel planes, avoiding measurement copies.
    bool poll(Frame &frame, std::uint64_t now, bool copy_overlay=true) {
        if (!begin(now)) return false;
        const auto &parsed=pending_.descriptor;
        // GPU-shared publications carry no CPU pixels; only metadata is read.
        if(copy_overlay&&!(parsed.flags&gpu_shared_flag)) {
            frame.overlay.resize(parsed.layer_bytes);
            std::memcpy(frame.overlay.data(), plane(2), parsed.layer_bytes);
            if(parsed.flags&16){world.resize(parsed.layer_bytes);depth.resize(parsed.layer_bytes);
                std::memcpy(world.data(),plane(0),parsed.layer_bytes);std::memcpy(depth.data(),plane(1),parsed.layer_bytes);}
            if(parsed.flags&32){avatar.resize(parsed.layer_bytes);avatar_depth.resize(parsed.layer_bytes);
                std::memcpy(avatar.data(),plane(3),parsed.layer_bytes);
                std::memcpy(avatar_depth.data(),plane(4),parsed.layer_bytes);}
        }
        return commit(frame, now);
    }
    // Zero-copy route: acquire(), copy the needed plane(i) bytes, then commit().
    // A false commit means the producer reused the slot during the copy; the
    // copied bytes must be discarded and the publication may be retried later.
    bool acquire(std::uint64_t now) { return begin(now); }
    const Descriptor &descriptor() const { return pending_.descriptor; }
    const Scene &pending_scene() const { return pending_.scene; }
    // Plane order matches the producer: world, depth, overlay, avatar, avatar depth.
    const std::uint8_t *plane(std::size_t index) const {
        if(pending_.descriptor.flags&gpu_shared_flag)return nullptr;
        const std::size_t planes=(pending_.descriptor.flags&32u)?5:3;
        return acquired_&&index<planes?pending_.base+pending_.descriptor.layer_bytes*index:nullptr;
    }
    bool commit(Frame &frame, std::uint64_t now) {
        if(!coherent()){acquired_=false;return false;}
        acquired_=false;
        frame.descriptor = pending_.descriptor; frame.publication = pending_.publication;
        scene=pending_.scene;
        return freshness_.observe(pending_.publication,now);
    }
};
} // namespace eldencraft::frames
