package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * ResultSet 代理
 * 职责：对可更新 ResultSet 的写操作（updateXxx、updateRow、deleteRow、insertRow 等）施加权限治理，
 * 防止灵元通过可更新 ResultSet 绕过 SQL 治理直接写入数据。
 * 读操作（getXxx、next、previous 等）直接委托 target，不施加权限检查。
 */
@Slf4j
public class LingResultSetProxy implements ResultSet {

    /** 可更新 ResultSet 写操作的能力键 */
    private static final String RESULTSET_WRITE_CAPABILITY = "storage:sql:resultSet";

    private final ResultSet target;
    private final PermissionService permissionService;
    /** 产生此 ResultSet 的代理 Statement，getStatement() 返回它以避免暴露原生 Statement；可为 null */
    private final Statement proxyStatement;
    /** 此 ResultSet 可更新的表名集合（小写 short form，无 schema 前缀）；可为空集/null 表示仅粗粒度校验 */
    private final Set<String> updatableTables;

    public LingResultSetProxy(ResultSet target, PermissionService permissionService,
                              Statement proxyStatement, Set<String> updatableTables) {
        this.target = target;
        this.permissionService = permissionService;
        this.proxyStatement = proxyStatement;
        this.updatableTables = updatableTables;
    }

    /**
     * 写操作权限检查。
     * 与 LingStatementProxy 的 checkPermission 逻辑对齐：
     * 无 LingContext 时按灵核治理开关决定放行/拒绝（fail-closed）；
     * 否则先校验粗粒度 storage:sql:resultSet 的 WRITE，再按需校验行所属表级 WRITE。
     * <p>
     * 表级校验仅在 updatableTables 非空且灵元启用了表级治理
     * （hasCapabilityPrefix "storage:sql:table:"）时进行：对每个 updatableTable
     * 校验 storage:sql:table:&lt;table&gt; 的 WRITE，任一拒绝即拒绝并审计。
     * 防止灵元凭 READ + resultSet WRITE 即可写表，绕过表级治理。
     */
    private void checkWritePermission() throws SQLException {
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            // 无上下文：按灵核治理开关决定
            if (!SqlPermissionSupport.checkLingCoreGovernance(permissionService, "resultSet:write")) {
                throw new SQLException(
                        "Access Denied: LINGCORE governance is enabled but no context provided for ResultSet write.");
            }
            return;
        }
        // 第一道：粗粒度 resultSet WRITE
        boolean allowed = permissionService.isAllowed(callerLingId, RESULTSET_WRITE_CAPABILITY, AccessType.WRITE);
        permissionService.audit(callerLingId, RESULTSET_WRITE_CAPABILITY, "resultSet:write", allowed);
        if (!allowed) {
            throw new SQLException(
                    new PermissionDeniedException(callerLingId, RESULTSET_WRITE_CAPABILITY, AccessType.WRITE));
        }
        // 第二道：表级 WRITE（仅当 updatableTables 非空且灵元启用表级治理时检查）
        if (updatableTables != null && !updatableTables.isEmpty()
                && permissionService.hasCapabilityPrefix(callerLingId, SqlPermissionSupport.TABLE_CAPABILITY_PREFIX)) {
            for (String table : updatableTables) {
                String tableCapability = SqlPermissionSupport.TABLE_CAPABILITY_PREFIX + table;
                boolean tableAllowed = permissionService.isAllowed(callerLingId, tableCapability, AccessType.WRITE);
                permissionService.audit(callerLingId, tableCapability,
                        "resultSet:table:write:" + table, tableAllowed);
                if (!tableAllowed) {
                    throw new SQLException(
                            new PermissionDeniedException(callerLingId, tableCapability, AccessType.WRITE));
                }
            }
        }
    }

    // ==================== 写操作：先做权限检查 ====================

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        checkWritePermission();
        target.updateNull(columnIndex);
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        checkWritePermission();
        target.updateNull(columnLabel);
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        checkWritePermission();
        target.updateBoolean(columnIndex, x);
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        checkWritePermission();
        target.updateBoolean(columnLabel, x);
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        checkWritePermission();
        target.updateByte(columnIndex, x);
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        checkWritePermission();
        target.updateByte(columnLabel, x);
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        checkWritePermission();
        target.updateShort(columnIndex, x);
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        checkWritePermission();
        target.updateShort(columnLabel, x);
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        checkWritePermission();
        target.updateInt(columnIndex, x);
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        checkWritePermission();
        target.updateInt(columnLabel, x);
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        checkWritePermission();
        target.updateLong(columnIndex, x);
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        checkWritePermission();
        target.updateLong(columnLabel, x);
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        checkWritePermission();
        target.updateFloat(columnIndex, x);
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        checkWritePermission();
        target.updateFloat(columnLabel, x);
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        checkWritePermission();
        target.updateDouble(columnIndex, x);
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        checkWritePermission();
        target.updateDouble(columnLabel, x);
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        checkWritePermission();
        target.updateBigDecimal(columnIndex, x);
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        checkWritePermission();
        target.updateBigDecimal(columnLabel, x);
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        checkWritePermission();
        target.updateString(columnIndex, x);
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        checkWritePermission();
        target.updateString(columnLabel, x);
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        checkWritePermission();
        target.updateBytes(columnIndex, x);
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        checkWritePermission();
        target.updateBytes(columnLabel, x);
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        checkWritePermission();
        target.updateDate(columnIndex, x);
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        checkWritePermission();
        target.updateDate(columnLabel, x);
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        checkWritePermission();
        target.updateTime(columnIndex, x);
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        checkWritePermission();
        target.updateTime(columnLabel, x);
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        checkWritePermission();
        target.updateTimestamp(columnIndex, x);
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        checkWritePermission();
        target.updateTimestamp(columnLabel, x);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        checkWritePermission();
        target.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        checkWritePermission();
        target.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        checkWritePermission();
        target.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        checkWritePermission();
        target.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        checkWritePermission();
        target.updateAsciiStream(columnIndex, x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        checkWritePermission();
        target.updateAsciiStream(columnLabel, x);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        checkWritePermission();
        target.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        checkWritePermission();
        target.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        checkWritePermission();
        target.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        checkWritePermission();
        target.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        checkWritePermission();
        target.updateBinaryStream(columnIndex, x);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        checkWritePermission();
        target.updateBinaryStream(columnLabel, x);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        checkWritePermission();
        target.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        checkWritePermission();
        target.updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        checkWritePermission();
        target.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        checkWritePermission();
        target.updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        checkWritePermission();
        target.updateCharacterStream(columnIndex, x);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        checkWritePermission();
        target.updateCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        checkWritePermission();
        target.updateObject(columnIndex, x, scaleOrLength);
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        checkWritePermission();
        target.updateObject(columnLabel, x, scaleOrLength);
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        checkWritePermission();
        target.updateObject(columnIndex, x);
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        checkWritePermission();
        target.updateObject(columnLabel, x);
    }

    @Override
    public void updateObject(int columnIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        checkWritePermission();
        target.updateObject(columnIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void updateObject(String columnLabel, Object x, SQLType targetSqlType, int scaleOrLength)
            throws SQLException {
        checkWritePermission();
        target.updateObject(columnLabel, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void updateObject(int columnIndex, Object x, SQLType targetSqlType) throws SQLException {
        checkWritePermission();
        target.updateObject(columnIndex, x, targetSqlType);
    }

    @Override
    public void updateObject(String columnLabel, Object x, SQLType targetSqlType) throws SQLException {
        checkWritePermission();
        target.updateObject(columnLabel, x, targetSqlType);
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        checkWritePermission();
        target.updateRef(columnIndex, x);
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        checkWritePermission();
        target.updateRef(columnLabel, x);
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        checkWritePermission();
        target.updateBlob(columnIndex, x);
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        checkWritePermission();
        target.updateBlob(columnLabel, x);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        checkWritePermission();
        target.updateBlob(columnIndex, inputStream, length);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        checkWritePermission();
        target.updateBlob(columnLabel, inputStream, length);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        checkWritePermission();
        target.updateBlob(columnIndex, inputStream);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        checkWritePermission();
        target.updateBlob(columnLabel, inputStream);
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        checkWritePermission();
        target.updateClob(columnIndex, x);
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        checkWritePermission();
        target.updateClob(columnLabel, x);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        checkWritePermission();
        target.updateClob(columnIndex, reader, length);
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        checkWritePermission();
        target.updateClob(columnLabel, reader, length);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        checkWritePermission();
        target.updateClob(columnIndex, reader);
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        checkWritePermission();
        target.updateClob(columnLabel, reader);
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        checkWritePermission();
        target.updateArray(columnIndex, x);
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        checkWritePermission();
        target.updateArray(columnLabel, x);
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        checkWritePermission();
        target.updateRowId(columnIndex, x);
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        checkWritePermission();
        target.updateRowId(columnLabel, x);
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        checkWritePermission();
        target.updateNString(columnIndex, nString);
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        checkWritePermission();
        target.updateNString(columnLabel, nString);
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        checkWritePermission();
        target.updateNClob(columnIndex, nClob);
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        checkWritePermission();
        target.updateNClob(columnLabel, nClob);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        checkWritePermission();
        target.updateNClob(columnIndex, reader, length);
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        checkWritePermission();
        target.updateNClob(columnLabel, reader, length);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        checkWritePermission();
        target.updateNClob(columnIndex, reader);
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        checkWritePermission();
        target.updateNClob(columnLabel, reader);
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        checkWritePermission();
        target.updateSQLXML(columnIndex, xmlObject);
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        checkWritePermission();
        target.updateSQLXML(columnLabel, xmlObject);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        checkWritePermission();
        target.updateNCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        checkWritePermission();
        target.updateNCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        checkWritePermission();
        target.updateNCharacterStream(columnIndex, x);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        checkWritePermission();
        target.updateNCharacterStream(columnLabel, reader);
    }

    @Override
    public void insertRow() throws SQLException {
        checkWritePermission();
        target.insertRow();
    }

    @Override
    public void updateRow() throws SQLException {
        checkWritePermission();
        target.updateRow();
    }

    @Override
    public void deleteRow() throws SQLException {
        checkWritePermission();
        target.deleteRow();
    }

    @Override
    public void refreshRow() throws SQLException {
        // refreshRow 会重新从数据库读取当前行数据，本质是读操作，但可能影响 ResultSet 内部状态
        // 这里保守地按写操作处理，避免灵元通过 refreshRow 触发副作用
        checkWritePermission();
        target.refreshRow();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        checkWritePermission();
        target.cancelRowUpdates();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        checkWritePermission();
        target.moveToInsertRow();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        checkWritePermission();
        target.moveToCurrentRow();
    }

    // ==================== 读操作：直接委托 ====================

    @Override
    public boolean next() throws SQLException {
        return target.next();
    }

    @Override
    public void close() throws SQLException {
        target.close();
    }

    @Override
    public boolean wasNull() throws SQLException {
        return target.wasNull();
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return target.getString(columnIndex);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return target.getString(columnLabel);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return target.getBoolean(columnIndex);
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return target.getBoolean(columnLabel);
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return target.getByte(columnIndex);
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return target.getByte(columnLabel);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return target.getShort(columnIndex);
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return target.getShort(columnLabel);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return target.getInt(columnIndex);
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return target.getInt(columnLabel);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return target.getLong(columnIndex);
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return target.getLong(columnLabel);
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return target.getFloat(columnIndex);
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return target.getFloat(columnLabel);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return target.getDouble(columnIndex);
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return target.getDouble(columnLabel);
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return target.getBigDecimal(columnIndex, scale);
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return target.getBigDecimal(columnLabel, scale);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return target.getBigDecimal(columnIndex);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return target.getBigDecimal(columnLabel);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return target.getBytes(columnIndex);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return target.getBytes(columnLabel);
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return target.getDate(columnIndex);
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return target.getDate(columnLabel);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return target.getDate(columnIndex, cal);
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return target.getDate(columnLabel, cal);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return target.getTime(columnIndex);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return target.getTime(columnLabel);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return target.getTime(columnIndex, cal);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return target.getTime(columnLabel, cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return target.getTimestamp(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return target.getTimestamp(columnLabel);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return target.getTimestamp(columnIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return target.getTimestamp(columnLabel, cal);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return target.getAsciiStream(columnIndex);
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return target.getAsciiStream(columnLabel);
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        return target.getUnicodeStream(columnIndex);
    }

    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return target.getUnicodeStream(columnLabel);
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        return target.getBinaryStream(columnIndex);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return target.getBinaryStream(columnLabel);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return target.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        target.clearWarnings();
    }

    @Override
    public String getCursorName() throws SQLException {
        return target.getCursorName();
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return target.getMetaData();
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return target.getObject(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return target.getObject(columnLabel);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return target.getObject(columnIndex, map);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return target.getObject(columnLabel, map);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return target.getObject(columnIndex, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return target.getObject(columnLabel, type);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        return target.findColumn(columnLabel);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        return target.getCharacterStream(columnIndex);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return target.getCharacterStream(columnLabel);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return target.getNCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return target.getNCharacterStream(columnLabel);
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return target.isBeforeFirst();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        return target.isAfterLast();
    }

    @Override
    public boolean isFirst() throws SQLException {
        return target.isFirst();
    }

    @Override
    public boolean isLast() throws SQLException {
        return target.isLast();
    }

    @Override
    public void beforeFirst() throws SQLException {
        target.beforeFirst();
    }

    @Override
    public void afterLast() throws SQLException {
        target.afterLast();
    }

    @Override
    public boolean first() throws SQLException {
        return target.first();
    }

    @Override
    public boolean last() throws SQLException {
        return target.last();
    }

    @Override
    public int getRow() throws SQLException {
        return target.getRow();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        return target.absolute(row);
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        return target.relative(rows);
    }

    @Override
    public boolean previous() throws SQLException {
        return target.previous();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        target.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return target.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        target.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return target.getFetchSize();
    }

    @Override
    public int getType() throws SQLException {
        return target.getType();
    }

    @Override
    public int getConcurrency() throws SQLException {
        return target.getConcurrency();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        return target.rowUpdated();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        return target.rowInserted();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        return target.rowDeleted();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return target.isClosed();
    }

    @Override
    public Statement getStatement() throws SQLException {
        // 返回构造时传入的代理 Statement（若构造者未提供则返回 null）。
        // 不能返回 target.getStatement()：那会暴露原生 Statement，灵元可通过
        // rs.getStatement().executeUpdate(...) 绕过 SQL 治理，也可通过
        // rs.getStatement().getConnection() 暴露原生 Connection 绕过 setCatalog 禁令。
        // JDBC 规范：无 Statement 产生该 ResultSet 时 getStatement() 返回 null，合规。
        return proxyStatement;
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        return target.getRef(columnIndex);
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return target.getRef(columnLabel);
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        return target.getBlob(columnIndex);
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return target.getBlob(columnLabel);
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        return target.getClob(columnIndex);
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return target.getClob(columnLabel);
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        return target.getArray(columnIndex);
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return target.getArray(columnLabel);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        return target.getURL(columnIndex);
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return target.getURL(columnLabel);
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        return target.getNClob(columnIndex);
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return target.getNClob(columnLabel);
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        return target.getSQLXML(columnIndex);
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return target.getSQLXML(columnLabel);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return target.getNString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return target.getNString(columnLabel);
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        return target.getRowId(columnIndex);
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return target.getRowId(columnLabel);
    }

    @Override
    public int getHoldability() throws SQLException {
        return target.getHoldability();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        // 拒绝暴露原生 ResultSet 实现，防止绕过 SQL 治理代理
        throw new SQLException("Cannot unwrap to " + iface.getName()
                + ": LingResultSetProxy only exposes the ResultSet interface");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }
}
