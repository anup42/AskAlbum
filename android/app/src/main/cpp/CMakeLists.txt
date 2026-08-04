cmake_minimum_required(VERSION 3.22.1)
project(askalbum_vector LANGUAGES CXX)

add_library(gallery_vector SHARED native_vector_scanner.cpp)
target_compile_features(gallery_vector PRIVATE cxx_std_17)
target_compile_options(gallery_vector PRIVATE -O3)

# Keep the native scanner on the Android arm64 baseline. The scalar half-float
# implementation in native_vector_scanner.cpp is safe on devices that do not
# expose ARMv8.2 FP16. A future runtime-dispatched SIMD path must not be
# enabled through a universal -march flag because arm64-v8a includes older
# devices without those instructions.
