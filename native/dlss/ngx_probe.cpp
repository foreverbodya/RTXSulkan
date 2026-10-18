// SPDX-License-Identifier: MIT
// Original Sulkan cinematic integration code. NVIDIA SDK is an external build input.
#include <jni.h>
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <mutex>
#include <sstream>
#include <string>
#include <cstdio>
#include <stdexcept>
#include "ngx_common.h"

namespace sulkan_ngx {
std::mutex calls;
const char* const projectId = "e26aa095-12b3-4f52-8ca1-6a9ea7a15e03";
std::mutex logMutex;

struct NgxLifetime {
    VkDevice device;
    bool initialized = false;
    NVSDK_NGX_Parameter* parameters = nullptr;
    ~NgxLifetime() {
        // Covers allocation/serialization/JNI exceptions without leaking SDK state.
        if (parameters) NVSDK_NGX_VULKAN_DestroyParameters(parameters);
        if (initialized) NVSDK_NGX_VULKAN_Shutdown1(device);
    }
};

void NVSDK_CONV logMessage(const char* message, NVSDK_NGX_Logging_Level level, NVSDK_NGX_Feature feature) {
    std::lock_guard<std::mutex> lock(logMutex);
    std::fprintf(stderr, "SULKAN_NGX_LOG level=%d feature=%d %s\n", int(level), int(feature), message ? message : "");
}

std::wstring wideString(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("Missing native probe path");
    const jchar* data = env->GetStringChars(value, nullptr);
    if (!data) throw std::runtime_error("Cannot read native probe path");
    struct ReleaseChars {
        JNIEnv* env;
        jstring value;
        const jchar* data;
        ~ReleaseChars() { env->ReleaseStringChars(value, data); }
    } release{env, value, data};
    static_assert(sizeof(wchar_t) == sizeof(jchar), "Windows JNI path encoding must be UTF-16");
    return std::wstring(reinterpret_cast<const wchar_t*>(data), env->GetStringLength(value));
}

void extensionArray(std::ostringstream& out, uint32_t count, const VkExtensionProperties* values) {
    if (count && !values) throw std::runtime_error("NGX returned an invalid extension list");
    out << '[';
    for (uint32_t i = 0; i < count; ++i) {
        if (i) out << ',';
        // Vulkan extension names contain identifiers only.
        out << '"' << values[i].extensionName << '"';
    }
    out << ']';
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sulkan_shaders_diagnostics_DlssConformance_probe(JNIEnv* env, jclass,
        jlong instanceAddress, jlong physicalAddress, jlong deviceAddress,
        jlong getInstanceProcAddress, jlong getDeviceProcAddress,
        jstring dataDirectory, jstring runtimeDirectory) {
    try {
        using namespace sulkan_ngx;
        std::lock_guard<std::mutex> serialized(calls);
        if (hasLiveContexts()) throw std::runtime_error("Capability probe cannot replace an active NGX session");
        if (!instanceAddress || !physicalAddress || !deviceAddress || !getInstanceProcAddress || !getDeviceProcAddress)
            throw std::invalid_argument("NGX requires Minecraft's live Vulkan handles and entry points");
        const auto instance = reinterpret_cast<VkInstance>(instanceAddress);
        const auto physical = reinterpret_cast<VkPhysicalDevice>(physicalAddress);
        const auto device = reinterpret_cast<VkDevice>(deviceAddress);
        NgxLifetime lifetime{device};
        const auto getInstanceProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(getInstanceProcAddress);
        const auto getDeviceProc = reinterpret_cast<PFN_vkGetDeviceProcAddr>(getDeviceProcAddress);
        const auto dataPath = wideString(env, dataDirectory);
        const auto runtimePath = wideString(env, runtimeDirectory);
        const wchar_t* paths[] = { runtimePath.c_str() };
        NVSDK_NGX_FeatureCommonInfo common{};
        common.PathListInfo.Path = paths;
        common.PathListInfo.Length = 1;
        common.LoggingInfo.LoggingCallback = logMessage;
        common.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_VERBOSE;
        common.LoggingInfo.DisableOtherLoggingSinks = false;
        NVSDK_NGX_FeatureDiscoveryInfo discovery{};
        discovery.SDKVersion = NVSDK_NGX_Version_API;
        discovery.FeatureID = NVSDK_NGX_Feature_SuperSampling;
        discovery.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
        discovery.Identifier.v.ProjectDesc.ProjectId = projectId;
        discovery.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
        discovery.Identifier.v.ProjectDesc.EngineVersion = "sulkan-cinematic.0";
        discovery.ApplicationDataPath = dataPath.c_str();
        discovery.FeatureInfo = &common;
        uint32_t instanceCount = 0, deviceCount = 0;
        VkExtensionProperties *instanceExtensions = nullptr, *deviceExtensions = nullptr;
        const auto instanceResult = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&discovery, &instanceCount, &instanceExtensions);
        const auto deviceResult = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(instance, physical, &discovery, &deviceCount, &deviceExtensions);
        NVSDK_NGX_FeatureRequirement requirement{};
        const auto requirementResult = NVSDK_NGX_VULKAN_GetFeatureRequirements(instance, physical, &discovery, &requirement);
        std::ostringstream out;
        out << "{\"sdkApi\":" << unsigned(NVSDK_NGX_Version_API)
            << ",\"instanceRequirementsResult\":" << unsigned(instanceResult)
            << ",\"deviceRequirementsResult\":" << unsigned(deviceResult)
            << ",\"featureRequirementsResult\":" << unsigned(requirementResult)
            << ",\"featureSupportedFlags\":" << unsigned(requirement.FeatureSupported)
            << ",\"instanceExtensions\":";
        extensionArray(out, NVSDK_NGX_SUCCEED(instanceResult) ? instanceCount : 0, instanceExtensions);
        out << ",\"deviceExtensions\":";
        extensionArray(out, NVSDK_NGX_SUCCEED(deviceResult) ? deviceCount : 0, deviceExtensions);
        const auto initResult = NVSDK_NGX_VULKAN_Init_with_ProjectID(projectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM,
            "sulkan-cinematic.0", dataPath.c_str(), instance, physical, device, getInstanceProc, getDeviceProc, &common);
        out << ",\"initResult\":" << unsigned(initResult);
        if (NVSDK_NGX_SUCCEED(initResult)) {
            lifetime.initialized = true;
            NVSDK_NGX_Parameter* parameters = nullptr;
            const auto parametersResult = NVSDK_NGX_VULKAN_GetCapabilityParameters(&parameters);
            out << ",\"capabilityResult\":" << unsigned(parametersResult);
            if (NVSDK_NGX_SUCCEED(parametersResult) && parameters) {
                lifetime.parameters = parameters;
                int available = -1, needsDriver = -1;
                const auto availabilityResult = parameters->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &available);
                const auto driverResult = parameters->Get(NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDriver);
                unsigned optimalWidth = 0, optimalHeight = 0, maxWidth = 0, maxHeight = 0, minWidth = 0, minHeight = 0;
                float sharpness = 0;
                const auto optimalResult = NGX_DLSS_GET_OPTIMAL_SETTINGS(parameters, 2560, 1440,
                    NVSDK_NGX_PerfQuality_Value_MaxQuality, &optimalWidth, &optimalHeight,
                    &maxWidth, &maxHeight, &minWidth, &minHeight, &sharpness);
                out << ",\"availabilityResult\":" << unsigned(availabilityResult) << ",\"available\":" << available
                    << ",\"driverResult\":" << unsigned(driverResult) << ",\"needsUpdatedDriver\":" << needsDriver
                    << ",\"optimalResult\":" << unsigned(optimalResult) << ",\"qualityWidth\":" << optimalWidth
                    << ",\"qualityHeight\":" << optimalHeight;
                out << ",\"destroyParametersResult\":" << unsigned(NVSDK_NGX_VULKAN_DestroyParameters(parameters));
                lifetime.parameters = nullptr;
            }
            out << ",\"shutdownResult\":" << unsigned(NVSDK_NGX_VULKAN_Shutdown1(device));
            lifetime.initialized = false;
        }
        out << '}';
        return env->NewStringUTF(out.str().c_str());
    } catch (const std::exception& failure) {
        if (!env->ExceptionCheck()) {
            jclass exceptionType = env->FindClass("java/lang/IllegalStateException");
            if (exceptionType) env->ThrowNew(exceptionType, failure.what());
        }
        return nullptr;
    }
}
