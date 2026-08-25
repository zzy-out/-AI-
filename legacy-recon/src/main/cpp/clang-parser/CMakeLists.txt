cmake_minimum_required(VERSION 3.16)
project(recon_clang_parser CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(recon_clang_parser main.cpp)

# libclang（可选依赖）：找到 → 语义级提取（RECON_HAS_LIBCLANG）；找不到 → 协议骨架降级
find_library(RECON_LIBCLANG
        NAMES libclang-18.so.18 libclang-18 libclang.so.1 libclang
        HINTS /usr/lib/llvm-18/lib /usr/lib/llvm-17/lib /usr/lib/llvm-16/lib
              /usr/lib/x86_64-linux-gnu /usr/local/lib)

if(RECON_LIBCLANG)
    message(STATUS "libclang found: ${RECON_LIBCLANG}")
    target_compile_definitions(recon_clang_parser PRIVATE RECON_HAS_LIBCLANG)
    # vendored clang-c 头文件（Apache-2.0 w/ LLVM exception，见 clang-c/LICENSE.TXT）
    target_include_directories(recon_clang_parser PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(recon_clang_parser PRIVATE ${RECON_LIBCLANG})
else()
    message(WARNING "libclang not found: 构建协议骨架降级版（仅 File 实体）。"
            "安装 libclang（如 libclang-18-dev 或 libclang.so 运行库）后重新构建可启用语义级提取。")
endif()

install(TARGETS recon_clang_parser RUNTIME DESTINATION bin)
