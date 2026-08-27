package com.haowugou.controller.importbatch;

import com.haowugou.application.inventoryimport.PostInitialInventoryImport;
import java.io.IOException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 初始库存导入的 HTTP 入口。
 *
 * <p>只负责 HTTP 参数绑定与响应模型转换；文件校验、解析、行级校验与过账编排统一由
 * {@link PostInitialInventoryImport} 完成。可选 {@code warehouseId} 不传时库存行
 * 仓库待分配，后续在商品编辑页面指定。
 */
@RestController
@RequestMapping("/api/stores/{storeId}")
public class InitialInventoryImportController {

    private final PostInitialInventoryImport importInventory;

    public InitialInventoryImportController(PostInitialInventoryImport importInventory) {
        this.importInventory = importInventory;
    }

    /**
     * 上传 POS 商品资料工作簿（.xls / .xlsx）导入初始库存。
     *
     * <p>响应 200 时批次可能为 FAILED（行级内容错误，全有或全无），由 {@code status} 区分；
     * 文件级错误返回 400、门店不存在返回 404、重复文件或已有有效批次返回 409。
     */
    @PostMapping("/inventory/import")
    public InitialInventoryImportResponse importInventory(
            @PathVariable long storeId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam("file") MultipartFile file) throws IOException {
        return InitialInventoryImportResponse.from(importInventory.importInventory(
                storeId,
                warehouseId,
                file.getOriginalFilename(),
                file.getBytes()));
    }
}
