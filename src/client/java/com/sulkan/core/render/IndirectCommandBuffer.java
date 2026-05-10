package com.sulkan.core.render;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.sulkan.core.stats.CoreStats;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class IndirectCommandBuffer implements Destroyable {
	public static final int DRAW_INDEXED_INDIRECT_STRIDE = 20;

	private static final int FRAME_BUFFER_COUNT = 4;
	private static final int INITIAL_CAPACITY_BYTES = 1024 * 1024;
	private static final int MAX_SINGLE_CACHED_COMMAND_BYTES = 1024 * 1024;
	private static final int MAX_CACHED_COMMAND_BYTES = 64 * 1024 * 1024;
	private static final long CACHE_UNUSED_FRAME_LIMIT = 600L;
	private static final FrameBuffer[] FRAME_BUFFERS = new FrameBuffer[FRAME_BUFFER_COUNT];
	private static final LinkedHashMap<CommandKey, CachedCommands> CACHED_COMMANDS = new LinkedHashMap<>(64, 0.75f, true);
	private static final ThreadLocal<ScratchCommands> SCRATCH_COMMANDS = ThreadLocal.withInitial(ScratchCommands::new);

	private static int frameIndex;
	private static long frameSerial;
	private static int cachedCommandBytes;

	private final VulkanDevice device;
	private final long vkBuffer;
	private final long allocation;
	private final long mappedPointer;
	private final int capacity;
	private int writeOffset;

	private IndirectCommandBuffer(VulkanDevice device, long vkBuffer, long allocation, long mappedPointer, int capacity) {
		this.device = device;
		this.vkBuffer = vkBuffer;
		this.allocation = allocation;
		this.mappedPointer = mappedPointer;
		this.capacity = capacity;
	}

	public static void beginFrame() {
		frameSerial++;
		frameIndex = (frameIndex + 1) % FRAME_BUFFER_COUNT;
		FrameBuffer frameBuffer = FRAME_BUFFERS[frameIndex];
		if (frameBuffer != null) {
			frameBuffer.buffer.reset();
		}
	}

	public static ByteBuffer scratchCommands(int requiredBytes) {
		return SCRATCH_COMMANDS.get().buffer(requiredBytes);
	}

	public static CommandUpload uploadCached(VulkanDevice device, ByteBuffer commands, Consumer<Destroyable> garbageQueue) {
		int commandBytes = commands.remaining();
		if (commandBytes <= 0) {
			throw new IllegalArgumentException("Cannot upload an empty indirect command buffer");
		}
		if (commandBytes > MAX_SINGLE_CACHED_COMMAND_BYTES) {
			CommandSlice slice = upload(device, commands, garbageQueue);
			return new CommandUpload(slice, false, commandBytes);
		}

		CommandKey key = CommandKey.create(device, commands);
		synchronized (CACHED_COMMANDS) {
			pruneCachedCommands(device, garbageQueue);
			CachedCommands cached = CACHED_COMMANDS.get(key);
			if (cached != null) {
				cached.lastUsedFrame = frameSerial;
				CoreStats.recordIndirectCacheHit(commandBytes);
				return new CommandUpload(cached.slice, true, commandBytes);
			}

			IndirectCommandBuffer cachedBuffer = create(device, nextPowerOfTwo(commandBytes));
			CommandSlice slice = cachedBuffer.upload(commands.duplicate());
			CACHED_COMMANDS.put(key, new CachedCommands(cachedBuffer, slice, commandBytes, frameSerial));
			cachedCommandBytes += commandBytes;
			CoreStats.recordIndirectCacheMiss(commandBytes);
			pruneCachedCommands(device, garbageQueue);
			return new CommandUpload(slice, false, commandBytes);
		}
	}

	public static CacheStats cacheStats() {
		synchronized (CACHED_COMMANDS) {
			return new CacheStats(CACHED_COMMANDS.size(), cachedCommandBytes);
		}
	}

	public static void closeAll() {
		SCRATCH_COMMANDS.get().close();
		SCRATCH_COMMANDS.remove();
		for (int i = 0; i < FRAME_BUFFERS.length; i++) {
			FrameBuffer frameBuffer = FRAME_BUFFERS[i];
			if (frameBuffer != null) {
				frameBuffer.buffer.destroy();
				FRAME_BUFFERS[i] = null;
			}
		}
		synchronized (CACHED_COMMANDS) {
			for (CachedCommands cached : CACHED_COMMANDS.values()) {
				cached.buffer.destroy();
			}
			CACHED_COMMANDS.clear();
			cachedCommandBytes = 0;
		}
	}

	private static CommandSlice upload(VulkanDevice device, ByteBuffer commands, Consumer<Destroyable> garbageQueue) {
		int commandBytes = commands.remaining();
		FrameBuffer frameBuffer = ensureFrameBuffer(device, commandBytes, garbageQueue);
		return frameBuffer.buffer.upload(commands);
	}

	private static FrameBuffer ensureFrameBuffer(VulkanDevice device, int commandBytes, Consumer<Destroyable> garbageQueue) {
		FrameBuffer frameBuffer = FRAME_BUFFERS[frameIndex];
		int requiredCapacity = Math.max(INITIAL_CAPACITY_BYTES, nextPowerOfTwo(commandBytes));
		if (frameBuffer == null || frameBuffer.buffer.device != device || frameBuffer.buffer.remainingBytes() < commandBytes) {
			if (frameBuffer != null) {
				garbageQueue.accept(frameBuffer.buffer);
			}
			int capacity = frameBuffer == null || frameBuffer.buffer.device != device
					? requiredCapacity
					: Math.max(nextPowerOfTwo(frameBuffer.buffer.capacity + commandBytes), requiredCapacity);
			frameBuffer = new FrameBuffer(create(device, capacity));
			FRAME_BUFFERS[frameIndex] = frameBuffer;
		}
		return frameBuffer;
	}

	private static void pruneCachedCommands(VulkanDevice device, Consumer<Destroyable> garbageQueue) {
		Iterator<Map.Entry<CommandKey, CachedCommands>> iterator = CACHED_COMMANDS.entrySet().iterator();
		while (iterator.hasNext()) {
			CachedCommands cached = iterator.next().getValue();
			if (cached.buffer.device != device || frameSerial - cached.lastUsedFrame > CACHE_UNUSED_FRAME_LIMIT) {
				cachedCommandBytes -= cached.bytes;
				garbageQueue.accept(cached.buffer);
				iterator.remove();
			}
		}

		iterator = CACHED_COMMANDS.entrySet().iterator();
		while (cachedCommandBytes > MAX_CACHED_COMMAND_BYTES && iterator.hasNext()) {
			CachedCommands cached = iterator.next().getValue();
			cachedCommandBytes -= cached.bytes;
			garbageQueue.accept(cached.buffer);
			iterator.remove();
		}
	}

	private static IndirectCommandBuffer create(VulkanDevice device, int capacity) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
			bufferInfo.sType$Default();
			bufferInfo.size(capacity);
			bufferInfo.usage(VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
			bufferInfo.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);

			VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
			allocationInfo.usage(Vma.VMA_MEMORY_USAGE_AUTO);
			allocationInfo.requiredFlags(VK12.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK12.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			allocationInfo.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);

			var bufferPtr = stack.callocLong(1);
			PointerBuffer allocationPtr = stack.callocPointer(1);
			int result = Vma.vmaCreateBuffer(device.vma(), bufferInfo, allocationInfo, bufferPtr, allocationPtr, null);
			if (result != VK12.VK_SUCCESS) {
				throw new IllegalStateException("Failed to allocate Sulkan indirect draw buffer: " + result);
			}

			PointerBuffer mapped = stack.callocPointer(1);
			result = Vma.vmaMapMemory(device.vma(), allocationPtr.get(0), mapped);
			if (result != VK12.VK_SUCCESS) {
				Vma.vmaDestroyBuffer(device.vma(), bufferPtr.get(0), allocationPtr.get(0));
				throw new IllegalStateException("Failed to map Sulkan indirect draw buffer: " + result);
			}

			return new IndirectCommandBuffer(device, bufferPtr.get(0), allocationPtr.get(0), mapped.get(0), capacity);
		}
	}

	private static int nextPowerOfTwo(int value) {
		int bounded = Math.max(1, value);
		if (bounded >= 1 << 30) {
			return bounded;
		}
		return 1 << (32 - Integer.numberOfLeadingZeros(bounded - 1));
	}

	private int remainingBytes() {
		return this.capacity - this.writeOffset;
	}

	private void reset() {
		this.writeOffset = 0;
	}

	private CommandSlice upload(ByteBuffer commands) {
		int offset = this.writeOffset;
		int bytes = commands.remaining();
		MemoryUtil.memCopy(MemoryUtil.memAddress(commands), this.mappedPointer + offset, bytes);
		this.writeOffset += bytes;
		return new CommandSlice(this.vkBuffer, offset);
	}

	@Override
	public void destroy() {
		Vma.vmaUnmapMemory(this.device.vma(), this.allocation);
		Vma.vmaDestroyBuffer(this.device.vma(), this.vkBuffer, this.allocation);
	}

	public record CommandSlice(long vkBuffer, long offset) {
	}

	public record CommandUpload(CommandSlice slice, boolean cacheHit, int bytes) {
	}

	public record CacheStats(int entries, int bytes) {
	}

	private record FrameBuffer(IndirectCommandBuffer buffer) {
	}

	private static final class CachedCommands {
		private final IndirectCommandBuffer buffer;
		private final CommandSlice slice;
		private final int bytes;
		private long lastUsedFrame;

		private CachedCommands(IndirectCommandBuffer buffer, CommandSlice slice, int bytes, long lastUsedFrame) {
			this.buffer = buffer;
			this.slice = slice;
			this.bytes = bytes;
			this.lastUsedFrame = lastUsedFrame;
		}
	}

	private record CommandKey(VulkanDevice device, int bytes, long hash, long firstBytes, long lastBytes) {
		private static CommandKey create(VulkanDevice device, ByteBuffer commands) {
			int position = commands.position();
			int limit = commands.limit();
			int bytes = limit - position;
			long hash = 0xcbf29ce484222325L;
			for (int i = position; i < limit; i++) {
				hash ^= commands.get(i) & 0xFFL;
				hash *= 0x100000001b3L;
			}
			long firstBytes = readKeyBytes(commands, position, Math.min(8, bytes));
			long lastBytes = readKeyBytes(commands, Math.max(position, limit - 8), Math.min(8, bytes));
			return new CommandKey(device, bytes, hash, firstBytes, lastBytes);
		}

		private static long readKeyBytes(ByteBuffer commands, int start, int count) {
			long value = 0L;
			for (int i = 0; i < count; i++) {
				value |= (commands.get(start + i) & 0xFFL) << (i * 8);
			}
			return value;
		}
	}

	private static final class ScratchCommands implements AutoCloseable {
		private ByteBuffer buffer;

		private ByteBuffer buffer(int requiredBytes) {
			if (this.buffer == null || this.buffer.capacity() < requiredBytes) {
				this.close();
				this.buffer = MemoryUtil.memAlloc(nextPowerOfTwo(requiredBytes));
			}
			this.buffer.clear();
			return this.buffer;
		}

		@Override
		public void close() {
			if (this.buffer != null) {
				MemoryUtil.memFree(this.buffer);
				this.buffer = null;
			}
		}
	}
}
