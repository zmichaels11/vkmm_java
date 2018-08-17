# Vulkan Memory Managers (Java)
Implementation of multiple types of Memory Allocators and a generic MemoryManager.

## Buddy Block Allocator
Implements [Buddy Memory Allocation](https://en.wikipedia.org/wiki/Buddy_memory_allocation). Works by recursively subdividing into two blocks of at least __MIN_SUBDIV_SIZE__.
Each block has a _buddy block_. When A block and its buddy are freed, the parent is also considered freed.

## Linear Memory Allocator
Allocates memory sequentially. Allocations from this memory allocator cannot be freed. Instead, the entire Memory Allocator can be reset, which effectively frees all allocations.
Clearing the Linear Memory Allocator does not notify allocations that they are now undefined.

## Slab Memory Allocator
Pre-subdivides memory allocations into n-bins. Allocation works by selecting from the best-fit bin and marking that memory as used. This allocator is ideal for a small memory pool.

## Stack Memory Allocator
Allocates memory by growing downwards. Supports push and pop. Push remembers the current _frame index_, while pop resets the _frame_ to the previous _frame index_. A _pop_ effectively frees all memory allocated within a _stack frame_.

## Unique Memory Allocator
Allocates memory as unique calls to VkAllocateMemory. This is not ideal, since Vulkan limits the number of active Memory Allocations to as low as ~1000 (dependent on hardware).
This memory allocator is best used for small demos, large memory allocations, or allocations that persist through the entire lifespan of the application.

## MemoryManager
Implements multiple Memory Allocators and selects from them depending on heuristics configured at startup.

Default settings result in:
- Use UniqueMemoryAllocator when allocation is above 128MB
- Use BuddyBlockAllocator(s) when between 32KB and 128MB
- Use SlabAllocator(s) when less than or equal to 32KB.

Default SlabAllocator Slab Sizes:
- 256x 4KB allocations
- 128x 8KB allocations
- 64x 16KB allocations
* 32x 32KB allocations

Additional BuddyBlockAllocators and SlabAllocators will be constructed if all available allocators are full (for the given range).

