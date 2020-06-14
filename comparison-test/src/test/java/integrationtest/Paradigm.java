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
package integrationtest;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import blocking.BlockingDemoApplication;
import reactive.ReactiveDemoApplication;

enum Paradigm {

    REACTIVE(ReactiveDemoApplication.class),
    BLOCKING(BlockingDemoApplication.class);

    private final Map<TimingMetric, Collection<Duration>> durations = new ConcurrentHashMap<>();
    private final Map<CountMetric, Collection<Float>> counts = new ConcurrentHashMap<>();
    private final Class<?> mainClass;

    private Paradigm(final Class<?> mainClass) {
        Objects.requireNonNull(mainClass);
        this.mainClass = mainClass;
    }

    public Class<?> getMainClass() {
        return mainClass;
    }

    public void logDuration(final TimingMetric metric, final Duration duration) {
        final var bucket = durations.computeIfAbsent(metric, key -> new ConcurrentLinkedQueue<>());
        bucket.add(duration);
    }

    public Duration getAverageDuration(final TimingMetric metric) {
        final var bucket = durations.get(metric);
        if (bucket == null || bucket.isEmpty()) {
            return null;
        }
        var total = Duration.ZERO;
        for (final var duration : bucket) {
            total = total.plus(duration);
        }
        return total.dividedBy(bucket.size());
    }

    public void logThroughput(final CountMetric metric, final int count, final Duration limit) {
        final var bucket = counts.computeIfAbsent(metric, key -> new ConcurrentLinkedQueue<>());
        final float throughput = (float)count / limit.toSeconds();
        bucket.add(throughput);
    }

    public Float getThroughput(CountMetric metric) {
        final var bucket = counts.get(metric);
        if (bucket == null || bucket.isEmpty()) {
            return null;
        }
        var total = 0.0f;
        for (final var count : bucket) {
            total += count;
        }
        return total / bucket.size();
    }

}