package com.haowugou.infrastructure.persistence.importbatch;

import com.haowugou.domain.importbatch.ImportBatchQueryCriteria;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 批次查询与撤销的原始 MyBatis Mapper，两条导入链路共用。
 *
 * <p>全部语句按 {@code storeId} 隔离。{@code import_raw_row} 的外键只有 {@code batch_id}，
 * 所以查原始行必须 join {@code import_batch} 并带 {@code store_id}，否则可跨门店读到
 * 别人的批次（架构规范 §9）。
 */
@Mapper
public interface ImportBatchAdminMapper {

    /** 符合筛选条件的批次总数。 */
    long countBatches(@Param("criteria") ImportBatchQueryCriteria criteria);

    /** 当前页批次，按导入时间倒序、主键倒序（同毫秒导入时结果稳定）。 */
    List<ImportBatchSummaryRow> findBatchPage(
            @Param("criteria") ImportBatchQueryCriteria criteria,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 批次详情；批次不属于该门店时返回 null。 */
    ImportBatchSummaryRow findBatchDetail(
            @Param("storeId") long storeId,
            @Param("batchId") long batchId);

    /** 该批次问题行（INVALID/WARNING）总数。 */
    long countProblemRows(@Param("storeId") long storeId, @Param("batchId") long batchId);

    /** 当前页问题行，按行号升序。 */
    List<ImportProblemRowObject> findProblemRowPage(
            @Param("storeId") long storeId,
            @Param("batchId") long batchId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /**
     * 读该批次的全部原流水，按主键升序（写入顺序），用于推导反向量与余额链。
     *
     * <p>排除已经是 REVERSAL 的行：撤销不可再撤销，取到它们会把已冲平的量再冲一次。
     */
    List<OriginalMovementRow> findOriginalMovements(
            @Param("storeId") long storeId,
            @Param("batchId") long batchId);

    /** 读当前库存余额，作为反向流水余额链的起点。 */
    List<InventoryQuantityRow> findCurrentQuantities(
            @Param("storeId") long storeId,
            @Param("productIds") List<Long> productIds);

    /** 库存回滚：按有符号净量累加，不覆盖仓库分配。 */
    int applyInventoryDeltas(
            @Param("storeId") long storeId,
            @Param("rows") List<InventoryDeltaRow> rows);

    /** 批量插入 REVERSAL 流水，每条带 {@code reversal_of_id}。 */
    int insertReversalMovements(
            @Param("storeId") long storeId,
            @Param("batchId") long batchId,
            @Param("rows") List<ReversalMovementRow> rows);

    /**
     * 批次翻 REVERSED 并写审计字段。
     *
     * <p>{@code WHERE status = 'POSTED'} 是并发下的唯一判据：返回 0 表示已被别的请求撤销。
     */
    int markReversed(
            @Param("storeId") long storeId,
            @Param("batchId") long batchId,
            @Param("reversedBy") String reversedBy,
            @Param("reversedReason") String reversedReason,
            @Param("reversedAt") LocalDateTime reversedAt);
}
