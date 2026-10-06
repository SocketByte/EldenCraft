#include "gpu_host.hpp"
#include <d3d12.h>
#include <windows.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <string_view>

namespace eldencraft::gpu {
using namespace reshade::api;
namespace {
void log_line(reshade::log::level level, const char *text) { reshade::log::message(level, text); }
template<class T> void put(std::uint8_t *view, std::size_t offset, T value) {
    std::memcpy(view + offset, &value, sizeof(T));
}
std::uint64_t read_u64(const std::uint8_t *view, std::size_t offset) {
    const auto value = *reinterpret_cast<const volatile std::uint64_t *>(view + offset);
    std::atomic_thread_fence(std::memory_order_seq_cst);
    return value;
}
} // namespace

void Host::poll(effect_runtime *runtime, std::uint64_t now) {
    if (disabled_) return;
    if (!view_) {
        if (now < next_attempt_) return;
        next_attempt_ = now + 1000;
        // Open-or-create: whichever process starts first owns the zeroed page.
        mapping_ = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, static_cast<DWORD>(mapping_bytes), mapping_name);
        if (!mapping_) return;
        view_ = static_cast<std::uint8_t *>(MapViewOfFile(mapping_, FILE_MAP_ALL_ACCESS, 0, 0, mapping_bytes));
        if (!view_) { CloseHandle(mapping_); mapping_ = nullptr; return; }
    }
    char setting[8]{};
    if (GetEnvironmentVariableA("ELDENCRAFT_GPU_TRANSPORT", setting, sizeof(setting)) == 1 && setting[0] == '0') {
        disabled_ = true;
        log_line(reshade::log::level::info, "EldenCraft GPU transport disabled by ELDENCRAFT_GPU_TRANSPORT=0; using shared-memory frames.");
        return;
    }
    // Tell the guest the host back-buffer size so it can render at full resolution.
    std::uint32_t back_width = 0, back_height = 0;
    runtime->get_screenshot_width_and_height(&back_width, &back_height);
    if (back_width > max_width || back_height > max_height) back_width = back_height = 0;
    put<std::uint32_t>(view_, preferred_offset, back_width);
    put<std::uint32_t>(view_, preferred_offset + 4, back_height);
    std::array<std::uint8_t, mapping_bytes> copy{};
    std::memcpy(copy.data(), view_, copy.size());
    Request request;
    if (!decode_request(copy, request)) return;
    // The guest reports a failed import of this generation; do not rebuild it.
    if (request.status == status_failed && request.status_generation == generation_ && generation_) {
        if (failed_generation_ != generation_) {
            failed_generation_ = generation_;
            log_line(reshade::log::level::warning, "EldenCraft GPU transport: Minecraft could not import the shared textures; it keeps shared-memory frames.");
        }
        return;
    }
    if (generation_ && request.width == width_ && request.height == height_) return;
    if (now < next_attempt_) return;
    next_attempt_ = now + 1000;
    create(runtime, request.width, request.height);
}

bool Host::create(effect_runtime *runtime, std::uint32_t width, std::uint32_t height) {
    release(runtime);
    auto *device = reinterpret_cast<ID3D12Device *>(runtime->get_device()->get_native());
    if (!device) return false;
    device->AddRef();
    device_ = device;
    static std::uint32_t counter = 0;
    std::uint32_t generation = (GetTickCount() ^ (++counter << 24)) | 1u;
    const auto pid = GetCurrentProcessId();
    D3D12_HEAP_PROPERTIES heap{};
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    std::array<std::uint64_t, planes> plane_bytes{};
    for (std::uint32_t set = 0; set < sets; ++set) {
        for (std::uint32_t plane = 0; plane < planes; ++plane) {
            D3D12_RESOURCE_DESC desc{};
            desc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
            desc.Width = width; desc.Height = height; desc.DepthOrArraySize = 1; desc.MipLevels = 1;
            desc.Format = depth_plane(plane) ? DXGI_FORMAT_R32_FLOAT : DXGI_FORMAT_R8G8B8A8_UNORM;
            desc.SampleDesc.Count = 1;
            desc.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
            // Simultaneous access: OpenGL writes and this queue copies without
            // cross-API barriers; reads/writes are ordered by the shared fence.
            desc.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET | D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS;
            ID3D12Resource *texture = nullptr;
            HRESULT result = device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_SHARED, &desc, D3D12_RESOURCE_STATE_COMMON,
                nullptr, __uuidof(ID3D12Resource), reinterpret_cast<void **>(&texture));
            HANDLE handle = nullptr;
            if (SUCCEEDED(result))
                result = device->CreateSharedHandle(texture, nullptr, GENERIC_ALL, texture_name(pid, generation, set, plane).c_str(), &handle);
            if (FAILED(result)) {
                if (texture) texture->Release();
                char message[160]{};
                std::snprintf(message, sizeof(message), "EldenCraft GPU transport: shared texture creation failed (0x%08lx); using shared-memory frames.", static_cast<unsigned long>(result));
                log_line(reshade::log::level::warning, message);
                release(runtime);
                disabled_ = true;
                return false;
            }
            textures_[set][plane] = texture;
            handles_[set][plane] = handle;
            plane_bytes[plane] = device->GetResourceAllocationInfo(0, 1, &desc).SizeInBytes;
        }
    }
    HRESULT result = device->CreateFence(0, D3D12_FENCE_FLAG_SHARED, __uuidof(ID3D12Fence), reinterpret_cast<void **>(&ready_));
    if (SUCCEEDED(result)) result = device->CreateSharedHandle(ready_, nullptr, GENERIC_ALL, fence_name(pid, generation).c_str(), &ready_handle_);
    if (FAILED(result)) {
        log_line(reshade::log::level::warning, "EldenCraft GPU transport: shared fence creation failed; using shared-memory frames.");
        release(runtime);
        disabled_ = true;
        return false;
    }
    // Publish the block with the magic last, so the guest never imports a half-written description.
    put<std::uint32_t>(view_, 0, 0);
    std::atomic_thread_fence(std::memory_order_seq_cst);
    put<std::uint32_t>(view_, 4, version);
    put<std::uint32_t>(view_, 8, pid);
    put<std::uint32_t>(view_, 12, generation);
    put<std::uint32_t>(view_, 16, width);
    put<std::uint32_t>(view_, 20, height);
    put<std::uint32_t>(view_, 24, sets);
    put<std::uint32_t>(view_, 28, planes);
    for (std::uint32_t plane = 0; plane < planes; ++plane) put<std::uint64_t>(view_, plane_bytes_offset + plane * 8, plane_bytes[plane]);
    for (std::uint32_t set = 0; set < sets; ++set) put<std::uint64_t>(view_, ack_offset + set * 8, 0);
    std::atomic_thread_fence(std::memory_order_seq_cst);
    put<std::uint32_t>(view_, 0, magic);
    generation_ = generation; width_ = width; height_ = height;
    char message[192]{};
    std::snprintf(message, sizeof(message), "EldenCraft GPU transport: %ux%u shared textures ready (generation %u); Minecraft frames need no CPU readback.", width, height, generation);
    log_line(reshade::log::level::info, message);
    return true;
}

bool Host::ready(const frames::Descriptor &descriptor) const {
    return generation_ && ready_ && descriptor.gpu_generation == generation_ && descriptor.gpu_set < sets
        && descriptor.width == width_ && descriptor.height == height_
        && ready_->GetCompletedValue() >= descriptor.minecraft_frame;
}

resource Host::plane(std::uint32_t set, std::uint32_t plane) const {
    if (set >= sets || plane >= planes || !textures_[set][plane]) return {};
    return {reinterpret_cast<std::uint64_t>(textures_[set][plane])};
}

void Host::publish_ack(std::uint32_t set, std::uint64_t frame) {
    if (!view_ || set >= sets) return;
    auto *slot = reinterpret_cast<volatile std::uint64_t *>(view_ + ack_offset + set * 8);
    if (read_u64(view_, ack_offset + set * 8) < frame) *slot = frame;
    std::atomic_thread_fence(std::memory_order_seq_cst);
}

void Host::retire(std::uint64_t completed_fence) {
    std::erase_if(pending_, [&](const PendingAck &ack) {
        if (ack.fence > completed_fence) return false;
        publish_ack(ack.set, ack.frame);
        return true;
    });
}

void Host::release(effect_runtime *runtime) {
    if (view_) { put<std::uint32_t>(view_, 0, 0); std::atomic_thread_fence(std::memory_order_seq_cst); }
    const bool any = ready_ || textures_[0][0];
    // Only reached on resize or shutdown; the GPU may still read the old sets.
    if (any && runtime) runtime->get_command_queue()->wait_idle();
    for (auto &set : textures_) for (auto &texture : set) { if (texture) texture->Release(); texture = nullptr; }
    for (auto &set : handles_) for (auto &handle : set) { if (handle) CloseHandle(handle); handle = nullptr; }
    if (ready_) ready_->Release();
    if (ready_handle_) CloseHandle(ready_handle_);
    ready_ = nullptr; ready_handle_ = nullptr;
    if (device_) device_->Release();
    device_ = nullptr;
    generation_ = width_ = height_ = 0;
    pending_.clear();
}

void Host::destroy(effect_runtime *runtime) {
    release(runtime);
    if (view_) UnmapViewOfFile(view_);
    if (mapping_) CloseHandle(mapping_);
    view_ = nullptr; mapping_ = nullptr;
}
} // namespace eldencraft::gpu
