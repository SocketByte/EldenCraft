#include "block_sort.hpp"
#include <iostream>
#include <stdexcept>

int main(){try{
    using namespace eldencraft::blocks;
    const std::array<TriangleCenter,5> centers={{{0,0,30},{0,0,6},{0,0,30},{-9,0,12},{12,0,3}}};
    std::vector<std::pair<double,std::uint32_t>> work;std::vector<std::uint32_t> indices;
    translucent_order(centers,{0,0,1},12,work,indices);
    const std::vector<std::uint32_t> expected={12,13,14,18,19,20,21,22,23,15,16,17,24,25,26};
    if(indices!=expected)throw std::runtime_error("back-to-front order/tie stability changed");
    auto *storage=indices.data();translucent_order(centers,{0,0,1},12,work,indices);
    if(indices!=expected||storage!=indices.data())throw std::runtime_error("stationary view reallocates");
    translucent_order(centers,{1,0,0},12,work,indices);
    if(indices[0]!=24||indices.back()!=23)throw std::runtime_error("rotation failed to resort");
    if(sort_view_changed({0,0,1},{0,0,1})||!sort_view_changed({0,0,1},{1,0,0}))throw std::runtime_error("sort key changed");
    // Independent old depth expression: eye translation subtracts one common
    // value, so translation cannot change the expected order or require an upload.
    for(double eye:{-1e5,-3.,0.,17.,1e5}){
        std::vector<std::pair<double,unsigned>> old;
        for(unsigned i=0;i<centers.size();++i)old.emplace_back(centers[i][2]-eye*3,i);
        std::stable_sort(old.begin(),old.end(),[](const auto&a,const auto&b){return a.first>b.first;});
        translucent_order(centers,{0,0,1},12,work,indices);
        for(unsigned i=0;i<old.size();++i)if(indices[i*3]!=12+old[i].second*3)throw std::runtime_error("translation changes triangle order");
    }
    std::cout<<"Translucency: depth order, stable ties, rotation and translation invariance passed\n";return 0;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
