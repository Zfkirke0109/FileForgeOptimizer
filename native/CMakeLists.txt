cmake_minimum_required(VERSION 3.22)
project(fileforge_native_tools LANGUAGES C CXX)

foreach(required_path IN ITEMS
    FILEFORGE_ZOPFLI_SOURCE
    FILEFORGE_AOSP_BUILD_SOURCE
    FILEFORGE_SYSTEM_CORE_SOURCE
    FILEFORGE_LIBZIPARCHIVE_SOURCE)
  if(NOT DEFINED ${required_path} OR NOT EXISTS "${${required_path}}")
    message(FATAL_ERROR "${required_path} must point to a verified source checkout")
  endif()
endforeach()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ZOPFLI_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZOPFLI_BUILD_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${FILEFORGE_ZOPFLI_SOURCE}" zopfli EXCLUDE_FROM_ALL)

add_executable(fileforge_zipalign
  "${FILEFORGE_AOSP_BUILD_SOURCE}/tools/zipalign/ZipAlign.cpp"
  "${FILEFORGE_AOSP_BUILD_SOURCE}/tools/zipalign/ZipAlignMain.cpp"
  "${FILEFORGE_AOSP_BUILD_SOURCE}/tools/zipalign/ZipEntry.cpp"
  "${FILEFORGE_AOSP_BUILD_SOURCE}/tools/zipalign/ZipFile.cpp"
  "${FILEFORGE_SYSTEM_CORE_SOURCE}/libutils/Errors.cpp"
  "${FILEFORGE_SYSTEM_CORE_SOURCE}/libutils/SharedBuffer.cpp"
  "${FILEFORGE_SYSTEM_CORE_SOURCE}/libutils/VectorImpl.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/zipalign-shim/ZipArchiveInflate.cpp"
)
target_include_directories(fileforge_zipalign PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/zipalign-shim/include"
  "${FILEFORGE_AOSP_BUILD_SOURCE}/tools/zipalign"
  "${FILEFORGE_AOSP_BUILD_SOURCE}/tools/zipalign/include"
  "${FILEFORGE_SYSTEM_CORE_SOURCE}/libutils/include"
  "${FILEFORGE_SYSTEM_CORE_SOURCE}/libcutils/include"
  "${FILEFORGE_LIBZIPARCHIVE_SOURCE}/include"
  "${FILEFORGE_ZOPFLI_SOURCE}/src"
)
target_compile_definitions(fileforge_zipalign PRIVATE _FILE_OFFSET_BITS=64)
target_compile_options(fileforge_zipalign PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(fileforge_zipalign PRIVATE libzopfli z log m)
set_target_properties(fileforge_zipalign PROPERTIES OUTPUT_NAME fileforge_zipalign)
