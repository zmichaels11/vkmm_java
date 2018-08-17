package demo.vkmm;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;

public interface MemoryBlock {
    long getHandle();

    long getOffset();

    long getSize();

    ByteBuffer map();

    void unmap();

    void free();

    VkDevice getDevice();

    default int bindToImage(long image) {
        return VK10.vkBindImageMemory(this.getDevice(), image, this.getHandle(), this.getOffset());
    }

    default int bindToBuffer(long buffer) {
        return VK10.vkBindBufferMemory(this.getDevice(), buffer, this.getHandle(), this.getOffset());
    }
}
