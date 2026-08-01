package com.lingframe.infra.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * 灵元 delegate 灵核 IService 的公共抽象基类。
 * <p>
 * 适用场景：灵元 implements 灵核原生 Service 接口（该接口 extends MyBatis-Plus {@link IService}）时，
 * 灵元被迫实现 IService 的全部抽象方法。由于灵元 Spring 上下文与灵核隔离、灵元不持有 DataSource，
 * 这些方法只能 delegate 到灵核实现。本基类统一处理这层 delegate 桩，子类只需覆写业务目标方法。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>零强引用：本基类不持有灵核 Bean 引用，子类通过 {@link #getCoreService()} 返回
 *       {@code @LingReference} 注入的代理（代理本身是零强引用设计，只持字符串元数据）。</li>
 *   <li>不引入 ClassLoader 泄漏：本基类是静态继承，不生成代理类，无需配套 Cleaner。</li>
 *   <li>不依赖 Spring：纯抽象类，可在任意上下文使用。</li>
 * </ul>
 * 子类示例：
 * <pre>
 * {@literal @Component}
 * public class SaaSUserServiceImpl extends DelegatingIServiceSupport&lt;User&gt; implements UserService {
 *     {@literal @LingReference(lingId = "lingcore-app")}
 *     private UserService coreUserService;
 *
 *     {@literal @Override}
 *     protected IService&lt;User&gt; getCoreService() {
 *         return coreUserService;
 *     }
 *
 *     // 只写覆盖方法，零 IService 桩代码
 *     {@literal @Override}
 *     public String socialLogin(...) { ... }
 * }
 * </pre>
 *
 * @param <T> 实体类型
 */
public abstract class DelegatingIServiceSupport<T> implements IService<T> {

    /**
     * 子类提供灵核 IService 代理（通常由 {@code @LingReference(lingId="lingcore-app")} 注入）。
     * <p>
     * 代理是零强引用设计，灵元卸载时由 SmartServiceProxy 机制自动清理，无 ClassLoader 泄漏风险。
     *
     * @return 灵核侧 IService 代理
     */
    protected abstract IService<T> getCoreService();

    // —— IService 抽象方法：统一 delegate 到灵核 ——

    @Override
    public boolean saveBatch(Collection<T> entityList, int batchSize) {
        return getCoreService().saveBatch(entityList, batchSize);
    }

    @Override
    public boolean saveOrUpdateBatch(Collection<T> entityList, int batchSize) {
        return getCoreService().saveOrUpdateBatch(entityList, batchSize);
    }

    @Override
    public boolean updateBatchById(Collection<T> entityList, int batchSize) {
        return getCoreService().updateBatchById(entityList, batchSize);
    }

    @Override
    public boolean saveOrUpdate(T entity) {
        return getCoreService().saveOrUpdate(entity);
    }

    @Override
    public T getOne(Wrapper<T> queryWrapper, boolean throwEx) {
        return getCoreService().getOne(queryWrapper, throwEx);
    }

    @Override
    public Map<String, Object> getMap(Wrapper<T> queryWrapper) {
        return getCoreService().getMap(queryWrapper);
    }

    @Override
    public <V> V getObj(Wrapper<T> queryWrapper, Function<? super Object, V> mapper) {
        return getCoreService().getObj(queryWrapper, mapper);
    }

    @Override
    public BaseMapper<T> getBaseMapper() {
        return getCoreService().getBaseMapper();
    }

    @Override
    public Class<T> getEntityClass() {
        return getCoreService().getEntityClass();
    }
}
