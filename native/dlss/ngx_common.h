// SPDX-License-Identifier: MIT
#pragma once
#include <jni.h>
#include <nvsdk_ngx_defs.h>
#include <mutex>
#include <string>
namespace sulkan_ngx {
extern std::mutex calls;
extern const char* const projectId;
// Caller must hold calls.
bool hasLiveContexts();
std::wstring wideString(JNIEnv* env, jstring value);
void NVSDK_CONV logMessage(const char*, NVSDK_NGX_Logging_Level, NVSDK_NGX_Feature);
}
