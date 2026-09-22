package com.lingframe.starter.event;

import com.lingframe.api.event.lifecycle.LingStartedEvent;
import com.lingframe.api.event.lifecycle.LingStoppedEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.spi.ServiceExporter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ServiceExporterListener} 补充测试。
 * <p>
 * 重点覆盖构造器订阅行为、onLingStarted / onLingStopped 事件回调、
 * 异常隔离与 shutdown 退订逻辑。事件分发使用真实 EventBus 同步派发。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceExporterListener 补充测试")
class ServiceExporterListenerSupplementTest {

    @Mock
    private LingRepository lingRepository;
    @Mock
    private LingServiceRegistry lingServiceRegistry;
    @Mock
    private ServiceExporter exporter;
    @Mock
    private LingRuntime runtime;

    @Test
    @DisplayName("exporters 非空时构造器应订阅 LingStarted / LingStopped 事件")
    void shouldSubscribeWhenExportersNotEmpty() {
        EventBus eventBus = new EventBus();

        new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry,
                Collections.singletonList(exporter));

        // 通过发布事件验证订阅是否生效
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);

        eventBus.publish(new LingStartedEvent("ling-a", "1.0"));

        // onLingStarted 被调用后应触发 lingServiceRegistry.getServicesByLingId
        verify(lingServiceRegistry).getServicesByLingId("ling-a");
    }

    @Test
    @DisplayName("exporters 为 null 或空时构造器不应订阅事件")
    void shouldNotSubscribeWhenExportersEmptyOrNull() {
        EventBus eventBus = new EventBus();

        // null exporters
        new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry, null);
        // 空 exporters
        new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry,
                Collections.emptyList());

        // 发布事件后不应触发任何回调
        eventBus.publish(new LingStartedEvent("ling-a", "1.0"));
        eventBus.publish(new LingStoppedEvent("ling-a", "1.0"));

        verify(lingRepository, never()).getRuntime(anyString());
    }

    @Test
    @DisplayName("onLingStarted 在 runtime 为 null 时应安全返回")
    void shouldReturnWhenRuntimeNull() {
        EventBus eventBus = new EventBus();
        when(lingRepository.getRuntime("ling-a")).thenReturn(null);

        new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry,
                Collections.singletonList(exporter));

        eventBus.publish(new LingStartedEvent("ling-a", "1.0"));

        // runtime 为 null，不应调用 getServicesByLingId
        verify(lingServiceRegistry, never()).getServicesByLingId(anyString());
        verify(exporter, never()).export(anyString(), anyList());
    }

    @Test
    @DisplayName("onLingStarted 应将服务列表派发至所有 exporter")
    void shouldExportServicesOnLingStarted() {
        EventBus eventBus = new EventBus();
        List<String> services = Arrays.asList("user:UserService", "order:OrderService");

        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(lingServiceRegistry.getServicesByLingId("ling-a")).thenReturn(services);

        new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry,
                Collections.singletonList(exporter));

        eventBus.publish(new LingStartedEvent("ling-a", "1.0"));

        verify(exporter).export("ling-a", services);
    }

    @Test
    @DisplayName("onLingStarted 在某个 exporter 抛异常时应隔离异常继续执行")
    void shouldIsolateExporterException() {
        EventBus eventBus = new EventBus();
        ServiceExporter failingExporter = Mockito.mock(ServiceExporter.class);
        ServiceExporter normalExporter = Mockito.mock(ServiceExporter.class);
        List<String> services = Collections.singletonList("user:UserService");

        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(lingServiceRegistry.getServicesByLingId("ling-a")).thenReturn(services);
        // 第一个 exporter 抛异常
        Mockito.doThrow(new RuntimeException("Nacos down"))
                .when(failingExporter).export("ling-a", services);

        new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry,
                Arrays.asList(failingExporter, normalExporter));

        // 发布事件不应抛出异常
        assertDoesNotThrow(() -> eventBus.publish(new LingStartedEvent("ling-a", "1.0")));

        // 第二个 exporter 仍应被调用（异常隔离）
        verify(normalExporter).export("ling-a", services);
    }

    @Test
    @DisplayName("onLingStopped 应对所有 exporter 调用 unexport")
    void shouldUnexportOnLingStopped() {
        EventBus eventBus = new EventBus();
        ServiceExporter exporter2 = Mockito.mock(ServiceExporter.class);

        new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry,
                Arrays.asList(exporter, exporter2));

        eventBus.publish(new LingStoppedEvent("ling-a", "1.0"));

        verify(exporter).unexport("ling-a");
        verify(exporter2).unexport("ling-a");
    }

    @Test
    @DisplayName("shutdown 应从 EventBus 退订两个监听器")
    void shouldUnsubscribeOnShutdown() {
        EventBus eventBus = new EventBus();

        ServiceExporterListener listener = new ServiceExporterListener(
                eventBus, lingRepository, lingServiceRegistry,
                Collections.singletonList(exporter));

        // shutdown 前发布事件应触发回调
        when(lingRepository.getRuntime("ling-a")).thenReturn(null);
        eventBus.publish(new LingStartedEvent("ling-a", "1.0"));
        verify(lingRepository, times(1)).getRuntime("ling-a");

        // shutdown
        listener.shutdown();

        // shutdown 后再发布事件不应触发回调
        eventBus.publish(new LingStartedEvent("ling-a", "1.0"));
        verify(lingRepository, times(1)).getRuntime("ling-a"); // 仍为 1 次
    }

    @Test
    @DisplayName("shutdown 在 exporters 为空时应安全返回不报错")
    void shouldShutdownSafelyWhenExportersEmpty() {
        EventBus eventBus = new EventBus();

        ServiceExporterListener listener = new ServiceExporterListener(
                eventBus, lingRepository, lingServiceRegistry, null);

        assertDoesNotThrow(listener::shutdown);
    }
}
