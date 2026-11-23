cmake_minimum_required(VERSION 3.22.1)

project("usbaudioengine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/alac
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wavpack
)

# Official Apple ALAC decoder (Apache 2.0)
set(ALAC_SOURCES
    third_party/alac/ALACBitUtilities.c
    third_party/alac/ALACDecoder.cpp
    third_party/alac/EndianPortable.c
    third_party/alac/ag_dec.c
    third_party/alac/dp_dec.c
    third_party/alac/matrix_dec.c
)

# Official WavPack 5.7.0 decoder-only sources (BSD-3-Clause, supports PCM + Float + DSD)
set(WAVPACK_SOURCES
    third_party/wavpack/common_utils.c
    third_party/wavpack/decorr_utils.c
    third_party/wavpack/entropy_utils.c
    third_party/wavpack/open_legacy.c
    third_party/wavpack/open_raw.c
    third_party/wavpack/open_utils.c
    third_party/wavpack/read_words.c
    third_party/wavpack/tag_utils.c
    third_party/wavpack/tags.c
    third_party/wavpack/unpack.c
    third_party/wavpack/unpack3.c
    third_party/wavpack/unpack3_open.c
    third_party/wavpack/unpack3_seek.c
    third_party/wavpack/unpack_dsd.c
    third_party/wavpack/unpack_floats.c
    third_party/wavpack/unpack_seek.c
    third_party/wavpack/unpack_utils.c
)

add_library(
    usbaudioengine
    SHARED
    audio_utils_jni.cpp
    audio_engine.cpp
    usb_audio_driver.cpp
    dsp_processor.cpp
    dsd_decoder.cpp
    sinc_resampler.cpp
    native_decoder.cpp
    alac_mp4_decoder.cpp
    tta_decoder.cpp
    third_party/stb_vorbis.c
    ${ALAC_SOURCES}
    ${WAVPACK_SOURCES}
)

target_compile_definitions(usbaudioengine PRIVATE
    ENABLE_DSD=1
    ENABLE_LEGACY=1
    STB_VORBIS_NO_PUSHDATA_API=1
)

# Keep third-party C libraries optimized and warning-clean
set_source_files_properties(
    ${WAVPACK_SOURCES}
    ${ALAC_SOURCES}
    third_party/stb_vorbis.c
    PROPERTIES COMPILE_FLAGS "-O3 -fvisibility=hidden -Wno-unused-variable -Wno-sign-compare -Wno-pointer-sign"
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(opensles-lib OpenSLES)

target_link_libraries(
    usbaudioengine
    ${log-lib}
    ${aaudio-lib}
    ${opensles-lib}
)
