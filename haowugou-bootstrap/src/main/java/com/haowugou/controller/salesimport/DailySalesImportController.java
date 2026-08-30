package com.haowugou.controller.salesimport;

import com.haowugou.application.salesimport.PostDailySalesImport;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 每日销售导入的 HTTP 入口。
 *
 * <p>只负责 HTTP 参数绑定与响应模型转换；文件校验、解析、行级校验、销售归并与库存扣减编排
 * 统一由 {@link PostDailySalesImport} 完成。{@code businessDate} 必填——POS 导出文件没有日期列，
 * 业务日期只能由操作员随请求指定。
 */
@RestController
@RequestMapping("/api/stores/{storeId}")
public class DailySalesImportController {

    private final PostDailySalesImport importDailySales;

    public DailySalesImportController(PostDailySalesImport importDailySales) {
        this.importDailySales = importDailySales;
    }

    /**
     * 上传 POS 商品销售汇总工作簿（.xls / .xlsx）导入某日销售并扣减库存。
     *
     * <p>响应 200 时批次可能为 FAILED（行级内容错误，全有或全无），由 {@code status} 区分；
     * 文件级错误或业务日期非法返回 400、门店不存在返回 404、
     * 重复文件或该日已有有效销售批次返回 409。
     */
    @PostMapping("/sales/import")
    public DailySalesImportResponse importDailySales(
            @PathVariable long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam("file") MultipartFile file) throws IOException {
        return DailySalesImportResponse.from(importDailySales.importDailySales(
                storeId,
                businessDate,
                file.getOriginalFilename(),
                file.getBytes()));
    }
}
