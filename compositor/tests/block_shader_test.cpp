#include "block_shader.hpp"
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <iostream>

int main(){
    struct Stage {const char *entry;const char *profile;};
    constexpr Stage stages[]={{"VS","vs_5_0"},{"PS","ps_5_0"},{"VS","vs_5_0"}};
    bool failed=false;
    unsigned variant=0;
    for(const auto &stage:stages){
        const D3D_SHADER_MACRO macros[]={{"RAW_LIGHT","1"},{nullptr,nullptr}};
        Microsoft::WRL::ComPtr<ID3DBlob> bytecode,diagnostics;
        const HRESULT result=D3DCompile(
            eldencraft::blocks::block_shader,sizeof(eldencraft::blocks::block_shader)-1,
            "block_shader.hpp",variant++==2?macros:nullptr,nullptr,stage.entry,stage.profile,
            D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3,0,
            &bytecode,&diagnostics);
        if(diagnostics&&diagnostics->GetBufferSize()){
            std::cerr<<stage.entry<<" ("<<stage.profile<<") compiler diagnostics:\n";
            std::cerr.write(static_cast<const char *>(diagnostics->GetBufferPointer()),
                static_cast<std::streamsize>(diagnostics->GetBufferSize()));
            std::cerr<<'\n';
        }
        if(FAILED(result)||!bytecode||!bytecode->GetBufferSize()){
            std::cerr<<stage.entry<<" ("<<stage.profile<<") failed: HRESULT=0x"
                <<std::hex<<static_cast<unsigned long>(result)<<std::dec<<'\n';
            failed=true;
        }else{
            std::cout<<stage.entry<<" ("<<stage.profile<<"): "
                <<bytecode->GetBufferSize()<<" bytes of shader bytecode\n";
        }
    }
    if(failed)return 1;
    std::cout<<"Block shader: both runtime entry points compile. GPU rendering remains an integration check.\n";
    return 0;
}
