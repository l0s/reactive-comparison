package sync;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * FIXME move to common utils
 *
 * @param <T>
 */
public class LockFactory<T> {

    private final Map<T, ReadWriteLock> map;
    private final Function<T, ReadWriteLock> lockSupplier;

    protected LockFactory(final Map<T,ReadWriteLock> map,
            final Function<T, ReadWriteLock> lockSupplier) {
        Objects.requireNonNull(map);
        Objects.requireNonNull(lockSupplier);
        this.map = map;
        this.lockSupplier = lockSupplier;
    }

    public LockFactory(final Function<T, ReadWriteLock> lockSupplier) {
        this(Collections.synchronizedMap(new WeakHashMap<>()), lockSupplier);
    }

    public LockFactory(final int initialCapacity, final float loadFactor,
            final Function<T, ReadWriteLock> lockSupplier) {
        this(Collections.synchronizedMap(new WeakHashMap<>(initialCapacity, loadFactor)), lockSupplier);
    }

    public LockFactory() {
        this(key -> new ReentrantReadWriteLock(true));
    }

    public ReadWriteLock getLock(final T key) {
        return getMap().computeIfAbsent(key, getLockSupplier());
    }

    protected Map<T, ReadWriteLock> getMap() {
        return map;
    }

    protected Function<T, ReadWriteLock> getLockSupplier() {
        return lockSupplier;
    }

}