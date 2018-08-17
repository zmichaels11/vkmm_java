package demo.vkmm;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class UniqueMemoryAllocator implements MemoryAllocator {
    private final WeakReference<VkDevice> device;
    private final int typeIndex;
    private final Set<MemoryBlock> allocations = new HashSet<>();

    public UniqueMemoryAllocator(final VkDevice device, final int typeIndex) {
        this.device = new WeakReference<>(device);
        this.typeIndex = typeIndex;
    }

    @Override
    public MemoryBlock malloc(MemoryType type, VkMemoryRequirements pMemReqs) {
        final long alignedSize  = MemoryAllocator.alignUp(pMemReqs.size(), pMemReqs.alignment());
        final MemoryBlock out = new UniqueMemoryBlock(alignedSize);

        this.allocations.add(out);

        return out;
    }

    @Override
    public void free() {
        this.allocations.forEach(MemoryBlock::free);
        this.allocations.clear();
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
        return this.allocations.isEmpty();
    }

    private final class UniqueMemoryBlock implements MemoryBlock {
        private final long handle;
        private final long size;

        private UniqueMemoryBlock(final long size) {
            this.size = size;

            try (var mem = MemoryStack.stackPush()) {
                final var pHandle = mem.callocLong(1);
                final var pMemoryAI = VkMemoryAllocateInfo.callocStack(mem)
                        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .memoryTypeIndex(UniqueMemoryAllocator.this.typeIndex)
                        .allocationSize(size);

                final int err = VK10.vkAllocateMemory(this.getDevice(), pMemoryAI, null, pHandle);

                assert VK10.VK_SUCCESS == err;

                this.handle = pHandle.get();
            }
        }

        @Override
        public long getHandle() {
            return this.handle;
        }

        @Override
        public long getOffset() {
            return 0;
        }

        @Override
        public long getSize() {
            return this.size;
        }

        @Override
        public ByteBuffer map() {
            try (var mem  = MemoryStack.stackPush()) {
                final var ppData = mem.callocPointer(1);
                final int err = VK10.vkMapMemory(this.getDevice(), this.handle, 0, this.size, 0, ppData);

                assert VK10.VK_SUCCESS == err;

                return ppData.getByteBuffer(0, (int) this.size);
            }
        }

        @Override
        public void unmap() {
            VK10.vkUnmapMemory(this.getDevice(), this.handle);
        }

        @Override
        public void free() {
            VK10.vkFreeMemory(this.getDevice(), this.handle, null);
        }

        @Override
        public VkDevice getDevice() {
            return UniqueMemoryAllocator.this.getDevice();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UniqueMemoryBlock that = (UniqueMemoryBlock) o;
            return handle == that.handle;
        }

        @Override
        public int hashCode() {
            return Objects.hash(handle);
        }
    }
}
