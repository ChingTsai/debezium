/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.postgresql;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.postgresql.util.PGmoney;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.connector.postgresql.connection.ReplicationConnection;
import io.debezium.connector.postgresql.spi.SlotCreationResult;
import io.debezium.connector.postgresql.spi.Snapshotter;
import io.debezium.data.SpecialValueDecimal;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.Column;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.SchemaChangeEvent.SchemaChangeEventType;
import io.debezium.util.Clock;

public class PostgresSnapshotChangeEventSource extends RelationalSnapshotChangeEventSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresSnapshotChangeEventSource.class);

    private final PostgresConnectorConfig connectorConfig;
    private final PostgresConnection jdbcConnection;
    private final PostgresSchema schema;
    private final Snapshotter snapshotter;
    private final SlotCreationResult slotCreatedInfo;

    public PostgresSnapshotChangeEventSource(PostgresConnectorConfig connectorConfig, Snapshotter snapshotter, PostgresOffsetContext previousOffset, PostgresConnection jdbcConnection, PostgresSchema schema, EventDispatcher<TableId> dispatcher, Clock clock, SnapshotProgressListener snapshotProgressListener, SlotCreationResult slotCreatedInfo) {
        super(connectorConfig, previousOffset, jdbcConnection, dispatcher, clock, snapshotProgressListener);
        this.connectorConfig = connectorConfig;
        this.jdbcConnection = jdbcConnection;
        this.schema = schema;
        this.snapshotter = snapshotter;
        this.slotCreatedInfo = slotCreatedInfo;
    }

    @Override
    protected SnapshottingTask getSnapshottingTask(OffsetContext previousOffset) {
        boolean snapshotSchema = true;
        boolean snapshotData = true;

        snapshotData = snapshotter.shouldSnapshot();
        if (snapshotData) {
            LOGGER.info("According to the connector configuration data will be snapshotted");
        }
        else {
            LOGGER.info("According to the connector configuration no snapshot will be executed");
            snapshotSchema = false;
        }

        return new SnapshottingTask(snapshotSchema, snapshotData);
    }

    @Override
    protected SnapshotContext prepare(ChangeEventSourceContext context) throws Exception {
        return new PostgresSnapshotContext(connectorConfig.databaseName());
    }

    @Override
    protected void connectionCreated(SnapshotContext snapshotContext) throws Exception {
        LOGGER.info("Setting isolation level");
        String transactionStatement = snapshotter.snapshotTransactionIsolationLevelStatement(slotCreatedInfo);
        LOGGER.info("Opening transaction with statement {}", transactionStatement);
        jdbcConnection.executeWithoutCommitting(transactionStatement);
        schema.refresh(jdbcConnection, false);
    }

    @Override
    protected Set<TableId> getAllTableIds(SnapshotContext ctx) throws Exception {
        return jdbcConnection.readTableNames(ctx.catalogName, null, null, new String[] {"TABLE"});
    }

    @Override
    protected void lockTablesForSchemaSnapshot(ChangeEventSourceContext sourceContext, SnapshotContext snapshotContext) throws SQLException, InterruptedException {
        final Duration lockTimeout = connectorConfig.snapshotLockTimeout();
        final Optional<String> lockStatement = snapshotter.snapshotTableLockingStatement(lockTimeout, schema.tableIds());

        if (lockStatement.isPresent()) {
            LOGGER.info("Waiting a maximum of '{}' seconds for each table lock", lockTimeout.getSeconds());
            jdbcConnection.executeWithoutCommitting(lockStatement.get());
            //now that we have the locks, refresh the schema
            schema.refresh(jdbcConnection, false);
        }
        else {
            // if we are not in an exported snapshot, this may result in some inconsistencies.
            // Let the user know
            if (!snapshotter.exportSnapshot()) {
                LOGGER.warn("Step 2: skipping locking each table, this may result in inconsistent schema!");
            }
            else {
                LOGGER.info("Step 2: skipping locking each table in an exported snapshot");
            }
        }
    }

    @Override
    protected void releaseSchemaSnapshotLocks(SnapshotContext snapshotContext) throws SQLException {
    }

    @Override
    protected void determineSnapshotOffset(SnapshotContext ctx) throws Exception {
        PostgresOffsetContext offset = (PostgresOffsetContext) ctx.offset;
        final long xlogStart = getTransactionStartLsn();
        final long txId = jdbcConnection.currentTransactionId().longValue();
        LOGGER.info("Read xlogStart at '{}' from transaction '{}'", ReplicationConnection.format(xlogStart), txId);
        if (offset == null) {
            offset = PostgresOffsetContext.initialContext(connectorConfig, jdbcConnection, getClock());
            ctx.offset = offset;
        }

        // use the old xmin, as we don't want to update it if in xmin recovery
        offset.updateWalPosition(xlogStart, null, clock.currentTime(), txId, null, offset.xmin());
    }

    private long getTransactionStartLsn() throws SQLException {
        if (snapshotter.exportSnapshot() && slotCreatedInfo != null) {
            // When performing an exported snapshot based on a newly created replication slot, the txLogStart position
            // should be based on the replication slot snapshot transaction point.  This is crucial so that if any
            // SQL operations occur mid-snapshot that they'll be properly captured when streaming begins; otherwise
            // they'll be lost.
            return slotCreatedInfo.startLsn();
        }
        return jdbcConnection.currentXLogLocation();
    }

    @Override
    protected void readTableStructure(ChangeEventSourceContext sourceContext, SnapshotContext snapshotContext) throws SQLException, InterruptedException {
        Set<String> schemas = snapshotContext.capturedTables.stream()
                .map(TableId::schema)
                .collect(Collectors.toSet());

        // reading info only for the schemas we're interested in as per the set of captured tables;
        // while the passed table name filter alone would skip all non-included tables, reading the schema
        // would take much longer that way
        for (String schema : schemas) {
            if (!sourceContext.isRunning()) {
                throw new InterruptedException("Interrupted while reading structure of schema " + schema);
            }

            LOGGER.info("Reading structure of schema '{}'", snapshotContext.catalogName);
            jdbcConnection.readSchema(
                    snapshotContext.tables,
                    snapshotContext.catalogName,
                    schema,
                    connectorConfig.getTableFilters().dataCollectionFilter(),
                    null,
                    false
            );
        }
        schema.refresh(jdbcConnection, false);
    }

    @Override
    protected SchemaChangeEvent getCreateTableEvent(SnapshotContext snapshotContext, Table table) throws SQLException {
        return new SchemaChangeEvent(
                snapshotContext.offset.getPartition(),
                snapshotContext.offset.getOffset(),
                snapshotContext.catalogName,
                table.id().schema(),
                null,
                table,
                SchemaChangeEventType.CREATE,
                true);
    }

    @Override
    protected void complete(SnapshotContext snapshotContext) {
    }

    @Override
    protected Optional<String> getSnapshotSelect(SnapshotContext snapshotContext, TableId tableId) {
        return snapshotter.buildSnapshotQuery(tableId);
    }

    @Override
    protected Object getColumnValue(ResultSet rs, int columnIndex, Column column) throws SQLException {
        try {
            final ResultSetMetaData metaData = rs.getMetaData();
            final String columnTypeName = metaData.getColumnTypeName(columnIndex);
            final PostgresType type = schema.getTypeRegistry().get(columnTypeName);

            LOGGER.trace("Type of incoming data is: {}", type.getOid());
            LOGGER.trace("ColumnTypeName is: {}", columnTypeName);
            LOGGER.trace("Type is: {}", type);

            if (type.isArrayType()) {
                return rs.getArray(columnIndex);
            }

            switch (type.getOid()) {
                case PgOid.MONEY:
                    //TODO author=Horia Chiorean date=14/11/2016 description=workaround for https://github.com/pgjdbc/pgjdbc/issues/100
                    return new PGmoney(rs.getString(columnIndex)).val;
                case PgOid.BIT:
                    return rs.getString(columnIndex);
                case PgOid.NUMERIC:
                    final String s = rs.getString(columnIndex);
                    if (s == null) {
                        return s;
                    }

                    Optional<SpecialValueDecimal> value = PostgresValueConverter.toSpecialValue(s);
                    return value.isPresent() ? value.get() : new SpecialValueDecimal(rs.getBigDecimal(columnIndex));
                case PgOid.TIME:
                    // To handle time 24:00:00 supported by TIME columns, read the column as a string.
                case PgOid.TIMETZ:
                    // In order to guarantee that we resolve TIMETZ columns with proper microsecond precision,
                    // read the column as a string instead and then re-parse inside the converter.
                    return rs.getString(columnIndex);
                default:
                    Object x = rs.getObject(columnIndex);
                    if(x != null) {
                        LOGGER.trace("rs getobject returns class: {}; rs getObject value is: {}", x.getClass(), x);
                    }
                    return x;
            }
        }
        catch (SQLException e) {
            // not a known type
            return super.getColumnValue(rs, columnIndex, column);
        }
    }

    /**
     * Mutable context which is populated in the course of snapshotting.
     */
    private static class PostgresSnapshotContext extends SnapshotContext {

        public PostgresSnapshotContext(String catalogName) throws SQLException {
            super(catalogName);
        }
    }
}
