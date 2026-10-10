#pragma once
#include <reshade.hpp>
#include <array>

namespace eldencraft::frames {
class SceneMask {
    reshade::api::pipeline_layout layout_{};
    std::array<reshade::api::pipeline,2> pipelines_{};
    std::array<reshade::api::resource,2> textures_{};
    std::array<reshade::api::resource_view,2> srvs_{},uavs_{};
    bool failed_{};
public:
    bool prepare(reshade::api::effect_runtime *);
    bool update(reshade::api::command_list *,reshade::api::resource_view depth,std::uint32_t w,std::uint32_t h);
    void destroy(reshade::api::effect_runtime *);
    reshade::api::resource_view view()const{return srvs_[0];}
    reshade::api::resource_view bounds_view()const{return srvs_[1];}
};
}
