package sync;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * <p>A factory for generating {@link ReadWriteLock ReadWriteLocks} based
 * on an "identifiable" object. If two objects are "equal" and two
 * threads hold a lock for the object at the same time, then the two
 * threads are guaranteed to have the same lock instance.</p>
 * 
 * <p>FIXME move to common utils</p>
 * 
 * <p>Inspired by XSync: https://github.com/antkorwin/xsync/</p>
 * 
 * @see https://dzone.com/articles/synchronized-by-the-value-of-the-object-in-java
 * @see https://github.com/antkorwin/xsync/
 * @param <T> a type with a well-defined equality contract
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
        // TODO see ConcurrentReferenceHashMap used by XSync:
        // https://github.com/antkorwin/xsync/blob/master/src/main/java/org/hibernate/validator/internal/util/ConcurrentReferenceHashMap.java
        this(Collections.synchronizedMap(new WeakHashMap<>()), lockSupplier);
    }

    public LockFactory(final int initialCapacity, final float loadFactor,
            final Function<T, ReadWriteLock> lockSupplier) {
        // TODO see ConcurrentReferenceHashMap used by XSync:
        // https://github.com/antkorwin/xsync/blob/master/src/main/java/org/hibernate/validator/internal/util/ConcurrentReferenceHashMap.java
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