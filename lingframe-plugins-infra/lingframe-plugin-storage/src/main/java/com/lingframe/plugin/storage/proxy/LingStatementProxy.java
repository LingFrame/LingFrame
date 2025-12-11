package com.lingframe.plugin.storage.proxy;

import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;

@Slf4j
@RequiredArgsConstructor
public class LingStatementProxy implements Statement {

    private final Statement target;
    private final PermissionService permissionService;
    
    // SQL解析结果缓存 (LRU缓存)
    private static final int MAX_CACHE_SIZE = 1000;
    private static final ConcurrentHashMap<String, SqlParseResult> parseCache = new ConcurrentHashMap<>();
    
    // 缓存条目过期时间 (毫秒)
    private static final long CACHE_EXPIRE_TIME = TimeUnit.MINUTES.toMillis(10);
    
    // SQL解析结果缓存条目
    private static class SqlParseResult {
        final AccessType accessType;
        final long timestamp;
        
        SqlParseResult(AccessType accessType) {
            this.accessType = accessType;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRE_TIME;
        }
    }

    // --- 鉴权逻辑：与 PreparedStatement 类似，只是 SQL 是参数传进来的 ---
    private void checkPermission(String sql) throws SQLException {
        String callerPluginId = PluginContextHolder.get();
        if (callerPluginId == null) return;

        AccessType accessType = parseSqlForAccessTypeWithCache(sql);
        boolean allowed = permissionService.isAllowed(callerPluginId, "storage:sql", accessType);
        permissionService.audit(callerPluginId, "storage:sql", sql, allowed);

        if (!allowed) {
            throw new SQLException(new PermissionDeniedException("Access Denied: " + sql));
        }
    }
    
    /**
     * 带缓存的SQL解析
     * @param sql SQL语句
     * @return 访问类型
     */
    private AccessType parseSqlForAccessTypeWithCache(String sql) {
        // 检查缓存
        SqlParseResult cachedResult = parseCache.get(sql);
        if (cachedResult != null && !cachedResult.isExpired()) {
            return cachedResult.accessType;
        }
        
        // 缓存未命中或已过期，重新解析
        AccessType accessType = parseSqlForAccessType(sql);
        
        // 更新缓存
        if (parseCache.size() < MAX_CACHE_SIZE) {
            parseCache.put(sql, new SqlParseResult(accessType));
        } else {
            // 缓存满时清除过期条目
            parseCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            // 如果仍有空间则添加新条目
            if (parseCache.size() < MAX_CACHE_SIZE) {
                parseCache.put(sql, new SqlParseResult(accessType));
            }
        }
        
        return accessType;
    }
    
    /**
     * 分级SQL解析策略
     * @param sql SQL语句
     * @return 访问类型
     */
    private AccessType parseSqlForAccessType(String sql) {
        // 对于简单的SQL语句，直接使用字符串匹配
        if (isSimpleSql(sql)) {
            return fallbackParseSql(sql);
        }
        
        // 对于复杂SQL语句，使用JSqlParser
        try {
            net.sf.jsqlparser.statement.Statement statement = CCJSqlParserUtil.parse(sql.trim());
            if (statement instanceof Select) {
                return AccessType.READ;
            } else if (statement instanceof Insert || statement instanceof Update || statement instanceof Delete) {
                return AccessType.WRITE;
            } else {
                return AccessType.EXECUTE;
            }
        } catch (JSQLParserException e) {
            log.warn("Failed to parse SQL with JSqlParser, falling back to simple matching: {}", sql);
            return fallbackParseSql(sql);
        }
    }
    
    /**
     * 判断是否为简单SQL语句
     * @param sql SQL语句
     * @return 是否为简单SQL
     */
    private boolean isSimpleSql(String sql) {
        // 简单规则：长度小于100且不包含复杂关键字
        if (sql.length() > 100) {
            return false;
        }
        
        String upperSql = sql.toUpperCase();
        // 如果包含复杂关键字，则认为不是简单SQL
        return !(upperSql.contains("JOIN") || upperSql.contains("UNION") || 
                upperSql.contains("SUBQUERY") || upperSql.contains("CASE"));
    }
    
    /**
     * 简单的字符串匹配作为回退方案
     * @param sql SQL语句
     * @return 访问类型
     */
    private AccessType fallbackParseSql(String sql) {
        String trimmedSql = sql.trim().toUpperCase();
        if (trimmedSql.startsWith("SELECT")) {
            return AccessType.READ;
        } else if (trimmedSql.startsWith("INSERT") || trimmedSql.startsWith("UPDATE") || trimmedSql.startsWith("DELETE")) {
            return AccessType.WRITE;
        } else {
            return AccessType.EXECUTE;
        }
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkPermission(sql);
        return target.executeQuery(sql);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkPermission(sql);
        return target.executeUpdate(sql);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkPermission(sql);
        return target.execute(sql);
    }

    // --- 省略其他委托方法 ---
    @Override public void close() throws SQLException { target.close(); }
    @Override public boolean isClosed() throws SQLException { return target.isClosed(); }
    @Override public int getMaxFieldSize() throws SQLException { return target.getMaxFieldSize(); }
    @Override public void setMaxFieldSize(int max) throws SQLException { target.setMaxFieldSize(max); }
    @Override public int getMaxRows() throws SQLException { return target.getMaxRows(); }
    @Override public void setMaxRows(int max) throws SQLException { target.setMaxRows(max); }
    @Override public void setEscapeProcessing(boolean enable) throws SQLException { target.setEscapeProcessing(enable); }
    @Override public int getQueryTimeout() throws SQLException { return target.getQueryTimeout(); }
    @Override public void setQueryTimeout(int seconds) throws SQLException { target.setQueryTimeout(seconds); }
    @Override public void cancel() throws SQLException { target.cancel(); }
    @Override public SQLWarning getWarnings() throws SQLException { return target.getWarnings(); }
    @Override public void clearWarnings() throws SQLException { target.clearWarnings(); }
    @Override public void setCursorName(String name) throws SQLException { target.setCursorName(name); }
    @Override public ResultSet getResultSet() throws SQLException { return target.getResultSet(); }
    @Override public int getUpdateCount() throws SQLException { return target.getUpdateCount(); }
    @Override public boolean getMoreResults() throws SQLException { return target.getMoreResults(); }
    @Override public void setFetchDirection(int direction) throws SQLException { target.setFetchDirection(direction); }
    @Override public int getFetchDirection() throws SQLException { return target.getFetchDirection(); }
    @Override public void setFetchSize(int rows) throws SQLException { target.setFetchSize(rows); }
    @Override public int getFetchSize() throws SQLException { return target.getFetchSize(); }
    @Override public int getResultSetConcurrency() throws SQLException { return target.getResultSetConcurrency(); }
    @Override public int getResultSetType() throws SQLException { return target.getResultSetType(); }
    @Override public void addBatch(String sql) throws SQLException { target.addBatch(sql); }
    @Override public void clearBatch() throws SQLException { target.clearBatch(); }
    @Override public int[] executeBatch() throws SQLException { return target.executeBatch(); }
    @Override public Connection getConnection() throws SQLException { return target.getConnection(); }
    @Override public boolean getMoreResults(int current) throws SQLException { return target.getMoreResults(current); }
    @Override public ResultSet getGeneratedKeys() throws SQLException { return target.getGeneratedKeys(); }
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { checkPermission(sql); return target.executeUpdate(sql, autoGeneratedKeys); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { checkPermission(sql); return target.executeUpdate(sql, columnIndexes); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { checkPermission(sql); return target.executeUpdate(sql, columnNames); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { checkPermission(sql); return target.execute(sql, autoGeneratedKeys); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { checkPermission(sql); return target.execute(sql, columnIndexes); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { checkPermission(sql); return target.execute(sql, columnNames); }
    @Override public int getResultSetHoldability() throws SQLException { return target.getResultSetHoldability(); }
    @Override public void setPoolable(boolean poolable) throws SQLException { target.setPoolable(poolable); }
    @Override public boolean isPoolable() throws SQLException { return target.isPoolable(); }
    @Override public void closeOnCompletion() throws SQLException { target.closeOnCompletion(); }
    @Override public boolean isCloseOnCompletion() throws SQLException { return target.isCloseOnCompletion(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return target.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return target.isWrapperFor(iface); }
}