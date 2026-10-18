import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Queries hardware support without creating a logical device or modifying Minecraft. */
public final class VulkanCapabilities {
    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed: VkResult=" + result);
        }
    }

    public static void main(String[] args) {
        VkInstance instance;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo application = VkApplicationInfo.calloc(stack)
                    .sType$Default().pApplicationName(stack.UTF8("Sulkan capability probe"))
                    .apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default().pApplicationInfo(application);
            PointerBuffer result = stack.mallocPointer(1);
            check(VK10.vkCreateInstance(createInfo, null, result), "vkCreateInstance");
            instance = new VkInstance(result.get(0), createInfo);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.ints(0);
            check(VK10.vkEnumeratePhysicalDevices(instance, count, null), "device count");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(VK10.vkEnumeratePhysicalDevices(instance, count, devices), "devices");
            for (int i = 0; i < devices.remaining(); i++) {
                report(new VkPhysicalDevice(devices.get(i), instance), stack);
            }
        } finally {
            VK10.vkDestroyInstance(instance, null);
        }
    }

    private static void report(VkPhysicalDevice device, MemoryStack stack) {
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceProperties(device, properties);
        IntBuffer count = stack.ints(0);
        check(VK10.vkEnumerateDeviceExtensionProperties(device, (java.nio.ByteBuffer) null, count, null), "extension count");
        Set<String> names = new HashSet<>();
        try (VkExtensionProperties.Buffer extensions = VkExtensionProperties.calloc(count.get(0))) {
            check(VK10.vkEnumerateDeviceExtensionProperties(device, (java.nio.ByteBuffer) null, count, extensions), "extensions");
            for (VkExtensionProperties extension : extensions) names.add(extension.extensionNameString());
        }

        VkPhysicalDeviceBufferDeviceAddressFeatures address = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default();
        VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceRayQueryFeaturesKHR query = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR pipeline = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        long chain = 0;
        if (names.contains("VK_KHR_ray_tracing_pipeline")) { pipeline.pNext(chain); chain = pipeline.address(); }
        if (names.contains("VK_KHR_ray_query")) { query.pNext(chain); chain = query.address(); }
        if (names.contains("VK_KHR_acceleration_structure")) { acceleration.pNext(chain); chain = acceleration.address(); }
        if (VK10.VK_VERSION_MAJOR(properties.apiVersion()) > 1 || VK10.VK_VERSION_MINOR(properties.apiVersion()) >= 2
                || names.contains("VK_KHR_buffer_device_address")) { address.pNext(chain); chain = address.address(); }
        features.pNext(chain);
        VK11.vkGetPhysicalDeviceFeatures2(device, features);

        System.out.println("device=" + properties.deviceNameString());
        System.out.println("limits.subPixelPrecisionBits=" + properties.limits().subPixelPrecisionBits());
        System.out.println("limits.subTexelPrecisionBits=" + properties.limits().subTexelPrecisionBits());
        System.out.println("api=" + VK10.VK_VERSION_MAJOR(properties.apiVersion()) + "."
                + VK10.VK_VERSION_MINOR(properties.apiVersion()) + "." + VK10.VK_VERSION_PATCH(properties.apiVersion()));
        System.out.println("supported.bufferDeviceAddress=" + address.bufferDeviceAddress());
        System.out.println("supported.accelerationStructure=" + acceleration.accelerationStructure());
        System.out.println("supported.rayQuery=" + query.rayQuery());
        System.out.println("supported.rayTracingPipeline=" + pipeline.rayTracingPipeline());
        System.out.println("supported.deferredHostOperations=" + names.contains("VK_KHR_deferred_host_operations"));
        System.out.println("minecraft.enabledFeatures=NOT_QUERIED");
        System.out.println("dlss.sdkSupport=NOT_QUERIED");
    }
}
