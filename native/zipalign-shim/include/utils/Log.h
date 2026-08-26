#pragma once

#include <android/log.h>
#include <stdlib.h>

#define FILEFORGE_LOG(priority, ...) \
  __android_log_print(priority, "FileForgeZipalign", __VA_ARGS__)
#define ALOGV(...) FILEFORGE_LOG(ANDROID_LOG_VERBOSE, __VA_ARGS__)
#define ALOGD(...) FILEFORGE_LOG(ANDROID_LOG_DEBUG, __VA_ARGS__)
#define ALOGI(...) FILEFORGE_LOG(ANDROID_LOG_INFO, __VA_ARGS__)
#define ALOGW(...) FILEFORGE_LOG(ANDROID_LOG_WARN, __VA_ARGS__)
#define ALOGE(...) FILEFORGE_LOG(ANDROID_LOG_ERROR, __VA_ARGS__)
#define ALOGW_IF(condition, ...) do { if (condition) ALOGW(__VA_ARGS__); } while (0)
#define FILEFORGE_ABORT_IF(condition) \
  do { \
    if (condition) { \
      __android_log_print(ANDROID_LOG_FATAL, "FileForgeZipalign", \
                          "fatal condition: %s", #condition); \
      abort(); \
    } \
  } while (0)
#define ALOG_ASSERT(condition, ...) FILEFORGE_ABORT_IF(!(condition))
#define LOG_ALWAYS_FATAL_IF(condition, ...) FILEFORGE_ABORT_IF(condition)
#define LOG_FATAL_IF(condition, ...) FILEFORGE_ABORT_IF(condition)
