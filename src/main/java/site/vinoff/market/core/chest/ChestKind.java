package site.vinoff.market.core.chest;

/** What a bound container is. Only chests, and a double chest is one container with two blocks. */
public enum ChestKind {
    SINGLE(27),
    DOUBLE(54);

    private final int size;

    ChestKind(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    public static ChestKind ofSize(int size) {
        for (ChestKind kind : values()) {
            if (kind.size == size) {
                return kind;
            }
        }
        throw new IllegalArgumentException("No chest holds " + size + " slots");
    }
}
