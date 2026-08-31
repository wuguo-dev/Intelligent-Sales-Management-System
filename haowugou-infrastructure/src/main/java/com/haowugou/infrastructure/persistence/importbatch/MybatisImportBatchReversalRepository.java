package com.haowugou.infrastructure.persistence.importbatch;

import com.haowugou.domain.importbatch.ImportBatchReversal;
import com.haowugou.domain.importbatch.ImportBatchReversalResult;
import com.haowugou.domain.importbatch.ImportBatchReversalRepository;
import com.haowugou.domain.importbatch.ImportType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 导入批次撤销的 MyBatis 实现。
 *
 * <p>单事务顺序：翻批次状态 → 读原流水 → 读当前余额 → 回滚库存 → 写反向流水。
 *
 * <p>状态先翻是有意的：{@code UPDATE ... WHERE status = 'POSTED'} 同时充当乐观锁与行锁，
 * 影响 0 行说明批次已被并发请求撤销，直接返回空且不写任何流水。反过来先读后写会留下
 * 两个请求都读到 POSTED 的窗口，双份反向流水会把余额链算错一倍
 * （{@code uk_inventory_movement_reversal} 能拦住重复冲销，但那是以约束冲突而非干净拒绝收场）。
 *
 * <p>反向量与余额链都在这里算：{@code balance_before} 必须是数据库里的当前值，
 * 应用层拿不到也不该拿。日志只记批次元信息与规模。
 */
@Repository
public class MybatisImportBatchReversalRepository implements ImportBatchReversalRepository {

    private static final Logger log =
            LoggerFactory.getLogger(MybatisImportBatchReversalRepository.class);

    private final ImportBatchAdminMapper mapper;

    public MybatisImportBatchReversalRepository(ImportBatchAdminMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Optional<ImportBatchReversalResult> reverse(ImportBatchReversal reversal) {
        long storeId = reversal.storeId();
        long batchId = reversal.batchId();
        LocalDateTime reversedAt = LocalDateTime.now();

        int flipped = mapper.markReversed(
                storeId, batchId, reversal.reversedBy(), reversal.reversedReason(), reversedAt);
        if (flipped == 0) {
            log.info("撤销未执行：批次已不是 POSTED batchId={} storeId={}", batchId, storeId);
            return Optional.empty();
        }

        ImportBatchSummaryRow batch = mapper.findBatchDetail(storeId, batchId);
        List<OriginalMovementRow> originals = mapper.findOriginalMovements(storeId, batchId);

        List<InventoryDeltaRow> deltas = toDeltas(originals);
        if (!deltas.isEmpty()) {
            List<ReversalMovementRow> movements = toReversalMovements(
                    originals, balancesBefore(storeId, deltas));
            mapper.applyInventoryDeltas(storeId, deltas);
            mapper.insertReversalMovements(storeId, batchId, movements);
        }

        log.info("批次撤销完成 batchId={} storeId={} 类型={} 反向流水={} 回滚商品={} 操作人={}",
                batchId, storeId, batch.getImportType(), originals.size(), deltas.size(),
                reversal.reversedBy());
        return Optional.of(new ImportBatchReversalResult(
                batchId,
                ImportType.valueOf(batch.getImportType()),
                originals.size(),
                deltas.size(),
                reversedAt));
    }

    /**
     * 按商品归并出库存净增量：同一批次里同一商品可能有多条流水，
     * 而 {@code applyInventoryDeltas} 的 {@code CASE product_id} 每个商品只能出现一次。
     */
    private List<InventoryDeltaRow> toDeltas(List<OriginalMovementRow> originals) {
        Map<Long, BigDecimal> byProduct = new LinkedHashMap<>();
        for (OriginalMovementRow original : originals) {
            byProduct.merge(
                    original.getProductId(), original.getQuantityChange().negate(), BigDecimal::add);
        }
        List<InventoryDeltaRow> deltas = new ArrayList<>(byProduct.size());
        byProduct.forEach((productId, quantityDelta) -> {
            InventoryDeltaRow delta = new InventoryDeltaRow();
            delta.setProductId(productId);
            delta.setQuantityDelta(quantityDelta);
            deltas.add(delta);
        });
        return deltas;
    }

    private Map<Long, BigDecimal> balancesBefore(long storeId, List<InventoryDeltaRow> deltas) {
        List<Long> productIds = deltas.stream().map(InventoryDeltaRow::getProductId).toList();
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        for (InventoryQuantityRow row : mapper.findCurrentQuantities(storeId, productIds)) {
            balances.put(row.getProductId(), row.getCurrentQuantity());
        }
        return balances;
    }

    /**
     * 每条原流水配一条反向流水（{@code uk_inventory_movement_reversal} 要求 1:1）。
     *
     * <p>同一商品多条时按写入顺序串余额链：上一条的 {@code balance_after} 就是下一条的
     * {@code balance_before}，链尾等于回滚后的库存，满足 {@code chk_inventory_movement_balance}。
     * {@code business_date} 沿用原流水，撤销不改变业务归属日期。
     */
    private List<ReversalMovementRow> toReversalMovements(
            List<OriginalMovementRow> originals,
            Map<Long, BigDecimal> balancesBefore) {
        Map<Long, BigDecimal> running = new LinkedHashMap<>(balancesBefore);
        List<ReversalMovementRow> movements = new ArrayList<>(originals.size());
        for (OriginalMovementRow original : originals) {
            long productId = original.getProductId();
            BigDecimal quantityChange = original.getQuantityChange().negate();
            BigDecimal before = running.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal after = before.add(quantityChange);
            running.put(productId, after);

            ReversalMovementRow movement = new ReversalMovementRow();
            movement.setProductId(productId);
            movement.setBusinessDate(original.getBusinessDate());
            movement.setQuantityChange(quantityChange);
            movement.setBalanceBefore(before);
            movement.setBalanceAfter(after);
            movement.setReversalOfId(original.getMovementId());
            movements.add(movement);
        }
        return movements;
    }
}
