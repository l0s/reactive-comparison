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

}