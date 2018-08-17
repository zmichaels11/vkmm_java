package demo.vkmm;

public enum MemoryType {
    FREE,
    IMAGE,
    BUFFER,
    UNKNOWN;

    public boolean conflicts(MemoryType neighbor) {
        if (this == UNKNOWN || neighbor == UNKNOWN) {
            return true;
        } else if (this == neighbor) {
            return false;
        } else {
            return this != FREE && neighbor != FREE;
        }
    }
}
