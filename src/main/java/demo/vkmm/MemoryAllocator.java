package demo.vkmm;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryRequirements;

public interface MemoryAllocator {
    MemoryBlock malloc(MemoryType type, VkMemoryRequirements pMemReqs);

    void free();

    VkDevice getDevice();

    int getTypeIndex();

    boolean isEmpty();

    static long alignUp(long a, long b) {
        return (a + b - 1) / b * b;
    }
}
