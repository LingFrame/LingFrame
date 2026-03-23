package com.lingframe.core.metrics;

import java.util.concurrent.atomic.LongAdder;

public class LatencyHistogram {
    private final LongAdder[] buckets;
    private final long[] bucketBounds;
    
    public LatencyHistogram() {
        this.bucketBounds = new long[]{10, 50, 100, 200, 500, 1000, 2000, 5000, Long.MAX_VALUE};
        this.buckets = new LongAdder[bucketBounds.length];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }
    
    public void record(long latencyMs) {
        for (int i = 0; i < bucketBounds.length; i++) {
            if (latencyMs <= bucketBounds[i]) {
                buckets[i].add(1);
                break;
            }
        }
    }
    
    public long getP99() {
        return getPercentile(99);
    }
    
    public long getP95() {
        return getPercentile(95);
    }
    
    public long getP90() {
        return getPercentile(90);
    }
    
    public long getP50() {
        return getPercentile(50);
    }
    
    private long getPercentile(int percentile) {
        long total = 0;
        for (LongAdder bucket : buckets) {
            total += bucket.sum();
        }
        
        if (total == 0) {
            return 0;
        }
        
        long target = total * percentile / 100;
        long cumulative = 0;
        
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i].sum();
            if (cumulative >= target) {
                return bucketBounds[i];
            }
        }
        
        return bucketBounds[bucketBounds.length - 1];
    }
    
    public void reset() {
        for (LongAdder bucket : buckets) {
            bucket.reset();
        }
    }
    
    public long[] getBucketBounds() {
        return bucketBounds.clone();
    }
    
    public long[] getBucketCounts() {
        long[] counts = new long[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            counts[i] = buckets[i].sum();
        }
        return counts;
    }
}
