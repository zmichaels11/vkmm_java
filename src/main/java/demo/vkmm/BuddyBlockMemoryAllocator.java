package demo.vkmm;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class BuddyBlockMemoryAllocator implements MemoryAllocator {
    private final WeakReference<VkDevice> device;
    private final long size;
    private final long handle;
    private final long minSize;
    private final int typeIndex;
    private BuddyBlockMemoryBlock root;
    private ByteBuffer address;
    private int mapCount;

    public BuddyBlockMemoryAllocator(final VkDevice device, final int typeIndex, final long minSize, final long totalSize) {
        this.device = new WeakReference<>(device);
        this.size = totalSize;
        this.minSize = minSize;
        this.typeIndex = typeIndex;

        try (var mem = MemoryStack.stackPush()) {
            final var pMemoryAI = VkMemoryAllocateInfo.callocStack(mem)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .memoryTypeIndex(this.typeIndex)
                    .allocationSize(this.size);

            final var pHandle = mem.callocLong(1);
            final int err = VK10.vkAllocateMemory(device, pMemoryAI, null, pHandle);

            assert VK10.VK_SUCCESS == err;

            this.handle = pHandle.get();
        }

        this.root = new BuddyBlockMemoryBlock(0L, this.size);
    }

    @Override
    public MemoryBlock malloc(MemoryType type, VkMemoryRequirements pMemReqs) {
        final long size = pMemReqs.size();
        final long alignment = pMemReqs.alignment();

        this.root.reclaim();

        var alloc = this.root.sub(size, alignment);

        if (null == alloc) {
            throw new OutOfMemoryError();
        }

        alloc.type = type;
        alloc.align(alignment);

        return alloc;
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
        this.root.reclaim();

        return MemoryType.FREE == this.root.type;
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

    private final class BuddyBlockMemoryBlock implements MemoryBlock {
        private final long offset;
        private final long size;
        private MemoryType type;
        private long alignedOffset;
        private BuddyBlockMemoryBlock left, right;

        private BuddyBlockMemoryBlock(final long offset, final long size) {
            this.offset = offset;
            this.size = size;
            this.type = MemoryType.FREE;
            this.alignedOffset = offset;
            this.left = null;
            this.right = null;
        }

        private void reclaim() {
            if (null != this.left && null != this.right) {
                this.left.reclaim();
                this.right.reclaim();

                if (MemoryType.FREE == this.left.type && MemoryType.FREE == this.right.type) {
                    this.type = MemoryType.FREE;
                    this.left = null;
                    this.right = null;
                }
            }
        }

        private BuddyBlockMemoryBlock sub(final long size, final long alignment) {
            if (null != this.left) {
                final var out = this.left.sub(size, alignment);

                if (out != null) {
                    return out;
                }
            }

            if (null != this.right) {
                final var out = this.right.sub(size, alignment);

                if (out != null) {
                    return out;
                }
            }

            if (this.type != MemoryType.FREE) {
                return null;
            }

            this.align(alignment);

            if (this.getSize() < size) {
                return null;
            }

            final long halfSize = this.getSize() / 2;

            if (halfSize < size || halfSize < minSize) {
                // cant split; leave
                return this;
            }

            // try subdividing
            final var left = new BuddyBlockMemoryBlock(this.offset, halfSize);
            final var right = new BuddyBlockMemoryBlock(this.offset + halfSize, halfSize);

            left.align(alignment);
            right.align(alignment);

            if (left.getSize() >= size) {
                this.type = MemoryType.UNKNOWN;
                this.left = left;
                this.right = right;

                final var out = left.sub(size, alignment);

                if (out != null) {
                    return out;
                }
            }

            if (right.getSize() >= size) {
                this.type = MemoryType.UNKNOWN;
                this.left = left;
                this.right = right;

                final var out = right.sub(size, alignment);

                if (out != null) {
                    return out;
                }
            }

            return this;
        }

        private void align(final long alignment) {
            this.alignedOffset = MemoryAllocator.alignUp(this.offset, alignment);
        }

        @Override
        public long getHandle() {
            return BuddyBlockMemoryAllocator.this.handle;
        }

        @Override
        public long getOffset() {
            return this.alignedOffset;
        }

        @Override
        public long getSize() {
            return this.offset + this.size - this.alignedOffset;
        }

        @Override
        public ByteBuffer map() {
            final var superBlock = BuddyBlockMemoryAllocator.this.map();

            return MemoryUtil.memSlice(superBlock, (int) this.getOffset(), (int) this.getSize());
        }

        @Override
        public void unmap() {
            BuddyBlockMemoryAllocator.this.unmap();
        }

        @Override
        public void free() {
            this.type = MemoryType.FREE;
            this.left = null;
            this.right = null;
        }

        @Override
        public VkDevice getDevice() {
            return BuddyBlockMemoryAllocator.this.getDevice();
        }
    }
}
