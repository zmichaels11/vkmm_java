package demo.vkmm;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class LinearMemoryAllocator implements MemoryAllocator {
    private final WeakReference<VkDevice> device;
    private final long size;
    private final long handle;
    private final long pageSize;
    private final int typeIndex;
    private long pointer;
    private MemoryType lastType = MemoryType.FREE;
    private ByteBuffer address;
    private int mapCount;

    public LinearMemoryAllocator(final VkDevice device, final VkMemoryAllocateInfo memoryAI) {
        this.device = new WeakReference<>(device);
        this.size = memoryAI.allocationSize();

        try (var mem = MemoryStack.stackPush()) {
            final var pPhysicalDeviceProps = VkPhysicalDeviceProperties.callocStack(mem);

            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), pPhysicalDeviceProps);

            this.pageSize = pPhysicalDeviceProps.limits().bufferImageGranularity();
            this.typeIndex = memoryAI.memoryTypeIndex();

            final var pHandle = mem.callocLong(1);
            final int err = VK10.vkAllocateMemory(device, memoryAI, null, pHandle);

            assert VK10.VK_SUCCESS == err;

            this.handle = pHandle.get();
        }
    }

    @Override
    public MemoryBlock malloc(MemoryType type, VkMemoryRequirements pMemReqs) {
        long alignedSize = MemoryAllocator.alignUp(pMemReqs.size(), pMemReqs.alignment());

        if (this.lastType.conflicts(type)) {
            alignedSize = MemoryAllocator.alignUp(alignedSize, this.pageSize);
        }

        if (this.pageSize + alignedSize > this.pointer + this.size) {
            throw new OutOfMemoryError();
        }

        final var out = new LinearMemoryBlock(this.pointer, alignedSize);

        this.lastType = type;
        this.pointer += alignedSize;

        return out;
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
        return this.pointer == 0L;
    }

    private ByteBuffer map() {
        if (this.address == null) {
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
        if (--this.mapCount == 0) {
            this.address = null;
            VK10.vkUnmapMemory(this.getDevice(), this.handle);
        }
    }

    private final class LinearMemoryBlock implements MemoryBlock {
        private final long offset;
        private final long size;

        private LinearMemoryBlock(final long offset, final long size) {
            this.offset = offset;
            this.size = size;
        }

        @Override
        public long getHandle() {
            return LinearMemoryAllocator.this.handle;
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
            final var superBlock = LinearMemoryAllocator.this.map();

            return MemoryUtil.memSlice(superBlock, (int) this.offset, (int) this.size);
        }

        @Override
        public void unmap() {
            LinearMemoryAllocator.this.unmap();
        }

        @Override
        public void free() {
        }

        @Override
        public VkDevice getDevice() {
            return LinearMemoryAllocator.this.getDevice();
        }
    }
}
