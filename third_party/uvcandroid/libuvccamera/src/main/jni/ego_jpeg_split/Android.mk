LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := ego_jpeg_split
LOCAL_SRC_FILES := ego_jpeg_split.c jpeg_split_core.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../libjpeg-turbo $(LOCAL_PATH)/../libjpeg-turbo/include
LOCAL_SHARED_LIBRARIES := jpeg-turbo212
LOCAL_CFLAGS := -std=c99 -Wall -Wextra
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
