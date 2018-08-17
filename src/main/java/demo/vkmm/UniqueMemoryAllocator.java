package demo.vkmm;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Objects;

public class UniqueMemoryAllocator implements MemoryAllocator {
    private final WeakReference<VkDevice> device;
    private final int typeIndex;

    public UniqueMemoryAllocator(final VkDevice device, final int typeIndex) {
        this.device = new WeakReference<>(device);
        this.typeIndex = typeIndex;
    }

    @Override
    public MemoryBlock malloc(MemoryType type, VkMemoryRequirements pMemReqs) {
        return null;
    }

    @Override
    public void free() {

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
        return true;
    }

    private final class UniqueMemoryBlock implements MemoryBlock {
        private final long handle;
        private final long size;
        private final MemoryType type;

        private UniqueMemoryBlock(final MemoryType type, final long size) {
            this.size = size;
            this.type = type;

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
    }
}
