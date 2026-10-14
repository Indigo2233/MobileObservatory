cmake_minimum_required(VERSION 3.22.1)
project(indeconv_mnn_android_probe LANGUAGES CXX)

if(NOT DEFINED MNN_SOURCE_DIR OR NOT IS_DIRECTORY "${MNN_SOURCE_DIR}")
    message(FATAL_ERROR "MNN_SOURCE_DIR is required")
endif()
if(NOT DEFINED MNN_LIBRARY OR NOT EXISTS "${MNN_LIBRARY}")
    message(FATAL_ERROR "MNN_LIBRARY is required")
endif()

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${MNN_LIBRARY}")

add_library(indeconv_probe SHARED
    "../../../../../main.cpp"
    "probe_jni.cpp")
target_compile_features(indeconv_probe PRIVATE cxx_std_17)
target_compile_options(indeconv_probe PRIVATE -O3 -fno-fast-math)
target_compile_definitions(indeconv_probe PRIVATE INDECONV_BENCH_NO_MAIN=1)
target_include_directories(indeconv_probe PRIVATE "${MNN_SOURCE_DIR}/include")
target_link_libraries(indeconv_probe PRIVATE MNN log android dl)
