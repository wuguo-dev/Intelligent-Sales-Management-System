package com.haowugou.controller;

import com.haowugou.application.inventoryimport.ActiveInitialBatchExistsException;
import com.haowugou.application.inventoryimport.DuplicateImportFileException;
import com.haowugou.application.inventoryimport.ImportWarehouseException;
import com.haowugou.application.inventoryimport.InvalidImportFileException;
import com.haowugou.application.operating.InvalidOperatingDataQueryException;
import com.haowugou.application.operating.StoreNotFoundException;
import com.haowugou.application.product.InvalidStoreProductQueryException;
import com.haowugou.application.product.StoreProductNotFoundException;
import com.haowugou.application.product.WarehouseNotInStoreException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 将可预期的应用异常转换为统一的 Problem Detail 响应。
 *
 * <p>未知编程错误不在这里兜底，以免被误报成客户端参数错误并泄漏内部信息。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(StoreNotFoundException.class)
    ProblemDetail handleStoreNotFound(StoreNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("门店不存在或未启用");
        return detail;
    }

    @ExceptionHandler(InvalidOperatingDataQueryException.class)
    ProblemDetail handleBadRequest(InvalidOperatingDataQueryException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(StoreProductNotFoundException.class)
    ProblemDetail handleStoreProductNotFound(StoreProductNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("门店商品不存在");
        return detail;
    }

    @ExceptionHandler(WarehouseNotInStoreException.class)
    ProblemDetail handleWarehouseNotInStore(WarehouseNotInStoreException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(InvalidStoreProductQueryException.class)
    ProblemDetail handleInvalidStoreProductQuery(InvalidStoreProductQueryException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(InvalidImportFileException.class)
    ProblemDetail handleInvalidImportFile(InvalidImportFileException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(ImportWarehouseException.class)
    ProblemDetail handleImportWarehouse(ImportWarehouseException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(DuplicateImportFileException.class)
    ProblemDetail handleDuplicateImportFile(DuplicateImportFileException exception) {
        return conflict(exception.getMessage());
    }

    @ExceptionHandler(ActiveInitialBatchExistsException.class)
    ProblemDetail handleActiveInitialBatch(ActiveInitialBatchExistsException exception) {
        return conflict(exception.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParameter(MissingServletRequestParameterException exception) {
        return badRequest("缺少请求参数: " + exception.getParameterName());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ProblemDetail handleMissingPart(MissingServletRequestPartException exception) {
        return badRequest("缺少上传文件: " + exception.getRequestPartName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return badRequest("上传文件超过大小限制");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleParameterTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return badRequest("请求参数格式错误: " + exception.getName());
    }

    private ProblemDetail badRequest(String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        detail.setTitle("请求参数错误");
        return detail;
    }

    private ProblemDetail conflict(String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, message);
        detail.setTitle("导入冲突");
        return detail;
    }
}
