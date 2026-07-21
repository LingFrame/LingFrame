package com.lingframe.dashboard.service;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.dashboard.dto.LingResourceMetricsDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灵元资源指标采集器。
 * <p>
 * 定时（5s）遍历所有活跃灵元实例，按 ClassLoader 维度采集类数、线程数、CPU 时间、堆分配增量、Metaspace 估算。
 * <ul>
 *   <li>类数：反射 ClassLoader 私有字段 classes（JDK8 Vector），精确</li>
 *   <li>线程数：遍历所有线程按 TCCL 匹配灵元 ClassLoader 分组，精确</li>
 *   <li>CPU 时间：ThreadMXBean.getThreadCpuTime 按 TCCL 分组累加，精确</li>
 *   <li>堆分配增量：com.sun.management.ThreadMXBean.getThreadAllocatedBytes 差值，精确</li>
 *   <li>Metaspace：类数 × 平均字节，近似</li>
 * </ul>
 * 使用 Spring 生命周期接口兼容 SB2/SB3，避免 javax/jakarta.annotation 差异。
 */
@Slf4j
public class LingResourceMetricsCollector implements InitializingBean, DisposableBean {

    private final LingRepository lingRepository;
    private final long metaspaceBytesPerClass;

    /** 缓存：lingId -> 最新指标 */
    private final Map<String, LingResourceMetricsDTO> cache = new ConcurrentHashMap<>();

    /** 上次采样的线程分配字节快照：threadId -> 累计字节数 */
    private final Map<Long, Long> lastAllocatedBytes = new ConcurrentHashMap<>();

    private volatile ThreadMXBean threadMXBean;
    private volatile boolean allocatedBytesSupported;
    private volatile boolean cpuTimeSupported;

    /** ClassLoader.classes 反射字段缓存，避免重复查找 */
    private volatile Field classesField;

    public LingResourceMetricsCollector(LingRepository lingRepository, long metaspaceBytesPerClass) {
        this.lingRepository = lingRepository;
        this.metaspaceBytesPerClass = metaspaceBytesPerClass;
    }

    @Override
    public void afterPropertiesSet() {
        init();
    }

    public void init() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof ThreadMXBean) {
            this.threadMXBean = (ThreadMXBean) bean;
            try {
                this.cpuTimeSupported = threadMXBean.isThreadCpuTimeSupported();
                if (cpuTimeSupported && !threadMXBean.isThreadCpuTimeEnabled()) {
                    threadMXBean.setThreadCpuTimeEnabled(true);
                }
            } catch (Exception e) {
                log.warn("Failed to enable thread CPU time: {}", e.getMessage());
                this.cpuTimeSupported = false;
            }
            try {
                this.allocatedBytesSupported = threadMXBean.isThreadAllocatedMemorySupported();
                if (allocatedBytesSupported && !threadMXBean.isThreadAllocatedMemoryEnabled()) {
                    threadMXBean.setThreadAllocatedMemoryEnabled(true);
                }
            } catch (Exception e) {
                log.warn("Failed to enable thread allocated bytes: {}", e.getMessage());
                this.allocatedBytesSupported = false;
            }
        } else {
            log.warn("ThreadMXBean is not com.sun.management.ThreadMXBean, CPU/heap metrics will be 0");
        }
    }

    @Override
    public void destroy() {
        cache.clear();
        lastAllocatedBytes.clear();
    }

    /**
     * 定时采样，5 秒一次。
     */
    @Scheduled(fixedRate = 5000, initialDelay = 5000)
    public void sample() {
        try {
            doSample();
        } catch (Exception e) {
            log.error("Ling resource metrics sample failed", e);
        }
    }

    private void doSample() {
        if (lingRepository == null) {
            return;
        }

        // 1. 收集所有活跃灵元的 ClassLoader
        Map<ClassLoader, LingInstance> loaderToInstance = new HashMap<>();
        for (LingRuntime runtime : lingRepository.getAllRuntimes()) {
            if (runtime == null) continue;
            for (LingInstance instance : runtime.getInstancePool().getActiveInstances()) {
                ClassLoader cl = instance.getClassLoader();
                if (cl != null) {
                    loaderToInstance.put(cl, instance);
                }
            }
        }

        if (loaderToInstance.isEmpty()) {
            cache.clear();
            return;
        }

        // 2. 遍历所有线程，按 TCCL 分组统计
        Map<ClassLoader, ThreadGroupStats> statsByLoader = new HashMap<>();
        Map<Thread, StackTraceElement[]> allTraces = Thread.getAllStackTraces();

        for (Thread thread : allTraces.keySet()) {
            ClassLoader tccl = thread.getContextClassLoader();
            if (tccl == null || !loaderToInstance.containsKey(tccl)) {
                continue;
            }
            ThreadGroupStats stats = statsByLoader.computeIfAbsent(tccl, k -> new ThreadGroupStats());
            stats.threadCount++;

            long tid = thread.getId();
            if (cpuTimeSupported && threadMXBean != null) {
                try {
                    long cpuNs = threadMXBean.getThreadCpuTime(tid);
                    if (cpuNs >= 0) {
                        stats.cpuTimeMs += cpuNs / 1_000_000L;
                    }
                } catch (Exception ignore) {
                    // 个别线程 CPU 时间获取失败不影响整体
                }
            }

            if (allocatedBytesSupported && threadMXBean != null) {
                try {
                    long current = threadMXBean.getThreadAllocatedBytes(tid);
                    if (current >= 0) {
                        Long prev = lastAllocatedBytes.get(tid);
                        if (prev != null && current >= prev) {
                            stats.heapDelta += (current - prev);
                        }
                        lastAllocatedBytes.put(tid, current);
                    }
                } catch (Exception ignore) {
                    // 个别线程分配字节获取失败不影响整体
                }
            }
        }

        // 3. 清理已消失线程的快照
        lastAllocatedBytes.keySet().removeIf(tid -> {
            for (Thread t : allTraces.keySet()) {
                if (t.getId() == tid) return false;
            }
            return true;
        });

        // 4. 组装 DTO
        long now = System.currentTimeMillis();
        Map<String, LingResourceMetricsDTO> newCache = new ConcurrentHashMap<>();
        for (Map.Entry<ClassLoader, LingInstance> entry : loaderToInstance.entrySet()) {
            ClassLoader cl = entry.getKey();
            LingInstance instance = entry.getValue();
            ThreadGroupStats stats = statsByLoader.getOrDefault(cl, new ThreadGroupStats());

            int classCount = countLoadedClasses(cl);

            LingResourceMetricsDTO dto = LingResourceMetricsDTO.builder()
                    .lingId(instance.getLingId())
                    .version(instance.getVersion())
                    .loadedClassCount(classCount)
                    .activeThreadCount(stats.threadCount)
                    .cpuTimeMs(stats.cpuTimeMs)
                    .estimatedHeapDeltaBytes(stats.heapDelta)
                    .estimatedMetaspaceBytes(classCount * metaspaceBytesPerClass)
                    .timestamp(now)
                    .build();

            newCache.put(dto.getLingId() + ":" + dto.getVersion(), dto);
        }

        cache.clear();
        cache.putAll(newCache);
    }

    /**
     * 反射读取 ClassLoader.classes 的 size。
     * JDK8 下该字段为 Vector&lt;Class&lt;?&gt;&gt;。
     */
    private int countLoadedClasses(ClassLoader cl) {
        try {
            Field f = classesField;
            if (f == null) {
                f = ClassLoader.class.getDeclaredField("classes");
                f.setAccessible(true);
                classesField = f;
            }
            Object classes = f.get(cl);
            if (classes instanceof List) {
                return ((List<?>) classes).size();
            }
            if (classes instanceof Collection) {
                return ((Collection<?>) classes).size();
            }
        } catch (SecurityException e) {
            log.debug("SecurityManager denied access to ClassLoader.classes for {}", cl, e);
        } catch (Exception e) {
            log.debug("Failed to reflect ClassLoader.classes for {}", cl, e);
        }
        return 0;
    }

    /**
     * 获取所有灵元资源指标快照，按 lingId 排序。
     */
    public List<LingResourceMetricsDTO> getMetrics() {
        if (cache.isEmpty()) {
            return Collections.emptyList();
        }
        List<LingResourceMetricsDTO> list = new ArrayList<>(cache.values());
        list.sort((a, b) -> {
            int c = a.getLingId().compareTo(b.getLingId());
            if (c != 0) return c;
            return a.getVersion().compareTo(b.getVersion());
        });
        return list;
    }

    /** 线程分组统计中间结构 */
    private static final class ThreadGroupStats {
        int threadCount;
        long cpuTimeMs;
        long heapDelta;
    }
}
