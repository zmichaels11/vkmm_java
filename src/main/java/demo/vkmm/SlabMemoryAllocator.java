package demo.vkmm;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

public final class SlabMemoryAllocator implements MemoryAllocator {
    public static final class SlabSizeInfo {
        public final long size;
        public final int count;

        public SlabSizeInfo(final long size, final int count) {
            this.size = size;
            this.count = count;
        }

        public SlabSizeInfo() {
            this(0L, 0);
        }

        public SlabSizeInfo withSize(final long size) {
            return new SlabSizeInfo(size, count);
        }

        public SlabSizeInfo withCount(final int count) {
            return new SlabSizeInfo(size, count);
        }
    }

    private final WeakReference<VkDevice> device;
    private final long handle;
    private final long size;
    private final List<SlabList> lists;
    private final int typeIndex;
    private ByteBuffer address;
    private int mapCount;

    public SlabMemoryAllocator(final VkDevice device, final int typeIndex, final List<SlabSizeInfo> sizeInfos) {
        this.device = new WeakReference<>(device);
        this.size = sizeInfos.stream()
                .mapToLong(sizeInfo -> sizeInfo.size * sizeInfo.count)
                .sum();

        this.typeIndex = typeIndex;

        try (var mem = MemoryStack.stackPush()) {
            final var pMemoryAI = VkMemoryAllocateInfo.callocStack(mem)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .memoryTypeIndex(typeIndex)
                    .allocationSize(this.size);

            final var pHandle = mem.callocLong(1);
            final int err = VK10.vkAllocateMemory(device, pMemoryAI, null, pHandle);

            assert VK10.VK_SUCCESS == err;

            this.handle = pHandle.get();
        }

        this.lists = new ArrayList<>();

        long offset = 0L;
        for (var sizeInfo : sizeInfos) {
            final var baseOffset = offset;
            final var slabs = LongStream.range(0, sizeInfo.count)
                    .map(idx -> baseOffset + sizeInfo.size * idx)
                    .mapToObj(off -> new SlabMemoryBlock(off, sizeInfo.size))
                    .collect(Collectors.toList());

            this.lists.add(new SlabList(sizeInfo.size, slabs));
            offset += sizeInfo.count * sizeInfo.size;
        }
    }

    public SlabMemoryAllocator(final VkDevice device, final int typeIndex, final SlabSizeInfo... sizeInfos) {
        this(device, typeIndex, Arrays.asList(sizeInfos));
    }

    @Override
    public boolean isEmpty() {
        return this.lists.stream()
                .flatMap(list -> list.slabs.stream())
                .noneMatch(slab -> slab.type != MemoryType.FREE);
    }

    @Override
    public int getTypeIndex() {
        return this.typeIndex;
    }

    @Override
    public MemoryBlock malloc(final MemoryType type, final VkMemoryRequirements pMemReqs) {
        final var slabListIt = this.lists.listIterator();
        final var requiredSize = pMemReqs.size();
        final var alignment = pMemReqs.alignment();

        while (slabListIt.hasNext()) {
            final var slabList = slabListIt.next();

            if (slabList.size < requiredSize) {
                continue;
            }

            final var slabIt = slabList.slabs.listIterator();

            while (slabIt.hasNext()) {
                final var theSlab = slabIt.next();

                if (theSlab.type != MemoryType.FREE) {
                    continue;
                }

                theSlab.align(alignment);

                if (theSlab.getSize()  >= requiredSize) {
                    theSlab.type = type;

                    return theSlab;
                } else {
                    break;
                }
            }
        }

        throw new OutOfMemoryError();
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

    @Override
    public void free() {
        VK10.vkFreeMemory(this.getDevice(), this.handle, null);
    }

    @Override
    public VkDevice getDevice() {
        return Objects.requireNonNull(this.device.get(), "Device was lost!");
    }

    private final class SlabMemoryBlock implements MemoryBlock {
        private final long offset;
        private final long size;
        private long alignedOffset;
        private MemoryType type;

        private SlabMemoryBlock(final long offset, final long size) {
            this.offset = offset;
            this.alignedOffset = offset;
            this.size = size;
            this.type = MemoryType.FREE;
        }

        private void align(final long alignment) {
            this.alignedOffset = MemoryAllocator.alignUp(this.offset, alignment);
        }

        @Override
        public long getHandle() {
            return SlabMemoryAllocator.this.handle;
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
            final var superBlock = SlabMemoryAllocator.this.map();

            return MemoryUtil.memSlice(superBlock, (int) this.getOffset(), (int) this.getSize());
        }

        @Override
        public void unmap() {
            SlabMemoryAllocator.this.unmap();;
        }

        @Override
        public void free() {
            this.alignedOffset = this.offset;
            this.type = MemoryType.FREE;
        }

        @Override
        public VkDevice getDevice() {
            return SlabMemoryAllocator.this.getDevice();
        }
    }

    private final class SlabList {
        private final long size;
        private final List<SlabMemoryBlock> slabs;

        private SlabList(final long size, final List<SlabMemoryBlock> slabs) {
            this.size = size;
            this.slabs = List.copyOf(slabs);
        }
    }
}
