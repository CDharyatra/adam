cmake_minimum_required(VERSION 3.22.1)
project("adam_llm")

# ── MLC LLM JNI bridge ───────────────────────────────────────────────────────
add_library(mlc_llm_jni SHARED src/main/cpp/mlc_llm_jni.cpp)

# Check whether the MLC LLM AAR has been extracted into libs/mlc_llm/.
# The AAR unpacks its headers under:  libs/mlc_llm/include/
# and its prebuilt .so under:         libs/mlc_llm/jni/<ABI>/libmlc_llm.so
set(MLC_LLM_ROOT "${CMAKE_SOURCE_DIR}/libs/mlc_llm")
set(MLC_LLM_SO   "${MLC_LLM_ROOT}/jni/${ANDROID_ABI}/libmlc_llm.so")

# Android 15+ requires native libs to be 16 KB page-aligned for ARM64.
# -Wl,-z,max-page-size=16384 emits ELF LOAD segments aligned to 16 KB.
target_link_options(mlc_llm_jni PRIVATE "-Wl,-z,max-page-size=16384")

if(EXISTS "${MLC_LLM_SO}")
    message(STATUS "MLC LLM found — enabling native LLM inference")
    target_compile_definitions(mlc_llm_jni PRIVATE MLC_LLM_AVAILABLE=1)
    target_include_directories(mlc_llm_jni PRIVATE "${MLC_LLM_ROOT}/include")
    add_library(mlc_llm SHARED IMPORTED)
    set_target_properties(mlc_llm PROPERTIES IMPORTED_LOCATION "${MLC_LLM_SO}")
    target_link_libraries(mlc_llm_jni mlc_llm android log)
else()
    message(STATUS "MLC LLM not found — regex fallback will be used")
    target_link_libraries(mlc_llm_jni android log)
endif()
