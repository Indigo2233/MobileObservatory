cmake_minimum_required(VERSION 3.18)
project(camera_jni)

# --- ToupTek ---
add_library(toupcam SHARED IMPORTED)
set_target_properties(toupcam PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtoupcam.so
)

add_library(toupcam_jni SHARED toupcam_jni.cpp)
target_include_directories(toupcam_jni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(toupcam_jni toupcam log)

# --- QHYCCD (arm64-v8a only for now) ---
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(qhyccd STATIC IMPORTED)
    set_target_properties(qhyccd PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libqhyccd.a
    )

    add_library(qhyccd_jni SHARED qhyccd_jni.cpp)
    target_include_directories(qhyccd_jni PRIVATE ${CMAKE_SOURCE_DIR})
    target_link_options(qhyccd_jni PRIVATE -nostdlib++)
    target_link_libraries(qhyccd_jni
        qhyccd
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libusb1.0.so
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libc++_shared.so
        log
    )
endif()

# USB fd helper used by ZWO (and optionally other USB open paths).
add_library(usb_helper_jni SHARED usb_helper_jni.cpp)
target_link_libraries(usb_helper_jni log)
