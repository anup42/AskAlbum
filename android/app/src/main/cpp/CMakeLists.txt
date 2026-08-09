cmake_minimum_required(VERSION 3.22.1)
project(askalbum_vector LANGUAGES CXX)

add_library(gallery_vector SHARED native_vector_scanner.cpp)
target_compile_features(gallery_vector PRIVATE cxx_std_17)
target_compile_options(gallery_vector PRIVATE -O3)

# Keep the shipped binary on the portable scalar path. A future tuned library
# may define ASKALBUM_ARM64_FP16 only after runtime CPU-feature dispatch.
