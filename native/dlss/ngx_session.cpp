// SPDX-License-Identifier: MIT
// Original adapter. SDK binaries and headers are external inputs.
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>
#include "ngx_common.h"
#include <map>
#include <memory>
#include <sstream>
#include <array>
#include <stdexcept>
#include <cmath>

namespace {
void require(NVSDK_NGX_Result result, const char* operation) {
    if (NVSDK_NGX_FAILED(result)) throw std::runtime_error(std::string(operation) + " NGX result=" + std::to_string(unsigned(result)));
}
void report(JNIEnv* env, const std::exception& failure) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, failure.what());
    }
}
struct Sdk {
    VkDevice device{};
    bool initialized = false;
    NVSDK_NGX_Parameter* capabilities = nullptr;
    std::wstring data, runtime;
    const wchar_t* path = nullptr;
    NVSDK_NGX_FeatureCommonInfo common{};
    unsigned destroyResult = 1, shutdownResult = 1;
    void shutdown() {
        if (capabilities) { destroyResult = unsigned(NVSDK_NGX_VULKAN_DestroyParameters(capabilities)); capabilities = nullptr; }
        if (initialized) { shutdownResult = unsigned(NVSDK_NGX_VULKAN_Shutdown1(device)); initialized = false; }
    }
    ~Sdk() { shutdown(); }
};
struct Context {
    std::shared_ptr<Sdk> sdk;
    NVSDK_NGX_Parameter* parameters = nullptr;
    NVSDK_NGX_Handle* feature = nullptr;
    int iw = 0, ih = 0, ow = 0, oh = 0;
    unsigned releaseResult = 1, destroyResult = 1;
    void close() {
        if (feature) { releaseResult = unsigned(NVSDK_NGX_VULKAN_ReleaseFeature(feature)); feature = nullptr; }
        if (parameters) { destroyResult = unsigned(NVSDK_NGX_VULKAN_DestroyParameters(parameters)); parameters = nullptr; }
    }
    ~Context() { close(); }
};
std::weak_ptr<Sdk> activeSdk;
std::map<jlong, std::unique_ptr<Context>> contexts;
jlong nextContext = 1;
Context& get(jlong id) {
    auto found = contexts.find(id);
    if (found == contexts.end()) throw std::invalid_argument("Invalid or retired NGX context");
    return *found->second;
}
}

bool sulkan_ngx::hasLiveContexts() { return !contexts.empty(); }

extern "C" JNIEXPORT jlong JNICALL Java_com_sulkan_shaders_platform_vulkan_dlss_DlssSession_openNative(
        JNIEnv* env, jclass, jlong instance, jlong physical, jlong device, jlong gipa, jlong gdpa, jstring data, jstring runtime) {
    try {
        std::lock_guard<std::mutex> lock(sulkan_ngx::calls);
        if (!instance || !physical || !device || !gipa || !gdpa) throw std::invalid_argument("Missing Vulkan handle");
        auto sdk = activeSdk.lock();
        auto dataPath = sulkan_ngx::wideString(env, data), runtimePath = sulkan_ngx::wideString(env, runtime);
        if (sdk && (sdk->device != reinterpret_cast<VkDevice>(device) || sdk->runtime != runtimePath))
            throw std::invalid_argument("An NGX session is already active on a different device/runtime");
        if (!sdk) {
            sdk = std::make_shared<Sdk>();
            sdk->device = reinterpret_cast<VkDevice>(device);
            sdk->data = std::move(dataPath); sdk->runtime = std::move(runtimePath);
            sdk->path = sdk->runtime.c_str();
            sdk->common.PathListInfo.Path = &sdk->path;
            sdk->common.PathListInfo.Length = 1;
            sdk->common.LoggingInfo.LoggingCallback = sulkan_ngx::logMessage;
            sdk->common.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_ON;
            require(NVSDK_NGX_VULKAN_Init_with_ProjectID(sulkan_ngx::projectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM,
                "sulkan-cinematic.0", sdk->data.c_str(), reinterpret_cast<VkInstance>(instance), reinterpret_cast<VkPhysicalDevice>(physical),
                sdk->device, reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa), reinterpret_cast<PFN_vkGetDeviceProcAddr>(gdpa), &sdk->common), "initialize");
            sdk->initialized = true;
            require(NVSDK_NGX_VULKAN_GetCapabilityParameters(&sdk->capabilities), "capabilities");
            if (!sdk->capabilities) throw std::runtime_error("NGX returned no capability map");
            int available = 0;
            require(sdk->capabilities->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &available), "availability");
            if (available != 1) throw std::runtime_error("DLSS is unavailable on the negotiated device");
            activeSdk = sdk;
        }
        auto context = std::make_unique<Context>();
        context->sdk = sdk;
        require(NVSDK_NGX_VULKAN_AllocateParameters(&context->parameters), "allocate parameters");
        const jlong id = nextContext++;
        contexts.emplace(id, std::move(context));
        return id;
    } catch (const std::exception& failure) { report(env, failure); return 0; }
}

extern "C" JNIEXPORT jintArray JNICALL Java_com_sulkan_shaders_platform_vulkan_dlss_DlssSession_qualityNative(
        JNIEnv* env, jclass, jlong id, jint width, jint height) {
    try {
        std::lock_guard<std::mutex> lock(sulkan_ngx::calls);
        if (width <= 0 || height <= 0) throw std::invalid_argument("Invalid output dimensions");
        auto& context = get(id);
        unsigned iw=0, ih=0, maxw=0, maxh=0, minw=0, minh=0; float sharpness=0;
        require(NGX_DLSS_GET_OPTIMAL_SETTINGS(context.sdk->capabilities, unsigned(width), unsigned(height),
            NVSDK_NGX_PerfQuality_Value_MaxQuality, &iw, &ih, &maxw, &maxh, &minw, &minh, &sharpness), "Quality dimensions");
        jint values[] = {jint(iw), jint(ih)};
        auto result = env->NewIntArray(2);
        if (result) env->SetIntArrayRegion(result, 0, 2, values);
        return result;
    } catch (const std::exception& failure) { report(env, failure); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_com_sulkan_shaders_platform_vulkan_dlss_DlssSession_createNative(
        JNIEnv* env, jclass, jlong id, jlong commands, jint iw, jint ih, jint ow, jint oh) {
    try {
        std::lock_guard<std::mutex> lock(sulkan_ngx::calls);
        auto& context = get(id);
        if (!commands || iw <= 0 || ih <= 0 || ow < iw || oh < ih || context.feature) throw std::invalid_argument("Invalid feature creation state");
        NVSDK_NGX_DLSS_Create_Params creation{};
        creation.Feature.InWidth = unsigned(iw); creation.Feature.InHeight = unsigned(ih);
        creation.Feature.InTargetWidth = unsigned(ow); creation.Feature.InTargetHeight = unsigned(oh);
        creation.Feature.InPerfQualityValue = NVSDK_NGX_PerfQuality_Value_MaxQuality;
        creation.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_IsHDR | NVSDK_NGX_DLSS_Feature_Flags_MVLowRes | NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
        require(NGX_VULKAN_CREATE_DLSS_EXT1(context.sdk->device, reinterpret_cast<VkCommandBuffer>(commands),
            1, 1, &context.feature, context.parameters, &creation), "create DLSS feature");
        if (!context.feature) throw std::runtime_error("Successful creation returned no feature");
        context.iw=iw; context.ih=ih; context.ow=ow; context.oh=oh;
    } catch (const std::exception& failure) { report(env, failure); }
}

extern "C" JNIEXPORT void JNICALL Java_com_sulkan_shaders_platform_vulkan_dlss_DlssSession_evaluateNative(
        JNIEnv* env, jclass, jlong id, jlong commands, jlongArray resources, jfloat jitterX, jfloat jitterY, jboolean reset) {
    try {
        std::lock_guard<std::mutex> lock(sulkan_ngx::calls);
        auto& context = get(id);
        if (!commands || !context.feature || !resources || env->GetArrayLength(resources) != 30
                || !std::isfinite(jitterX) || !std::isfinite(jitterY)) throw std::invalid_argument("Invalid DLSS evaluation state");
        std::array<jlong, 30> values{};
        env->GetLongArrayRegion(resources, 0, 30, values.data());
        if (env->ExceptionCheck()) return;
        std::array<NVSDK_NGX_Resource_VK, 5> images{};
        for (size_t i=0; i<5; ++i) {
            const auto* v = values.data() + i*6;
            if (!v[0] || !v[1] || v[3] <= 0 || v[4] <= 0 || v[5] != VK_IMAGE_ASPECT_COLOR_BIT) throw std::invalid_argument("Invalid NGX image descriptor");
            const int expectedW = i==3 ? 1 : i==4 ? context.ow : context.iw;
            const int expectedH = i==3 ? 1 : i==4 ? context.oh : context.ih;
            const VkFormat formats[] = {VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32_SFLOAT,VK_FORMAT_R16G16_SFLOAT,VK_FORMAT_R32_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT};
            if (v[3] != expectedW || v[4] != expectedH || v[2] != formats[i]) throw std::invalid_argument("NGX image format or extent mismatch");
            VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            images[i] = NVSDK_NGX_Create_ImageView_Resource_VK(VkImageView(v[1]), VkImage(v[0]), range,
                VkFormat(v[2]), unsigned(v[3]), unsigned(v[4]), i==4);
        }
        NVSDK_NGX_VK_DLSS_Eval_Params evaluation{};
        evaluation.Feature.pInColor = &images[0]; evaluation.pInDepth = &images[1];
        evaluation.pInMotionVectors = &images[2]; evaluation.pInExposureTexture = &images[3];
        evaluation.Feature.pInOutput = &images[4];
        evaluation.InRenderSubrectDimensions = {unsigned(context.iw),unsigned(context.ih)};
        evaluation.InJitterOffsetX = jitterX; evaluation.InJitterOffsetY = jitterY;
        evaluation.InMVScaleX = 1; evaluation.InMVScaleY = 1;
        evaluation.InReset = reset ? 1 : 0;
        evaluation.InPreExposure = 1; evaluation.InExposureScale = 1;
        require(NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(commands), context.feature, context.parameters, &evaluation), "evaluate DLSS");
    } catch (const std::exception& failure) { report(env, failure); }
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sulkan_shaders_platform_vulkan_dlss_DlssSession_closeNative(JNIEnv* env, jclass, jlong id) {
    try {
        std::lock_guard<std::mutex> lock(sulkan_ngx::calls);
        auto found = contexts.find(id);
        if (found == contexts.end()) throw std::invalid_argument("Invalid or already retired NGX context");
        auto context = std::move(found->second); contexts.erase(found);
        auto sdk = context->sdk;
        context->close();
        const auto release = context->releaseResult, destroy = context->destroyResult;
        context.reset();
        const bool last = sdk.use_count() == 1;
        if (last) sdk->shutdown();
        std::ostringstream out;
        out << "{\"releaseFeatureResult\":" << release << ",\"destroyParametersResult\":" << destroy
            << ",\"lastContext\":" << (last ? "true" : "false") << ",\"destroyCapabilitiesResult\":" << sdk->destroyResult
            << ",\"shutdownResult\":" << sdk->shutdownResult << ",\"liveContexts\":" << contexts.size() << '}';
        return env->NewStringUTF(out.str().c_str());
    } catch (const std::exception& failure) { report(env, failure); return nullptr; }
}
