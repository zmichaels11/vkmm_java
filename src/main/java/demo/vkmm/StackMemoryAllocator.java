package demo.vkmm;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Based on MemoryStack from LWJGL
 */
public class StackMemoryAllocator implements MemoryAllocator, AutoCloseable {
    private static final int MAX_STACK_FRAMES = 8;

    private final WeakReference<VkDevice> device;
    private final long size;
    private final long handle;
    private final long pageSize;
    private final long[] frames = new long[MAX_STACK_FRAMES];
    private final int typeIndex;
    private int frameIndex;
    private long pointer;
    private MemoryType lastType = MemoryType.FREE;
    private ByteBuffer address;
    private int mapCount = 0;

    public StackMemoryAllocator(final VkDevice device, final VkMemoryAllocateInfo allocateInfo) {
        this.device = new WeakReference<>(device);
        this.size = allocateInfo.allocationSize();
        this.typeIndex = allocateInfo.memoryTypeIndex();

        try (var mem = MemoryStack.stackPush()) {
            final var pPhysicalDeviceProperties = VkPhysicalDeviceProperties.callocStack(mem);

            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), pPhysicalDeviceProperties);

            this.pageSize = pPhysicalDeviceProperties.limits().bufferImageGranularity();

            final var pHandle = mem.callocLong(1);
            final int err = VK10.vkAllocateMemory(this.getDevice(), allocateInfo, null, pHandle);

            assert VK10.VK_SUCCESS == err;

            this.handle = pHandle.get();
        }

        this.pointer = this.size;
    }

    private long mallocImpl(final MemoryType type, final long size, final long alignment) {
        long newPointer = this.pointer - size;

        newPointer &= ~(alignment - 1);

        if (this.lastType.conflicts(type)) {
            newPointer &= ~(pageSize - 1);
        }

        this.pointer = newPointer;
        this.lastType = type;

        return newPointer;
    }

    @Override
    public void close() {
        pop();
    }

    public StackMemoryAllocator pop() {
        if (0 == this.frameIndex) {
            this.free();
            return null;
        }

        this.pointer = this.frames[--this.frameIndex];
        return this;
    }

    public StackMemoryAllocator push() {
        this.frames[this.frameIndex++] = this.pointer;

        return this;
    }

    @Override
    public MemoryBlock malloc(MemoryType type, VkMemoryRequirements pMemReqs) {
        final long size = pMemReqs.size();
        final long offset = mallocImpl(type, size, pMemReqs.alignment());

        return new StackMemoryBlock(offset, size);
    }

    @Override
    public void free() {
        VK10.vkFreeMemory(this.getDevice(), this.handle, null);
    }

    @Override
    public VkDevice getDevice() {
        return Objects.requireNonNull(this.device.get(), "Device was lost!");
    }

    @Override
    public int getTypeIndex() {
        return this.typeIndex;
    }

    @Override
    public boolean isEmpty() {
        return this.pointer == this.size;
    }

    private ByteBuffer map() {
        if (null == this.address) {
            try (var mem = MemoryStack.stackPush()) {
                final var ppData = mem.callocPointer(1);
                final int err = VK10.vkMapMemory(this.getDevice(), this.handle, 0L, this.size, 0, ppData);

                assert VK10.VK_SUCCESS == err;

                this.address = ppData.getByteBuffer(0, (int) this.size);
            }
        }

        this.mapCount++;

        return this.address;
    }

    private void unmap() {
        if (0 == --this.mapCount) {
            this.address = null;
            VK10.vkUnmapMemory(this.getDevice(), this.handle);
        }
    }

    private final class StackMemoryBlock implements MemoryBlock {
        private final long offset;
        private final long size;

        private StackMemoryBlock(final long offset, final long size) {
            this.offset = offset;
            this.size = size;
        }

        @Override
        public long getHandle() {
            return StackMemoryAllocator.this.handle;
        }

        @Override
        public long getOffset() {
            return this.offset;
        }

        @Override
        public long getSize() {
            return this.size;
        }

        @Override
        public ByteBuffer map() {
            final var superBlock = StackMemoryAllocator.this.map();

            return MemoryUtil.memSlice(superBlock, (int) this.offset, (int) this.size);
        }

        @Override
        public void unmap() {
            StackMemoryAllocator.this.unmap();
        }

        @Override
        public void free() {

        }

        @Override
        public VkDevice getDevice() {
            return StackMemoryAllocator.this.getDevice();
        }
    }
}
