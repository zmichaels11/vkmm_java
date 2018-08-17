package demo.vkmm;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reference MemoryManager
 */
public final class MemoryManager {
    private static final long LARGE_ALLOC_THRESHOLD = Long.getLong("MemoryManager.LARGE_ALLOC_THRESHOLD", 128L * 1024L * 1024L);
    private static final long SMALL_ALLOC_THRESHOLD = Long.getLong("MemoryManager.SMALL_ALLOC_THRESHOLD", 32L * 1024L);
    private static final long STANDARD_BUFFER_HEAP_SIZE = Long.getLong("MemoryManager.STANDARD_BUFFER_HEAP_SIZE", 128L * 1024L * 1024L);
    private static final long STANDARD_IMAGE_HEAP_SIZE = Long.getLong("MemoryManager.STANDARD_IMAGE_HEAP_SIZE", 256L * 1024L * 1024L);
    private static final long MINIMUM_BUFFER_SUBDIV_SIZE = Long.getLong("MemoryManager.MINIMUM_BUFFER_SUBDIV_SIZE", 4L * 1024L);
    private static final long MINIMUM_IMAGE_SUBDIV_SIZE = Long.getLong("MemoryManager.MINIMUM_IMAGE_SUBDIV_SIZE", 4L * 1024L);
    private static final List<SlabMemoryAllocator.SlabSizeInfo> SMALL_HEAP_SIZES = List.of(
            new SlabMemoryAllocator.SlabSizeInfo(4 * 1024L, 256),
            new SlabMemoryAllocator.SlabSizeInfo(8 * 1024L, 128),
            new SlabMemoryAllocator.SlabSizeInfo(16 * 1024L, 64),
            new SlabMemoryAllocator.SlabSizeInfo(32 * 1024L, 32));

    private final WeakReference<VkDevice> device;
    private final UniqueMemoryAllocator[] largeBufferHeaps;
    private final UniqueMemoryAllocator[] largeImageHeaps;
    private final List<SlabMemoryAllocator> smallBufferHeaps = new ArrayList<>();
    private final List<SlabMemoryAllocator> smallImageHeaps = new ArrayList<>();
    private final List<BuddyBlockMemoryAllocator> standardBufferHeaps = new ArrayList<>();
    private final List<BuddyBlockMemoryAllocator> standardImageHeaps = new ArrayList<>();
    private final VkPhysicalDeviceMemoryProperties pPhysicalDeviceMemoryProperties;

    public MemoryManager(final VkDevice device) {
        this.device = new WeakReference<>(device);
        this.pPhysicalDeviceMemoryProperties = VkPhysicalDeviceMemoryProperties.calloc();

        VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), pPhysicalDeviceMemoryProperties);

        this.largeBufferHeaps = new UniqueMemoryAllocator[pPhysicalDeviceMemoryProperties.memoryTypeCount()];
        this.largeImageHeaps = new UniqueMemoryAllocator[pPhysicalDeviceMemoryProperties.memoryTypeCount()];
    }

    public VkDevice getDevice() {
        return Objects.requireNonNull(this.device.get(), "Device was lost!");
    }

    public void garbageCollect() {
        Stream.of(this.smallBufferHeaps, this.smallImageHeaps, this.standardBufferHeaps, this.standardImageHeaps)
                .forEach(heap -> {
                    final var garbage = heap.stream()
                            .filter(MemoryAllocator::isEmpty)
                            .peek(MemoryAllocator::free)
                            .collect(Collectors.toList());

                    heap.removeAll(garbage);
                });
    }

    public void free() {
        Stream.of(this.largeBufferHeaps, this.largeImageHeaps)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
                .forEach(MemoryAllocator::free);

        Stream.of(this.smallBufferHeaps, this.smallImageHeaps, this.standardBufferHeaps, this.standardImageHeaps)
                .flatMap(List::stream)
                .forEach(MemoryAllocator::free);

        for (int i = 0; i < this.largeBufferHeaps.length; i++) {
            this.largeBufferHeaps[i] = null;
        }

        for (int i = 0; i < this.largeImageHeaps.length; i++) {
            this.largeImageHeaps[i] = null;
        }

        Stream.of(this.smallBufferHeaps, this.smallImageHeaps, this.standardBufferHeaps, this.standardImageHeaps)
                .forEach(List::clear);
    }

    private static <MemAllocT extends MemoryAllocator> MemoryBlock allocate(
            final MemoryType memType, final VkMemoryRequirements pMemReqs,
            final int index, final List<MemAllocT> allocator, final Supplier<MemAllocT> constructor) {

        final var selectedHeaps = allocator.stream()
                .filter(testHeap -> testHeap.getTypeIndex() == index)
                .collect(Collectors.toList());

        for (var selectedHeap : selectedHeaps) {
            try {
                return selectedHeap.malloc(memType, pMemReqs);
            } catch (OutOfMemoryError err) {
                // heap is too full; try next
            }
        }

        final var newHeap = constructor.get();

        allocator.add(newHeap);

        // if this throws OOM; then the wrong heap was selected.
        return newHeap.malloc(memType, pMemReqs);
    }

    private int getMemoryTypeIndex(final int typeBits, final int requirementsMask) {
        for (int i = 0; i < this.pPhysicalDeviceMemoryProperties.memoryTypeCount(); i++) {
            if (0 != (typeBits & (1 << i))) {
                if (requirementsMask == (this.pPhysicalDeviceMemoryProperties.memoryTypes(i).propertyFlags() & requirementsMask)) {
                    return i;
                }
            }
        }

        throw new UnsupportedOperationException("No MemoryType exists with the required features!");
    }

    public MemoryBlock allocateImageMemory(final VkMemoryRequirements pMemReqs, final int properties) {
        final var index = this.getMemoryTypeIndex(pMemReqs.memoryTypeBits(), properties);
        final var size = pMemReqs.size();
        final var device = this.getDevice();

        if (size > LARGE_ALLOC_THRESHOLD) {
            if (this.largeImageHeaps[index] == null) {
                this.largeImageHeaps[index] = new UniqueMemoryAllocator(device, index);
            }

            final var out = this.largeImageHeaps[index].malloc(MemoryType.IMAGE, pMemReqs);

            return out;
        } else if (size <= SMALL_ALLOC_THRESHOLD) {
            return allocate(MemoryType.IMAGE, pMemReqs, index, this.smallImageHeaps, () -> new SlabMemoryAllocator(device, index, SMALL_HEAP_SIZES));
        } else {
            return allocate(MemoryType.IMAGE, pMemReqs, index, this.standardImageHeaps, () -> new BuddyBlockMemoryAllocator(device, index, MINIMUM_IMAGE_SUBDIV_SIZE, STANDARD_IMAGE_HEAP_SIZE));
        }
    }

    public MemoryBlock allocateBufferMemory(final VkMemoryRequirements pMemReqs, final int properties) {
        final var index = this.getMemoryTypeIndex(pMemReqs.memoryTypeBits(), properties);
        final var size = pMemReqs.size();
        final var device = this.getDevice();

        if (size > LARGE_ALLOC_THRESHOLD) {
            if (this.largeBufferHeaps[index] == null) {
                this.largeBufferHeaps[index] = new UniqueMemoryAllocator(device, index);
            }

            final var out = this.largeBufferHeaps[index].malloc(MemoryType.BUFFER, pMemReqs);

            return out;
        } else if (size <= SMALL_ALLOC_THRESHOLD) {
            return allocate(MemoryType.BUFFER, pMemReqs, index, this.smallBufferHeaps, () -> new SlabMemoryAllocator(device, index, SMALL_HEAP_SIZES));
        } else {
            return allocate(MemoryType.BUFFER, pMemReqs, index, this.standardBufferHeaps, () -> new BuddyBlockMemoryAllocator(device, index, MINIMUM_BUFFER_SUBDIV_SIZE, STANDARD_BUFFER_HEAP_SIZE));
        }
    }
}
