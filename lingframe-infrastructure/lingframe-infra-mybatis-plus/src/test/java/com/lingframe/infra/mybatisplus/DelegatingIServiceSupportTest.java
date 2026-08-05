package com.lingframe.infra.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.IService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link DelegatingIServiceSupport} 单元测试。
 * <p>
 * 验证 IService 桩方法统一 delegate 到子类提供的灵核代理，子类无需手写桩代码。
 */
@DisplayName("DelegatingIServiceSupport 抽象基类测试")
class DelegatingIServiceSupportTest {

    /** 测试用实体 */
    private static class FakeEntity {
    }

    /** 测试用子类：只实现 getCoreService，不手写任何 IService 桩 */
    private static class TestImpl extends DelegatingIServiceSupport<FakeEntity> {
        private final IService<FakeEntity> core;

        TestImpl(IService<FakeEntity> core) {
            this.core = core;
        }

        @Override
        protected IService<FakeEntity> getCoreService() {
            return core;
        }
    }

    @Nested
    @DisplayName("IService 桩方法 delegate")
    class IServiceDelegateTest {

        @Test
        @DisplayName("saveBatch 委托灵核")
        void saveBatchDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            when(core.saveBatch(anyList(), eq(100))).thenReturn(true);

            TestImpl impl = new TestImpl(core);
            assertTrue(impl.saveBatch(Arrays.asList(new FakeEntity()), 100));

            verify(core).saveBatch(anyList(), eq(100));
        }

        @Test
        @DisplayName("saveOrUpdateBatch 委托灵核")
        void saveOrUpdateBatchDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            when(core.saveOrUpdateBatch(anyList(), eq(50))).thenReturn(true);

            TestImpl impl = new TestImpl(core);
            assertTrue(impl.saveOrUpdateBatch(Arrays.asList(new FakeEntity()), 50));

            verify(core).saveOrUpdateBatch(anyList(), eq(50));
        }

        @Test
        @DisplayName("updateBatchById 委托灵核")
        void updateBatchByIdDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            when(core.updateBatchById(anyList(), eq(20))).thenReturn(true);

            TestImpl impl = new TestImpl(core);
            assertTrue(impl.updateBatchById(Arrays.asList(new FakeEntity()), 20));

            verify(core).updateBatchById(anyList(), eq(20));
        }

        @Test
        @DisplayName("saveOrUpdate 委托灵核")
        void saveOrUpdateDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            FakeEntity entity = new FakeEntity();
            when(core.saveOrUpdate(entity)).thenReturn(true);

            TestImpl impl = new TestImpl(core);
            assertTrue(impl.saveOrUpdate(entity));

            verify(core).saveOrUpdate(entity);
        }

        @Test
        @DisplayName("getOne 委托灵核")
        void getOneDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            Wrapper<FakeEntity> wrapper = Mockito.mock(Wrapper.class);
            FakeEntity expected = new FakeEntity();
            when(core.getOne(wrapper, false)).thenReturn(expected);

            TestImpl impl = new TestImpl(core);
            assertSame(expected, impl.getOne(wrapper, false));

            verify(core).getOne(wrapper, false);
        }

        @Test
        @DisplayName("getMap 委托灵核")
        void getMapDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            Wrapper<FakeEntity> wrapper = Mockito.mock(Wrapper.class);
            Map<String, Object> expected = Collections.singletonMap("k", "v");
            when(core.getMap(wrapper)).thenReturn(expected);

            TestImpl impl = new TestImpl(core);
            assertSame(expected, impl.getMap(wrapper));

            verify(core).getMap(wrapper);
        }

        @Test
        @DisplayName("getObj 委托灵核")
        void getObjDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            Wrapper<FakeEntity> wrapper = Mockito.mock(Wrapper.class);
            Function<Object, String> mapper = Object::toString;
            when(core.getObj(wrapper, mapper)).thenReturn("result");

            TestImpl impl = new TestImpl(core);
            assertEquals("result", impl.getObj(wrapper, mapper));

            verify(core).getObj(wrapper, mapper);
        }

        @Test
        @DisplayName("getBaseMapper 委托灵核")
        void getBaseMapperDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            BaseMapper<FakeEntity> expected = Mockito.mock(BaseMapper.class);
            when(core.getBaseMapper()).thenReturn(expected);

            TestImpl impl = new TestImpl(core);
            assertSame(expected, impl.getBaseMapper());

            verify(core).getBaseMapper();
        }

        @Test
        @DisplayName("getEntityClass 委托灵核")
        void getEntityClassDelegate() {
            IService<FakeEntity> core = mock(IService.class);
            Class<FakeEntity> expected = FakeEntity.class;
            when(core.getEntityClass()).thenReturn(expected);

            TestImpl impl = new TestImpl(core);
            assertSame(expected, impl.getEntityClass());

            verify(core).getEntityClass();
        }
    }

    @Nested
    @DisplayName("子类覆写能力")
    class OverrideTest {

        @Test
        @DisplayName("子类可覆写业务方法，不走 delegate")
        void subclassCanOverride() {
            IService<FakeEntity> core = mock(IService.class);

            TestImplWithOverride impl = new TestImplWithOverride(core);
            assertEquals("overridden", impl.customBusinessMethod());
            verifyNoInteractions(core);
        }
    }

    @Nested
    @DisplayName("getCoreService 动态取不缓存")
    class DynamicCoreTest {

        @Test
        @DisplayName("每次调用 getCoreService 都重新取值：替换字段后立即生效，无缓存")
        void getCoreServiceNotCached() {
            // 用 volatile 字段子类，模拟 @LingReference 代理热更新场景
            TestImplDynamic impl = new TestImplDynamic();
            IService<FakeEntity> coreA = mock(IService.class);
            IService<FakeEntity> coreB = mock(IService.class);
            when(coreA.saveOrUpdate(any())).thenReturn(true);
            when(coreB.saveOrUpdate(any())).thenReturn(false);

            // 第一次调用：core=A，返回 true
            impl.core = coreA;
            assertTrue(impl.saveOrUpdate(new FakeEntity()), "第一次应走 coreA 返回 true");
            verify(coreA).saveOrUpdate(any());

            // 热更新：替换 core 字段
            impl.core = coreB;

            // 第二次调用：core=B，返回 false——证明每次都重新取，没缓存第一次的 coreA
            assertFalse(impl.saveOrUpdate(new FakeEntity()), "第二次应走 coreB 返回 false");
            verify(coreB).saveOrUpdate(any());
            verifyNoMoreInteractions(coreA);
        }
    }

    /** 子类自定义业务方法（模拟灵元覆写场景） */
    private static class TestImplWithOverride extends DelegatingIServiceSupport<FakeEntity> {
        private final IService<FakeEntity> core;

        TestImplWithOverride(IService<FakeEntity> core) {
            this.core = core;
        }

        @Override
        protected IService<FakeEntity> getCoreService() {
            return core;
        }

        public String customBusinessMethod() {
            return "overridden";
        }
    }

    /** volatile 字段子类：模拟 @LingReference 代理可被热更新替换的场景 */
    private static class TestImplDynamic extends DelegatingIServiceSupport<FakeEntity> {
        volatile IService<FakeEntity> core;

        @Override
        protected IService<FakeEntity> getCoreService() {
            return core;
        }
    }
}
