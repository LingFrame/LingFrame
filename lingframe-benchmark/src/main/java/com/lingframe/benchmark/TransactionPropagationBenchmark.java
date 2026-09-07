package com.lingframe.benchmark;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionContext.TransactionSnapshot;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import com.lingframe.infra.storage.proxy.NonCloseableLingConnectionProxy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * 事务穿透链路性能基准测试。
 * <p>
 * 量化事务穿透的核心开销项——这是受管灵元每次调用在 NORMAL 模式下的必经路径：
 * <ul>
 *   <li>push/pop —— {@link TransactionPropagationFilter} 压栈与 finally 弹栈（含 PUSH_ORDER 配对维护）；</li>
 *   <li>snapshot —— 跨线程搬运的 capture / apply / restore 三阶段快照；</li>
 *   <li>nonCloseableView —— 穿透命中时受管代理返回 {@link NonCloseableLingConnectionProxy}
 *       的单层 Statement 视图修正。</li>
 * </ul>
 * <p>
 * 运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -Pbenchmark -am -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar TransactionPropagationBenchmark -f 3 -prof gc
 * </pre>
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {
        "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"
})
@State(Scope.Benchmark)
public class TransactionPropagationBenchmark {

    private static final String DATA_SOURCE_ID = "default";

    private Connection poolConnection;
    private LingDataSourceProxy managedProxy;
    private TransactionSnapshot snapshot;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // 模拟灵核根事务连接（穿透压栈的物理连接）——benchmark 模块不引 Mockito，
        // 用 JDK 动态代理满足 Connection 引用语义
        poolConnection = (Connection) Proxy.newProxyInstance(
                TransactionPropagationBenchmark.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return false;
                        case "toString":
                            return "bench-pool-connection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });

        DataSource target = (DataSource) Proxy.newProxyInstance(
                TransactionPropagationBenchmark.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> "getConnection".equals(method.getName())
                        ? poolConnection : null);
        // 权限服务零拦截：仅满足受管代理构造签名
        PermissionService permissionService = new PermissionService() {
            @Override
            public boolean isAllowed(String lingId, String capability, AccessType accessType) {
                return true;
            }

            @Override
            public void grant(String lingId, String capability, AccessType accessType) {
            }

            @Override
            public PermissionInfo getPermission(String lingId, String capability) {
                return null;
            }

            @Override
            public void audit(String lingId, String capability, String operation, boolean allowed) {
            }

            @Override
            public void audit(PermissionAuditRecord record) {
            }
        };
        // 受管代理（携带身份，启用穿透复用）
        managedProxy = new LingDataSourceProxy(target, permissionService, DATA_SOURCE_ID);

        // 预捕获一份跨线程快照（模拟主线程捕获、worker 重放的输入）
        LingTransactionContext.pushConnection(DATA_SOURCE_ID, poolConnection);
        snapshot = LingTransactionContext.captureSnapshot();
        LingTransactionContext.clear();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        LingTransactionContext.clear();
    }

    @Benchmark
    public void pushAndPop() {
        LingTransactionContext.pushConnection(DATA_SOURCE_ID, poolConnection);
        LingTransactionContext.popConnection();
    }

    @Benchmark
    public void pushAndPopWithCleanup() {
        LingTransactionContext.pushConnection(DATA_SOURCE_ID, poolConnection);
        LingTransactionContext.popConnection();
        LingTransactionContext.cleanIfEmpty();
    }

    @Benchmark
    public TransactionSnapshot snapshotCapture() {
        return LingTransactionContext.captureSnapshot();
    }

    @Benchmark
    public void snapshotApplyAndRestore() {
        TransactionSnapshot previous = LingTransactionContext.applySnapshot(snapshot);
        LingTransactionContext.restoreSnapshot(previous, snapshot);
    }

    @Benchmark
    public void nonCloseableViewAcquire() throws SQLException {
        // 穿透命中：受管代理按身份查栈，命中返回 NonCloseable（不向池借新连接）
        LingTransactionContext.pushConnection(DATA_SOURCE_ID, poolConnection);
        try {
            Connection conn = managedProxy.getConnection();
            if (!(conn instanceof NonCloseableLingConnectionProxy)) {
                throw new IllegalStateException("穿透命中应返回 NonCloseable 代理");
            }
        } finally {
            LingTransactionContext.popConnection();
        }
    }
}