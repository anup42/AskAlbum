cmake_minimum_required(VERSION 3.22.1)
project(agenticgallery_vector LANGUAGES CXX)

add_library(gallery_vector SHARED native_vector_scanner.cpp)
target_compile_features(gallery_vector PRIVATE cxx_std_17)
target_compile_options(gallery_vector PRIVATE -O3)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(gallery_vector PRIVATE -march=armv8.2-a+fp16)
    target_compile_definitions(gallery_vector PRIVATE AGENTICGALLERY_ARM64_FP16=1)
endif()
