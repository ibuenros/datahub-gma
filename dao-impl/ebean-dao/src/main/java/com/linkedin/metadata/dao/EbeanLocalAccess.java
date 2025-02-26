package com.linkedin.metadata.dao;

import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.data.template.SetMode;
import com.linkedin.metadata.aspect.AuditedAspect;
import com.linkedin.metadata.dao.builder.BaseLocalRelationshipBuilder;
import com.linkedin.metadata.dao.builder.LocalRelationshipBuilderRegistry;
import com.linkedin.metadata.dao.scsi.EmptyPathExtractor;
import com.linkedin.metadata.dao.scsi.UrnPathExtractor;
import com.linkedin.metadata.dao.utils.EBeanDAOUtils;
import com.linkedin.metadata.dao.utils.ModelUtils;
import com.linkedin.metadata.dao.utils.RecordUtils;
import com.linkedin.metadata.dao.utils.SQLSchemaUtils;
import com.linkedin.metadata.dao.utils.SQLStatementUtils;
import com.linkedin.metadata.query.IndexFilter;
import com.linkedin.metadata.query.IndexGroupByCriterion;
import com.linkedin.metadata.query.IndexSortCriterion;
import io.ebean.EbeanServer;
import io.ebean.SqlQuery;
import io.ebean.SqlRow;
import io.ebean.SqlUpdate;
import io.ebean.annotation.Transactional;
import io.ebean.config.ServerConfig;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;

import static com.linkedin.metadata.dao.utils.EBeanDAOUtils.*;
import static com.linkedin.metadata.dao.utils.SQLIndexFilterUtils.*;
import static com.linkedin.metadata.dao.utils.SQLSchemaUtils.*;


/**
 * EBeanLocalAccess provides model agnostic data access (read / write) to MySQL database.
 */
@Slf4j
public class EbeanLocalAccess<URN extends Urn> implements IEbeanLocalAccess<URN> {
  private final EbeanServer _server;
  private final Class<URN> _urnClass;
  private final String _entityType;
  private UrnPathExtractor<URN> _urnPathExtractor;
  private final EbeanLocalRelationshipWriterDAO _localRelationshipWriterDAO;
  private LocalRelationshipBuilderRegistry _localRelationshipBuilderRegistry;
  private final SchemaEvolutionManager _schemaEvolutionManager;

  // TODO confirm if the default page size is 1000 in other code context.
  private static final int DEFAULT_PAGE_SIZE = 1000;
  private static final String ASPECT_JSON_PLACEHOLDER = "__PLACEHOLDER__";
  private static final String DEFAULT_ACTOR = "urn:li:principal:UNKNOWN";

  public EbeanLocalAccess(EbeanServer server, ServerConfig serverConfig, @Nonnull Class<URN> urnClass, UrnPathExtractor<URN> urnPathExtractor) {
    _server = server;
    _urnClass = urnClass;
    _urnPathExtractor = urnPathExtractor;
    _entityType = ModelUtils.getEntityTypeFromUrnClass(_urnClass);
    _localRelationshipWriterDAO = new EbeanLocalRelationshipWriterDAO(_server);
    _schemaEvolutionManager = createSchemaEvolutionManager(serverConfig);
  }

  public void setUrnPathExtractor(@Nonnull UrnPathExtractor<URN> urnPathExtractor) {
    _urnPathExtractor = urnPathExtractor;
  }

  public void ensureSchemaUpToDate() {
    _schemaEvolutionManager.ensureSchemaUpToDate();
  }

  @Override
  @Transactional
  public <ASPECT extends RecordTemplate> int add(@Nonnull URN urn, @Nullable ASPECT newValue, @Nonnull Class<ASPECT> aspectClass,
      @Nonnull AuditStamp auditStamp) {

    final String actor = auditStamp.hasActor() ? auditStamp.getActor().toString() : DEFAULT_ACTOR;
    final String impersonator = auditStamp.hasImpersonator() ? auditStamp.getImpersonator().toString() : null;
    final boolean urnExtraction = _urnPathExtractor != null && !(_urnPathExtractor instanceof EmptyPathExtractor);

    final SqlUpdate sqlUpdate = _server.createSqlUpdate(SQLStatementUtils.createAspectUpsertSql(urn, aspectClass, urnExtraction))
        .setParameter("urn", urn.toString())
        .setParameter("lastmodifiedon", new Timestamp(System.currentTimeMillis()).toString())
        .setParameter("lastmodifiedby", actor);

    // If a non-default UrnPathExtractor is provided, the user MUST specify in their schema generation scripts
    // 'ALTER TABLE <table> ADD COLUMN a_urn JSON'.
    if (urnExtraction) {
      sqlUpdate.setParameter("a_urn", toJsonString(urn));
    }

    // newValue is null if aspect is to be soft-deleted.
    if (newValue == null) {
      /*
      TODO:
      Local relationship is derived from an aspect. If an aspect metadata is deleted, then the local relationships derived from it
      should also be invalidated. But how this invalidation process should work is still unclear. We can re-visited this part
      once we see clear use case. For now, to prevent inconsistency between entity table and local relationship table, we do not allow
      an aspect to be deleted if there's local relationship being derived from it.
       */
      if (_localRelationshipBuilderRegistry != null && _localRelationshipBuilderRegistry.isRegistered(aspectClass)) {
        throw new UnsupportedOperationException(
            String.format("Aspect %s cannot be soft-deleted because it has a local relationship builder registered.",
                aspectClass.getCanonicalName()));
      }

      return sqlUpdate.setParameter("metadata", DELETED_VALUE).execute();
    }

    // Add local relationships if builder is provided.
    addRelationships(urn, newValue, aspectClass);

    final long timestamp = auditStamp.hasTime() ? auditStamp.getTime() : System.currentTimeMillis();

    AuditedAspect auditedAspect = new AuditedAspect()
        .setAspect(RecordUtils.toJsonString(newValue))
        .setCanonicalName(aspectClass.getCanonicalName())
        .setLastmodifiedby(actor)
        .setLastmodifiedon(new Timestamp(timestamp).toString())
        .setCreatedfor(impersonator, SetMode.IGNORE_NULL);

    return sqlUpdate.setParameter("metadata", toJsonString(auditedAspect)).execute();
  }

  @Override
  public <ASPECT extends RecordTemplate> void addRelationships(@Nonnull URN urn, @Nonnull ASPECT aspect, @Nonnull Class<ASPECT> aspectClass) {
    if (_localRelationshipBuilderRegistry != null && _localRelationshipBuilderRegistry.isRegistered(aspectClass)) {
      List<BaseLocalRelationshipBuilder<ASPECT>.LocalRelationshipUpdates> localRelationshipUpdates =
          _localRelationshipBuilderRegistry.getLocalRelationshipBuilder(aspect).buildRelationships(urn, aspect);

      _localRelationshipWriterDAO.processLocalRelationshipUpdates(localRelationshipUpdates);
    }
  }

  /**
   * Construct and execute a SQL statement as follows.
   * SELECT urn, aspect1, lastmodifiedon, lastmodifiedby FROM metadata_entity_foo WHERE urn = 'urn:1' AND JSON_EXTRACT(aspect1, '$.gma_deleted') IS NULL
   * UNION ALL
   * SELECT urn, aspect2, lastmodifiedon, lastmodifiedby FROM metadata_entity_foo WHERE urn = 'urn:1' AND JSON_EXTRACT(aspect2, '$.gma_deleted') IS NULL
   * UNION ALL
   * SELECT urn, aspect1, lastmodifiedon, lastmodifiedby FROM metadata_entity_foo WHERE urn = 'urn:2' AND JSON_EXTRACT(aspect1, '$.gma_deleted') IS NULL
   * @param aspectKeys a List of keys (urn, aspect pairings) to query for
   * @param keysCount number of keys to query
   * @param position position of the key to start from
   */
  @Override
  public <ASPECT extends RecordTemplate> List<EbeanMetadataAspect> batchGetUnion(
      @Nonnull List<AspectKey<URN, ? extends RecordTemplate>> aspectKeys, int keysCount, int position) {

    final int end = Math.min(aspectKeys.size(), position + keysCount);
    final Map<Class<ASPECT>, Set<Urn>> keysToQueryMap = new HashMap<>();
    for (int index = position; index < end; index++) {
      final Urn entityUrn = aspectKeys.get(index).getUrn();
      final Class<ASPECT> aspectClass = (Class<ASPECT>) aspectKeys.get(index).getAspectClass();
      keysToQueryMap.computeIfAbsent(aspectClass, unused -> new HashSet<>()).add(entityUrn);
    }

    // each statement is for a single aspect class
    List<String> selectStatements = keysToQueryMap.entrySet().stream()
        .map(entry -> SQLStatementUtils.createAspectReadSql(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());

    // consolidate/join the results
    List<SqlRow> sqlRows = selectStatements.stream().flatMap(sql -> _server.createSqlQuery(sql).findList().stream()).collect(Collectors.toList());
    return EBeanDAOUtils.readSqlRows(sqlRows);
  }

  @Override
  public List<URN> listUrns(@Nonnull IndexFilter indexFilter, @Nullable IndexSortCriterion indexSortCriterion,
      @Nullable URN lastUrn, int pageSize) {
    SqlQuery sqlQuery = createFilterSqlQuery(indexFilter, indexSortCriterion, lastUrn, 0, pageSize);
    final List<SqlRow> sqlRows = sqlQuery.setFirstRow(0).findList();
    return sqlRows.stream().map(sqlRow -> getUrn(sqlRow.getString("urn"), _urnClass)).collect(Collectors.toList());
  }

  @Override
  public ListResult<URN> listUrns(@Nonnull IndexFilter indexFilter, @Nullable IndexSortCriterion indexSortCriterion,
      int start, int pageSize) {
    final SqlQuery sqlQuery = createFilterSqlQuery(indexFilter, indexSortCriterion, null, start, pageSize);
    final List<SqlRow> sqlRows = sqlQuery.findList();
    if (sqlRows.size() == 0) {
      final List<SqlRow> totalCountResults = createFilterSqlQuery(indexFilter, indexSortCriterion, null, 0, DEFAULT_PAGE_SIZE).findList();
      final int actualTotalCount = totalCountResults.isEmpty() ? 0 : totalCountResults.get(0).getInteger("_total_count");
      return toListResult(actualTotalCount, start, pageSize);
    }
    final List<URN> values = sqlRows.stream().map(sqlRow -> getUrn(sqlRow.getString("urn"), _urnClass)).collect(Collectors.toList());
    return toListResult(values, sqlRows, start, pageSize);
  }

  @Override
  public boolean exists(@Nonnull URN urn) {
    final String existSql = SQLStatementUtils.createExistSql(urn);
    final SqlQuery sqlQuery = _server.createSqlQuery(existSql);
    return sqlQuery.findList().size() > 0;
  }

  @Nonnull
  @Override
  public <ASPECT extends RecordTemplate> ListResult<URN> listUrns(@Nonnull Class<ASPECT> aspectClass, int start,
      int pageSize) {
    final String browseSql = SQLStatementUtils.createAspectBrowseSql(_entityType, aspectClass, start, pageSize);
    final SqlQuery sqlQuery = _server.createSqlQuery(browseSql);

    final List<SqlRow> sqlRows = sqlQuery.findList();
    if (sqlRows.size() == 0) {
      final List<SqlRow> totalCountResults = _server.createSqlQuery(
          SQLStatementUtils.createAspectBrowseSql(_entityType, aspectClass, 0, DEFAULT_PAGE_SIZE)).findList();
      final int actualTotalCount = totalCountResults.isEmpty() ? 0 : totalCountResults.get(0).getInteger("_total_count");
      return toListResult(actualTotalCount, start, pageSize);
    }
    final List<URN> values = sqlRows.stream()
        .map(sqlRow -> getUrn(sqlRow.getString("urn"), _urnClass))
        .collect(Collectors.toList());
    return toListResult(values, sqlRows, start, pageSize);
  }

  @Nonnull
  @Override
  public Map<String, Long> countAggregate(@Nonnull IndexFilter indexFilter,
      @Nonnull IndexGroupByCriterion indexGroupByCriterion) {
    final String tableName = SQLSchemaUtils.getTableName(_entityType);

    // first, check for existence of the column we want to GROUP BY
    final String groupByColumnExistsSql = SQLStatementUtils.createGroupByColumnExistsSql(tableName, indexGroupByCriterion);
    final SqlRow groupByColumnExistsResults = _server.createSqlQuery(groupByColumnExistsSql).findOne();
    if (groupByColumnExistsResults == null) {
      // if we are trying to GROUP BY the results on a column that does not exist, just return an empty map
      return Collections.emptyMap();
    }

    // now run the actual GROUP BY query
    final String groupBySql = SQLStatementUtils.createGroupBySql(tableName, indexFilter, indexGroupByCriterion);
    final SqlQuery sqlQuery = _server.createSqlQuery(groupBySql);
    final List<SqlRow> sqlRows = sqlQuery.findList();
    Map<String, Long> resultMap = new HashMap<>();
    for (SqlRow sqlRow : sqlRows) {
      final Long count = sqlRow.getLong("count");
      String value = null;
      for (Map.Entry<String, Object> entry : sqlRow.entrySet()) {
        if (!entry.getKey().equalsIgnoreCase("count")) {
          value = String.valueOf(entry.getValue());
          break;
        }
      }
      resultMap.put(value, count);
    }
    return resultMap;
  }

  /**
   * Produce {@link SqlQuery} for list urn by offset (start) and by lastUrn.
   * @param indexFilter index filter conditions
   * @param indexSortCriterion sorting criterion, default ACS
   * @param lastUrn last urn of the previous fetched page. For the first page, this should be set as NULL
   * @return SqlQuery a SQL query which can be executed by ebean server.
   */
  private SqlQuery createFilterSqlQuery(@Nonnull IndexFilter indexFilter,
      @Nullable IndexSortCriterion indexSortCriterion, @Nullable URN lastUrn, int offset, int pageSize) {
    if (indexFilter.hasCriteria() && indexFilter.getCriteria().isEmpty()) {
      throw new UnsupportedOperationException("Empty Index Filter is not supported by EbeanLocalDAO");
    }

    final String tableName = SQLSchemaUtils.getTableName(_entityType);
    StringBuilder filterSql = new StringBuilder();
    filterSql.append(SQLStatementUtils.createFilterSql(tableName, indexFilter, indexSortCriterion));

    // append last urn where condition
    if (lastUrn != null) {
      // because createFilterSql will only include a WHERE clause if there are non-urn filters, we need to make sure
      // that we add a WHERE if it wasn't added already.
      final boolean filterOnlyOnUrns = indexFilter.getCriteria().stream().allMatch(criteria -> isUrn(criteria.getAspect()));
      filterSql.append(filterOnlyOnUrns ? " WHERE " : " AND ");
      filterSql.append("urn > '");
      filterSql.append(lastUrn);
      filterSql.append("'");
    }

    if (indexSortCriterion != null) {
      filterSql.append("\n");
      filterSql.append(parseSortCriteria(indexSortCriterion));
    }

    filterSql.append(String.format(" LIMIT %d", Math.max(pageSize, 0)));
    filterSql.append(String.format(" OFFSET %d", Math.max(offset, 0)));
    return _server.createSqlQuery(filterSql.toString());
  }


  /**
   * Convert sqlRows into {@link ListResult}. This version of toListResult is used when the original SQL query
   * returned nothing, but that doesn't necessarily mean that _total_count is 0 (thought it still could be). For example:
   *
   * <p>
   * If &lt;start&gt; (e.g. 5) is greater than _total_count (e.g. 2), the SQL query will return an empty list, but _total_count
   * is not 0. If we pass in the empty list into the other toListResult method, we will not be able to get the _total_count
   * value (since that is stored within each SqlRow of that list. We must use a second query (with &lt;start&gt; = 0) to check for
   * the actual _total_count, then pass that into this toListResult method.
   * </p>
   * @param totalCount total count from ebean query execution
   * @param start starting position
   * @param pageSize number of rows in a page
   * @param <T> type of query response
   * @return {@link ListResult} which contains paging metadata information
   */
  @Nonnull
  protected <T> ListResult<T> toListResult(int totalCount, int start, int pageSize) {
    if (pageSize == 0) {
      pageSize = DEFAULT_PAGE_SIZE;
    }
    final int totalPageCount = ceilDiv(totalCount, pageSize);
    boolean hasNext;
    int nextStart;
    if (totalCount - start > 0) {
      hasNext = true;
      nextStart = start;
    } else {
      hasNext = false;
      nextStart = ListResult.INVALID_NEXT_START;
    }
    return ListResult.<T>builder()
        .values(Collections.emptyList())
        .metadata(null)
        .nextStart(nextStart)
        .havingMore(hasNext)
        .totalCount(totalCount)
        .totalPageCount(totalPageCount)
        .pageSize(pageSize)
        .build();
  }

  /**
   * Convert sqlRows into {@link ListResult}.
   * @param values a list of query response result
   * @param sqlRows list of {@link SqlRow} from ebean query execution
   * @param start starting position
   * @param pageSize number of rows in a page
   * @param <T> type of query response
   * @return {@link ListResult} which contains paging metadata information
   */
  @Nonnull
  protected <T> ListResult<T> toListResult(@Nonnull List<T> values, @Nonnull List<SqlRow> sqlRows,
      int start, int pageSize) {
    if (pageSize == 0) {
      pageSize = DEFAULT_PAGE_SIZE;
    }
    final int totalCount = sqlRows.get(0).getInteger("_total_count");
    final int totalPageCount = ceilDiv(totalCount, pageSize);
    boolean hasNext;
    int nextStart;
    if (sqlRows.size() < totalCount - start) {
      hasNext = true;
      nextStart = sqlRows.size() + start;
    } else if (sqlRows.size() == totalCount - start || totalCount == 0 || totalCount - start < 0) {
      hasNext = false;
      nextStart = ListResult.INVALID_NEXT_START;
    } else {
      throw new RuntimeException(
          String.format("Row count (%d) is more than total count of (%d) starting from offset of (%s)", sqlRows.size(),
              totalCount, start));
    }
    return ListResult.<T>builder()
        .values(values)
        .metadata(null)
        .nextStart(nextStart)
        .havingMore(hasNext)
        .totalCount(totalCount)
        .totalPageCount(totalPageCount)
        .pageSize(pageSize)
        .build();
  }

  /**
   * Given an AuditedAspect object, serialize it into a json string in a format that will be saved in DB.
   * @param auditedAspect AuditedAspect object to be serialized
   * @return A json string that can be saved to DB.
   */
  @Nonnull
  public static String toJsonString(@Nonnull final AuditedAspect auditedAspect) {
    String aspect = auditedAspect.getAspect();
    auditedAspect.setAspect(ASPECT_JSON_PLACEHOLDER);
    return RecordUtils.toJsonString(auditedAspect).replace("\"" + ASPECT_JSON_PLACEHOLDER + "\"",  aspect);
  }

  /**
   * Extract paths from urn into a map using the UrnPathExtractor, and convert this map into a JSON string so that it
   * can be used to index urn paths in the MySQL tables.
   * For example, assuming a FooUrnPathExtractor is implemented in a certain way, "urn:li:foo:(urn:li:bar,baz)" can be converted to
   * "{"/name":"foo", "/field1":"urn:li:bar", "/field1/value":"bar", "/field2":"baz"}".
   * @param urn urn
   * @return JSON string representation of the urn
   */
  @Nonnull
  private String toJsonString(@Nonnull URN urn) {
    final Map<String, Object> pathValueMap = _urnPathExtractor.extractPaths(urn);
    return JSONObject.toJSONString(pathValueMap);
  }

  /**
   * Set local relationship builder registry.
   */
  public void setLocalRelationshipBuilderRegistry(@Nullable LocalRelationshipBuilderRegistry localRelationshipBuilderRegistry) {
    _localRelationshipBuilderRegistry = localRelationshipBuilderRegistry;
  }

  @Nonnull
  private SchemaEvolutionManager createSchemaEvolutionManager(@Nonnull ServerConfig serverConfig) {
    SchemaEvolutionManager.Config config = new SchemaEvolutionManager.Config(
        serverConfig.getDataSourceConfig().getUrl(),
        serverConfig.getDataSourceConfig().getPassword(),
        serverConfig.getDataSourceConfig().getUsername());

    return new FlywaySchemaEvolutionManager(config);
  }
}
