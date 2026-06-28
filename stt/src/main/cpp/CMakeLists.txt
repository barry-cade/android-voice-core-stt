cmake_minimum_required(VERSION 3.22)
project(whisper_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(whisper_bridge SHARED
    whisper_bridge.cpp
)

find_library(log-lib log)

add_library(whisper_prebuilt SHARED IMPORTED)
set_target_properties(whisper_prebuilt PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libwhisper.so"
)

set(WHISPER_CPP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../whisper.cpp")

target_include_directories(whisper_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WHISPER_CPP_ROOT}/include
    ${WHISPER_CPP_ROOT}/ggml/include
    ${WHISPER_CPP_ROOT}/ggml/src
)

target_link_libraries(whisper_bridge
    PRIVATE
    ${log-lib}
    whisper_prebuilt
)
