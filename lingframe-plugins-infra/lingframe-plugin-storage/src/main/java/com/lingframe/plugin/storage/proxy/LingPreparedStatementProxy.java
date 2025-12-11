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
public class LingPreparedStatementProxy implements PreparedStatement {

    private final PreparedStatement target;
    private final PermissionService permissionService;
    private final String sql; // 预编译的 SQL
    
    // 预解析的结果
    private final AccessType preParsedAccessType;
    
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
    
    public LingPreparedStatementProxy(PreparedStatement target, PermissionService permissionService, String sql) {
        this.target = target;
        this.permissionService = permissionService;
        this.sql = sql;
        
        // 在构造时预解析SQL类型
        this.preParsedAccessType = parseSqlForAccessTypeWithCache(sql);
    }

    // --- 核心鉴权逻辑 ---
    private void checkPermission() throws SQLException {
        // 1. 获取当前调用者（业务插件ID）
        // 这里依赖我们在 Runtime 层实现的 ThreadLocal Holder
        String callerPluginId = PluginContextHolder.get();
        if (callerPluginId == null) {
            // 如果拿不到 ID，可能是 Core 自身在调用，或者某些特殊情况，默认放行或严格拒绝
            // log.debug("No caller plugin id found, skipping check.");
            return;
        }

        // 2. 使用预解析的结果
        boolean allowed = permissionService.isAllowed(callerPluginId, "storage:sql", preParsedAccessType);

        // 3. 上报审计 (异步)
        permissionService.audit(callerPluginId, "storage:sql", sql, allowed);

        if (!allowed) {
            throw new SQLException(new PermissionDeniedException(
                    "Plugin [" + callerPluginId + "] denied access to SQL: " + sql));
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
    public ResultSet executeQuery() throws SQLException {
        checkPermission();
        return target.executeQuery();
    }

    @Override
    public int executeUpdate() throws SQLException {
        checkPermission();
        return target.executeUpdate();
    }

    @Override
    public boolean execute() throws SQLException {
        checkPermission();
        return target.execute();
    }

    // --- 同样，省略其余上百个 Setter/Getter 的委托方法，IDE 生成即可 ---
    // 下面仅示例几个关键的，其他的必须补全
    @Override public void close() throws SQLException { target.close(); }
    @Override public void setString(int parameterIndex, String x) throws SQLException { target.setString(parameterIndex, x); }
    @Override public void setInt(int parameterIndex, int x) throws SQLException { target.setInt(parameterIndex, x); }
    @Override public void setLong(int parameterIndex, long x) throws SQLException { target.setLong(parameterIndex, x); }
    // ... 请务必补全所有 setXxx 方法
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return target.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return target.isWrapperFor(iface); }
    // ...
    // 省略几百行... (实际开发请用 IDE Generate Delegate Methods)
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
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { return target.executeUpdate(sql, autoGeneratedKeys); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { return target.executeUpdate(sql, columnIndexes); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { return target.executeUpdate(sql, columnNames); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { return target.execute(sql, autoGeneratedKeys); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { return target.execute(sql, columnIndexes); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { return target.execute(sql, columnNames); }
    @Override public int getResultSetHoldability() throws SQLException { return target.getResultSetHoldability(); }
    @Override public boolean isClosed() throws SQLException { return target.isClosed(); }
    @Override public void setPoolable(boolean poolable) throws SQLException { target.setPoolable(poolable); }
    @Override public boolean isPoolable() throws SQLException { return target.isPoolable(); }
    @Override public void closeOnCompletion() throws SQLException { target.closeOnCompletion(); }
    @Override public boolean isCloseOnCompletion() throws SQLException { return target.isCloseOnCompletion(); }
    @Override public int executeUpdate(String sql) throws SQLException { return target.executeUpdate(sql); }
    @Override public boolean execute(String sql) throws SQLException { return target.execute(sql); }
    @Override public ResultSet executeQuery(String sql) throws SQLException { return target.executeQuery(sql); }
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
    @Override public void setNull(int parameterIndex, int sqlType) throws SQLException { target.setNull(parameterIndex, sqlType); }
    @Override public void setBoolean(int parameterIndex, boolean x) throws SQLException { target.setBoolean(parameterIndex, x); }
    @Override public void setByte(int parameterIndex, byte x) throws SQLException { target.setByte(parameterIndex, x); }
    @Override public void setShort(int parameterIndex, short x) throws SQLException { target.setShort(parameterIndex, x); }
    @Override public void setFloat(int parameterIndex, float x) throws SQLException { target.setFloat(parameterIndex, x); }
    @Override public void setDouble(int parameterIndex, double x) throws SQLException { target.setDouble(parameterIndex, x); }
    @Override public void setBigDecimal(int parameterIndex, java.math.BigDecimal x) throws SQLException { target.setBigDecimal(parameterIndex, x); }
    @Override public void setBytes(int parameterIndex, byte[] x) throws SQLException { target.setBytes(parameterIndex, x); }
    @Override public void setDate(int parameterIndex, java.sql.Date x) throws SQLException { target.setDate(parameterIndex, x); }
    @Override public void setTime(int parameterIndex, java.sql.Time x) throws SQLException { target.setTime(parameterIndex, x); }
    @Override public void setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException { target.setTimestamp(parameterIndex, x); }
    @Override public void setAsciiStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException { target.setAsciiStream(parameterIndex, x, length); }
    @Override public void setUnicodeStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException { target.setUnicodeStream(parameterIndex, x, length); }
    @Override public void setBinaryStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException { target.setBinaryStream(parameterIndex, x, length); }
    @Override public void clearParameters() throws SQLException { target.clearParameters(); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException { target.setObject(parameterIndex, x, targetSqlType); }
    @Override public void setObject(int parameterIndex, Object x) throws SQLException { target.setObject(parameterIndex, x); }
    @Override public void addBatch() throws SQLException { target.addBatch(); }
    @Override public void setCharacterStream(int parameterIndex, java.io.Reader reader, int length) throws SQLException { target.setCharacterStream(parameterIndex, reader, length); }
    @Override public void setRef(int parameterIndex, Ref x) throws SQLException { target.setRef(parameterIndex, x); }
    @Override public void setBlob(int parameterIndex, Blob x) throws SQLException { target.setBlob(parameterIndex, x); }
    @Override public void setClob(int parameterIndex, Clob x) throws SQLException { target.setClob(parameterIndex, x); }
    @Override public void setArray(int parameterIndex, Array x) throws SQLException { target.setArray(parameterIndex, x); }
    @Override public ResultSetMetaData getMetaData() throws SQLException { return target.getMetaData(); }
    @Override public void setDate(int parameterIndex, java.sql.Date x, java.util.Calendar cal) throws SQLException { target.setDate(parameterIndex, x, cal); }
    @Override public void setTime(int parameterIndex, java.sql.Time x, java.util.Calendar cal) throws SQLException { target.setTime(parameterIndex, x, cal); }
    @Override public void setTimestamp(int parameterIndex, java.sql.Timestamp x, java.util.Calendar cal) throws SQLException { target.setTimestamp(parameterIndex, x, cal); }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException { target.setNull(parameterIndex, sqlType, typeName); }
    @Override public void setURL(int parameterIndex, java.net.URL x) throws SQLException { target.setURL(parameterIndex, x); }
    @Override public java.sql.ParameterMetaData getParameterMetaData() throws SQLException { return target.getParameterMetaData(); }
    @Override public void setRowId(int parameterIndex, RowId x) throws SQLException { target.setRowId(parameterIndex, x); }
    @Override public void setNString(int parameterIndex, String value) throws SQLException { target.setNString(parameterIndex, value); }
    @Override public void setNCharacterStream(int parameterIndex, java.io.Reader value, long length) throws SQLException { target.setNCharacterStream(parameterIndex, value, length); }
    @Override public void setNClob(int parameterIndex, NClob value) throws SQLException { target.setNClob(parameterIndex, value); }
    @Override public void setClob(int parameterIndex, java.io.Reader reader, long length) throws SQLException { target.setClob(parameterIndex, reader, length); }
    @Override public void setBlob(int parameterIndex, java.io.InputStream inputStream, long length) throws SQLException { target.setBlob(parameterIndex, inputStream, length); }
    @Override public void setNClob(int parameterIndex, java.io.Reader reader, long length) throws SQLException { target.setNClob(parameterIndex, reader, length); }
    @Override public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException { target.setSQLXML(parameterIndex, xmlObject); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException { target.setObject(parameterIndex, x, targetSqlType, scaleOrLength); }
    @Override public void setAsciiStream(int parameterIndex, java.io.InputStream x, long length) throws SQLException { target.setAsciiStream(parameterIndex, x, length); }
    @Override public void setBinaryStream(int parameterIndex, java.io.InputStream x, long length) throws SQLException { target.setBinaryStream(parameterIndex, x, length); }
    @Override public void setCharacterStream(int parameterIndex, java.io.Reader reader, long length) throws SQLException { target.setCharacterStream(parameterIndex, reader, length); }
    @Override public void setAsciiStream(int parameterIndex, java.io.InputStream x) throws SQLException { target.setAsciiStream(parameterIndex, x); }
    @Override public void setBinaryStream(int parameterIndex, java.io.InputStream x) throws SQLException { target.setBinaryStream(parameterIndex, x); }
    @Override public void setCharacterStream(int parameterIndex, java.io.Reader reader) throws SQLException { target.setCharacterStream(parameterIndex, reader); }
    @Override public void setNCharacterStream(int parameterIndex, java.io.Reader value) throws SQLException { target.setNCharacterStream(parameterIndex, value); }
    @Override public void setClob(int parameterIndex, java.io.Reader reader) throws SQLException { target.setClob(parameterIndex, reader); }
    @Override public void setBlob(int parameterIndex, java.io.InputStream inputStream) throws SQLException { target.setBlob(parameterIndex, inputStream); }
    @Override public void setNClob(int parameterIndex, java.io.Reader reader) throws SQLException { target.setNClob(parameterIndex, reader); }
}