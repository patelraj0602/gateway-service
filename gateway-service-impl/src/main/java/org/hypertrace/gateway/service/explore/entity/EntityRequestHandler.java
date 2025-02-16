package org.hypertrace.gateway.service.explore.entity;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hypertrace.gateway.service.common.AttributeMetadataProvider;
import org.hypertrace.gateway.service.common.datafetcher.QueryServiceEntityFetcher;
import org.hypertrace.gateway.service.common.util.DataCollectionUtil;
import org.hypertrace.gateway.service.common.util.QueryServiceClient;
import org.hypertrace.gateway.service.entity.config.EntityIdColumnsConfigs;
import org.hypertrace.gateway.service.explore.ExploreRequestContext;
import org.hypertrace.gateway.service.explore.RequestHandler;
import org.hypertrace.gateway.service.explore.RowComparator;
import org.hypertrace.gateway.service.v1.common.OrderByExpression;
import org.hypertrace.gateway.service.v1.explore.EntityOption;
import org.hypertrace.gateway.service.v1.explore.ExploreRequest;
import org.hypertrace.gateway.service.v1.explore.ExploreResponse;

/**
 * {@link EntityRequestHandler} is currently used only when the selections, group bys and filters
 * are on EDS. Can be extended later to support multiple sources. Only needed, when there is a group
 * by on the request, else can directly use {@link
 * org.hypertrace.gateway.service.v1.entity.EntitiesRequest}
 *
 * <p>Currently,
 *
 * <ul>
 *   <li>Query to {@link
 *       org.hypertrace.gateway.service.common.datafetcher.QueryServiceEntityFetcher} with the time
 *       filter to get set of entity ids. Can be extended to support QS filters
 *   <li>Query to {@link EntityServiceEntityFetcher} with selections, group bys, and filters with an
 *       IN clause on entity ids
 * </ul>
 */
public class EntityRequestHandler extends RequestHandler {
  private final EntityServiceEntityFetcher entityServiceEntityFetcher;
  private final AttributeMetadataProvider attributeMetadataProvider;
  private final QueryServiceEntityFetcher queryServiceEntityFetcher;

  public EntityRequestHandler(
      AttributeMetadataProvider attributeMetadataProvider,
      EntityIdColumnsConfigs entityIdColumnsConfigs,
      QueryServiceClient queryServiceClient,
      QueryServiceEntityFetcher queryServiceEntityFetcher,
      EntityServiceEntityFetcher entityServiceEntityFetcher) {
    super(
        queryServiceClient,
        attributeMetadataProvider,
        entityIdColumnsConfigs,
        queryServiceEntityFetcher,
        entityServiceEntityFetcher);
    this.attributeMetadataProvider = attributeMetadataProvider;
    this.entityServiceEntityFetcher = entityServiceEntityFetcher;
    this.queryServiceEntityFetcher = queryServiceEntityFetcher;
  }

  @Override
  public ExploreResponse.Builder handleRequest(
      ExploreRequestContext requestContext, ExploreRequest exploreRequest) {
    // Track if we have Group By so we can determine if we need to do Order By, Limit and Offset
    // ourselves.
    if (!exploreRequest.getGroupByList().isEmpty()) {
      requestContext.setHasGroupBy(true);
    }

    ExploreResponse.Builder builder = ExploreResponse.newBuilder();
    Set<String> entityIds = new HashSet<>();
    Optional<EntityOption> maybeEntityOption = getEntityOption(exploreRequest);
    if (requestOnLiveEntities(maybeEntityOption)) {
      entityIds.addAll(getEntityIdsInTimeRangeFromQueryService(requestContext, exploreRequest));
      if (entityIds.isEmpty()) {
        return builder;
      }
    }

    builder.addAllRow(
        entityServiceEntityFetcher.getResults(requestContext, exploreRequest, entityIds));

    // If there's a Group By in the request, we need to do the sorting and pagination ourselves.
    if (requestContext.hasGroupBy()) {
      sortAndPaginatePostProcess(
          builder,
          requestContext.getOrderByExpressions(),
          requestContext.getRowLimitBeforeRest(),
          requestContext.getOffset());
    }

    if (requestContext.hasGroupBy() && requestContext.getIncludeRestGroup()) {
      getTheRestGroupRequestHandler()
          .getRowsForTheRestGroup(requestContext, exploreRequest, builder);
    }

    return builder;
  }

  @Override
  public void sortAndPaginatePostProcess(
      ExploreResponse.Builder builder,
      List<OrderByExpression> orderByExpressions,
      int limit,
      int offset) {
    List<org.hypertrace.gateway.service.v1.common.Row.Builder> rowBuilders =
        builder.getRowBuilderList();

    List<org.hypertrace.gateway.service.v1.common.Row.Builder> sortedRowBuilders =
        sortAndPaginateRowBuilders(rowBuilders, orderByExpressions, limit, offset);

    builder.clearRow();
    sortedRowBuilders.forEach(builder::addRow);
  }

  protected List<org.hypertrace.gateway.service.v1.common.Row.Builder> sortAndPaginateRowBuilders(
      List<org.hypertrace.gateway.service.v1.common.Row.Builder> rowBuilders,
      List<OrderByExpression> orderByExpressions,
      int limit,
      int offset) {
    RowComparator rowComparator = new RowComparator(orderByExpressions);

    return DataCollectionUtil.limitAndSort(
        rowBuilders.stream(), limit, offset, orderByExpressions.size(), rowComparator);
  }

  private boolean requestOnLiveEntities(Optional<EntityOption> entityOption) {
    if (entityOption.isEmpty()) {
      return true;
    }
    return !entityOption.get().getIncludeNonLiveEntities();
  }

  private Optional<EntityOption> getEntityOption(ExploreRequest exploreRequest) {
    if (!exploreRequest.hasContextOption()) {
      return Optional.empty();
    }
    return exploreRequest.getContextOption().hasEntityOption()
        ? Optional.of(exploreRequest.getContextOption().getEntityOption())
        : Optional.empty();
  }
}
