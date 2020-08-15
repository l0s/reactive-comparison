/**
 * Copyright © 2020 Carlos Macasaet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sync;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

/**
 * <p>A factory for generating {@link ReadWriteLock ReadWriteLocks} based
 * on an "identifiable" object. If two objects are "equal" and two
 * threads hold a lock for the object at the same time, then the two
 * threads are guaranteed to have the same lock instance.</p>
 * 
 * <p>Inspired by XSync: https://github.com/antkorwin/xsync/</p>
 * 
 * @see https://dzone.com/articles/synchronized-by-the-value-of-the-object-in-java
 * @see https://github.com/antkorwin/xsync/
 * @param <T> a type with a well-defined equality contract
 */
public class LockFactory<T> {

    private final Map<T, StampedLock> map;
    private final Function<T, StampedLock> lockSupplier;

    protected LockFactory(final Map<T, StampedLock> map,
            final Function<T, StampedLock> lockSupplier) {
        Objects.requireNonNull(map);
        Objects.requireNonNull(lockSupplier);
        this.map = map;
        this.lockSupplier = lockSupplier;
    }

    public LockFactory(final Function<T, StampedLock> lockSupplier) {
        // TODO see ConcurrentReferenceHashMap used by XSync:
        // https://github.com/antkorwin/xsync/blob/master/src/main/java/org/hibernate/validator/internal/util/ConcurrentReferenceHashMap.java
        this(Collections.synchronizedMap(new WeakHashMap<>()), lockSupplier);
    }

    public LockFactory(final int initialCapacity, final float loadFactor,
            final Function<T, StampedLock> lockSupplier) {
        // TODO see ConcurrentReferenceHashMap used by XSync:
        // https://github.com/antkorwin/xsync/blob/master/src/main/java/org/hibernate/validator/internal/util/ConcurrentReferenceHashMap.java
        this(Collections.synchronizedMap(new WeakHashMap<>(initialCapacity, loadFactor)), lockSupplier);
    }

    public LockFactory() {
        this(key -> new StampedLock());
    }

    public StampedLock getLock(final T key) {
        return getMap().computeIfAbsent(key, getLockSupplier());
    }

    protected Map<T, StampedLock> getMap() {
        return map;
    }

    protected Function<T, StampedLock> getLockSupplier() {
        return lockSupplier;
    }

}