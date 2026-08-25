cmake_minimum_required(VERSION 3.16)
project(recon_clang_parser CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(recon_clang_parser main.cpp)

install(TARGETS recon_clang_parser RUNTIME DESTINATION bin)