package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;

/**
 * DatabaseMetaData 代理
 * 职责：对元数据枚举方法（getTables/getColumns/getIndexInfo 等）返回的 ResultSet 进行代理包装，
 * 防止灵元通过可更新 ResultSet 绕过 SQL 治理；并对元数据枚举施加审计（视为 READ，不拦截）。
 */
@Slf4j
@SuppressWarnings("deprecation")
public class LingDatabaseMetaDataProxy implements DatabaseMetaData {

    /** 元数据枚举的能力键，仅用于审计，不做拦截 */
    private static final String METADATA_CAPABILITY = "storage:sql:metadata";

    private final DatabaseMetaData target;
    private final PermissionService permissionService;

    public LingDatabaseMetaDataProxy(DatabaseMetaData target, PermissionService permissionService) {
        this.target = target;
        this.permissionService = permissionService;
    }

    /**
     * 元数据枚举审计。
     * 元数据查询视为 READ：始终记录审计，但不拦截。
     * @param methodName 触发审计的方法名
     */
    private void auditMetadata(String methodName) {
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            // 灵核自身的元数据查询无需审计
            log.debug("Metadata [{}] without LingContext (LINGCORE).", methodName);
            return;
        }
        permissionService.audit(callerLingId, METADATA_CAPABILITY, "metadata:" + methodName, true);
    }

    /**
     * 包装 target 返回的 ResultSet 为 LingResultSetProxy，并记录元数据审计。
     * @param rs target 返回的原生 ResultSet
     * @param methodName 触发包装的方法名
     * @return 代理后的 ResultSet，若 rs 为 null 则直接返回 null
     */
    private ResultSet wrap(ResultSet rs, String methodName) {
        auditMetadata(methodName);
        if (rs == null) {
            return null;
        }
        // 元数据 ResultSet 无对应 Statement，传 null（getStatement() 返回 null 符合 JDBC 规范）；
        // 传空集保持粗粒度，不施加表级写校验
        return new LingResultSetProxy(rs, permissionService, null, Collections.emptySet());
    }

    // ==================== 返回 ResultSet 的方法：包装 + 审计 ====================

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        return wrap(target.getTables(catalog, schemaPattern, tableNamePattern, types), "getTables");
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return wrap(target.getSchemas(), "getSchemas");
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        return wrap(target.getCatalogs(), "getCatalogs");
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return wrap(target.getTableTypes(), "getTableTypes");
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        return wrap(target.getProcedures(catalog, schemaPattern, procedureNamePattern), "getProcedures");
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
            String columnNamePattern) throws SQLException {
        return wrap(target.getProcedureColumns(catalog, schemaPattern, procedureNamePattern, columnNamePattern),
                "getProcedureColumns");
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        return wrap(target.getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern), "getColumns");
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
            throws SQLException {
        return wrap(target.getColumnPrivileges(catalog, schema, table, columnNamePattern), "getColumnPrivileges");
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return wrap(target.getTablePrivileges(catalog, schemaPattern, tableNamePattern), "getTablePrivileges");
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        return wrap(target.getBestRowIdentifier(catalog, schema, table, scope, nullable), "getBestRowIdentifier");
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return wrap(target.getVersionColumns(catalog, schema, table), "getVersionColumns");
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return wrap(target.getPrimaryKeys(catalog, schema, table), "getPrimaryKeys");
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return wrap(target.getImportedKeys(catalog, schema, table), "getImportedKeys");
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return wrap(target.getExportedKeys(catalog, schema, table), "getExportedKeys");
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
            String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        return wrap(target.getCrossReference(parentCatalog, parentSchema, parentTable,
                foreignCatalog, foreignSchema, foreignTable), "getCrossReference");
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return wrap(target.getTypeInfo(), "getTypeInfo");
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        return wrap(target.getUDTs(catalog, schemaPattern, typeNamePattern, types), "getUDTs");
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return wrap(target.getSchemas(catalog, schemaPattern), "getSchemas(catalog,schema)");
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
            throws SQLException {
        return wrap(target.getSuperTypes(catalog, schemaPattern, typeNamePattern), "getSuperTypes");
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return wrap(target.getSuperTables(catalog, schemaPattern, tableNamePattern), "getSuperTables");
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
            String attributeNamePattern) throws SQLException {
        return wrap(target.getAttributes(catalog, schemaPattern, typeNamePattern, attributeNamePattern),
                "getAttributes");
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        return wrap(target.getFunctions(catalog, schemaPattern, functionNamePattern), "getFunctions");
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
            String columnNamePattern) throws SQLException {
        return wrap(target.getFunctionColumns(catalog, schemaPattern, functionNamePattern, columnNamePattern),
                "getFunctionColumns");
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        return wrap(target.getPseudoColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern),
                "getPseudoColumns");
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return wrap(target.getClientInfoProperties(), "getClientInfoProperties");
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
            throws SQLException {
        return wrap(target.getIndexInfo(catalog, schema, table, unique, approximate), "getIndexInfo");
    }

    // ==================== 其余方法：直接委托 ====================

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        return target.allProceduresAreCallable();
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        return target.allTablesAreSelectable();
    }

    @Override
    public String getURL() throws SQLException {
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId != null) {
            // 灵元调用：脱敏，防止泄露数据库连接串
            return "jdbc:lingframe:masked";
        }
        return target.getURL();
    }

    @Override
    public String getUserName() throws SQLException {
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId != null) {
            // 灵元调用：脱敏，防止泄露数据库用户名
            return "lingframe_masked";
        }
        return target.getUserName();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return target.isReadOnly();
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return target.nullsAreSortedHigh();
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return target.nullsAreSortedLow();
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        return target.nullsAreSortedAtStart();
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        return target.nullsAreSortedAtEnd();
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        return target.getDatabaseProductName();
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return target.getDatabaseProductVersion();
    }

    @Override
    public String getDriverName() throws SQLException {
        return target.getDriverName();
    }

    @Override
    public String getDriverVersion() throws SQLException {
        return target.getDriverVersion();
    }

    @Override
    public int getDriverMajorVersion() {
        return target.getDriverMajorVersion();
    }

    @Override
    public int getDriverMinorVersion() {
        return target.getDriverMinorVersion();
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        return target.usesLocalFiles();
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        return target.usesLocalFilePerTable();
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        return target.supportsMixedCaseIdentifiers();
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        return target.storesUpperCaseIdentifiers();
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        return target.storesLowerCaseIdentifiers();
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        return target.storesMixedCaseIdentifiers();
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        return target.supportsMixedCaseQuotedIdentifiers();
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        return target.storesUpperCaseQuotedIdentifiers();
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        return target.storesLowerCaseQuotedIdentifiers();
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        return target.storesMixedCaseQuotedIdentifiers();
    }

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        return target.getIdentifierQuoteString();
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        return target.getSQLKeywords();
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        return target.getNumericFunctions();
    }

    @Override
    public String getStringFunctions() throws SQLException {
        return target.getStringFunctions();
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        return target.getSystemFunctions();
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        return target.getTimeDateFunctions();
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        return target.getSearchStringEscape();
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        return target.getExtraNameCharacters();
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return target.supportsAlterTableWithAddColumn();
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return target.supportsAlterTableWithDropColumn();
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        return target.supportsColumnAliasing();
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        return target.nullPlusNonNullIsNull();
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        return target.supportsConvert();
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        return target.supportsConvert(fromType, toType);
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        return target.supportsTableCorrelationNames();
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        return target.supportsDifferentTableCorrelationNames();
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return target.supportsExpressionsInOrderBy();
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return target.supportsOrderByUnrelated();
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        return target.supportsGroupBy();
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        return target.supportsGroupByUnrelated();
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return target.supportsGroupByBeyondSelect();
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        return target.supportsLikeEscapeClause();
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        return target.supportsMultipleResultSets();
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        return target.supportsMultipleTransactions();
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        return target.supportsNonNullableColumns();
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        return target.supportsMinimumSQLGrammar();
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        return target.supportsCoreSQLGrammar();
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return target.supportsExtendedSQLGrammar();
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        return target.supportsANSI92EntryLevelSQL();
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        return target.supportsANSI92IntermediateSQL();
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        return target.supportsANSI92FullSQL();
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        return target.supportsIntegrityEnhancementFacility();
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return target.supportsOuterJoins();
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return target.supportsFullOuterJoins();
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return target.supportsLimitedOuterJoins();
    }

    @Override
    public String getSchemaTerm() throws SQLException {
        return target.getSchemaTerm();
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        return target.getProcedureTerm();
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        return target.getCatalogTerm();
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        return target.isCatalogAtStart();
    }

    @Override
    public String getCatalogSeparator() throws SQLException {
        return target.getCatalogSeparator();
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return target.supportsSchemasInDataManipulation();
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return target.supportsSchemasInProcedureCalls();
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return target.supportsSchemasInTableDefinitions();
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return target.supportsSchemasInIndexDefinitions();
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return target.supportsSchemasInPrivilegeDefinitions();
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return target.supportsCatalogsInDataManipulation();
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return target.supportsCatalogsInProcedureCalls();
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return target.supportsCatalogsInTableDefinitions();
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return target.supportsCatalogsInIndexDefinitions();
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        return target.supportsCatalogsInPrivilegeDefinitions();
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        return target.supportsPositionedDelete();
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        return target.supportsPositionedUpdate();
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        return target.supportsSelectForUpdate();
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        return target.supportsStoredProcedures();
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        return target.supportsSubqueriesInComparisons();
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        return target.supportsSubqueriesInExists();
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        return target.supportsSubqueriesInIns();
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return target.supportsSubqueriesInQuantifieds();
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return target.supportsCorrelatedSubqueries();
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        return target.supportsUnion();
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        return target.supportsUnionAll();
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        return target.supportsOpenCursorsAcrossCommit();
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        return target.supportsOpenCursorsAcrossRollback();
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        return target.supportsOpenStatementsAcrossCommit();
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        return target.supportsOpenStatementsAcrossRollback();
    }

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        return target.getMaxBinaryLiteralLength();
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        return target.getMaxCharLiteralLength();
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        return target.getMaxColumnNameLength();
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        return target.getMaxColumnsInGroupBy();
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        return target.getMaxColumnsInIndex();
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        return target.getMaxColumnsInOrderBy();
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        return target.getMaxColumnsInSelect();
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        return target.getMaxColumnsInTable();
    }

    @Override
    public int getMaxConnections() throws SQLException {
        return target.getMaxConnections();
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        return target.getMaxCursorNameLength();
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        return target.getMaxIndexLength();
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        return target.getMaxSchemaNameLength();
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        return target.getMaxProcedureNameLength();
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        return target.getMaxCatalogNameLength();
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        return target.getMaxRowSize();
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        return target.doesMaxRowSizeIncludeBlobs();
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        return target.getMaxStatementLength();
    }

    @Override
    public int getMaxStatements() throws SQLException {
        return target.getMaxStatements();
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        return target.getMaxTableNameLength();
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        return target.getMaxTablesInSelect();
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        return target.getMaxUserNameLength();
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        return target.getDefaultTransactionIsolation();
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        return target.supportsTransactions();
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        return target.supportsTransactionIsolationLevel(level);
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return target.supportsDataDefinitionAndDataManipulationTransactions();
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return target.supportsDataManipulationTransactionsOnly();
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return target.dataDefinitionCausesTransactionCommit();
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return target.dataDefinitionIgnoredInTransactions();
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return target.supportsBatchUpdates();
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        return target.supportsResultSetConcurrency(type, concurrency);
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        return target.supportsResultSetHoldability(holdability);
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        return target.supportsResultSetType(type);
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        return target.ownUpdatesAreVisible(type);
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        return target.ownDeletesAreVisible(type);
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        return target.ownInsertsAreVisible(type);
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        return target.othersUpdatesAreVisible(type);
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        return target.othersDeletesAreVisible(type);
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        return target.othersInsertsAreVisible(type);
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        return target.updatesAreDetected(type);
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        return target.deletesAreDetected(type);
    }

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        return target.insertsAreDetected(type);
    }

    @Override
    public Connection getConnection() throws SQLException {
        // 拒绝通过 DatabaseMetaData 反向获取 Connection，防止绕过 Connection 治理代理
        // 灵元应直接使用已有的 LingConnectionProxy，不应从元数据反向取连接
        throw new SQLFeatureNotSupportedException(
                "DatabaseMetaData.getConnection() is disabled in LingFrame Security Mode to prevent connection bypass.");
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        return target.supportsSavepoints();
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        return target.supportsNamedParameters();
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        return target.supportsMultipleOpenResults();
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws SQLException {
        return target.supportsGetGeneratedKeys();
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return target.getResultSetHoldability();
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return target.getDatabaseMajorVersion();
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return target.getDatabaseMinorVersion();
    }

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        return target.getJDBCMajorVersion();
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        return target.getJDBCMinorVersion();
    }

    @Override
    public int getSQLStateType() throws SQLException {
        return target.getSQLStateType();
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        return target.locatorsUpdateCopy();
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        return target.supportsStatementPooling();
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        return target.getRowIdLifetime();
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return target.supportsStoredFunctionsUsingCallSyntax();
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        return target.autoCommitFailureClosesAllResultSets();
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        return target.generatedKeyAlwaysReturned();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        // 拒绝暴露原生 DatabaseMetaData 实现，防止绕过元数据治理代理
        throw new SQLException("Cannot unwrap to " + iface.getName()
                + ": LingDatabaseMetaDataProxy only exposes the DatabaseMetaData interface");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }
}
