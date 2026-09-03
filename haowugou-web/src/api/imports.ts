import http from './http';
import type { StoreView } from './types';

export type ImportType = 'INITIAL_INVENTORY' | 'DAILY_SALES';
export type ImportBatchStatus = 'VALIDATING' | 'POSTING' | 'POSTED' | 'REVERSED' | 'FAILED';
export type ImportResultStatus = 'POSTED' | 'FAILED';

export interface ImportBatchItem {
  batchId: string;
  importType: ImportType;
  status: ImportBatchStatus;
  dataDate: string | null;
  fileName: string;
  totalRows: number;
  successRows: number;
  errorRows: number;
  importedAt: string;
  postedAt: string | null;
  reversedAt: string | null;
  reversible: boolean;
}

export interface ImportBatchPage {
  store: StoreView;
  items: ImportBatchItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ImportBatchProblemRow {
  rowNumber: number;
  barcode: string | null;
  parseStatus: string;
  errorMessage: string;
}

export interface ImportBatchDetail {
  store: StoreView;
  batch: {
    batchId: string;
    importType: ImportType;
    status: ImportBatchStatus;
    dataDate: string | null;
    fileName: string;
    fileHash: string;
    totalRows: number;
    successRows: number;
    errorRows: number;
    errorMessage: string | null;
    operatorName: string;
    importedAt: string;
    postedAt: string | null;
    reversedAt: string | null;
    reversedBy: string | null;
    reversedReason: string | null;
    reversible: boolean;
  };
  problemRows: {
    items: ImportBatchProblemRow[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface WarehouseView {
  id: string;
  storeId: string;
  warehouseCode: string | null;
  warehouseName: string;
}

export interface RowError {
  rowNumber: number;
  barcode: string | null;
  message: string;
}

export interface ImportResult {
  batchId: string;
  status: ImportResultStatus;
  totalRows: number;
  successRows: number;
  errorRows: number;
  salesRows?: number;
  pendingProductsCreated?: number;
  deductedProducts?: number;
  errors: RowError[];
}

export interface ReverseResult {
  store: StoreView;
  batchId: string;
  importType: ImportType;
  dataDate: string | null;
  fileName: string;
  reversedMovements: number;
  restoredProducts: number;
  reversedAt: string;
  reversedBy: string;
  reversedReason: string;
}

export interface ListBatchesCriteria {
  importType?: ImportType;
  status?: ImportBatchStatus;
  dataDateFrom?: string;
  dataDateTo?: string;
  page: number;
  size: number;
}

export async function listBatches(
  storeId: string,
  criteria: ListBatchesCriteria,
): Promise<ImportBatchPage> {
  const { data } = await http.get<ImportBatchPage>(`/api/stores/${storeId}/import-batches`, {
    params: criteria,
  });
  return data;
}

export async function getBatch(
  storeId: string,
  batchId: string,
  page: number,
  size: number,
): Promise<ImportBatchDetail> {
  const { data } = await http.get<ImportBatchDetail>(
    `/api/stores/${storeId}/import-batches/${batchId}`,
    { params: { page, size } },
  );
  return data;
}

export async function reverseBatch(
  storeId: string,
  batchId: string,
  body: { reversedBy: string; reversedReason: string },
): Promise<ReverseResult> {
  const { data } = await http.post<ReverseResult>(
    `/api/stores/${storeId}/import-batches/${batchId}/reverse`,
    body,
  );
  return data;
}

/** 初始库存导入（multipart）；不手工设 Content-Type，axios 自动带 boundary。 */
export async function importInventory(
  storeId: string,
  file: File,
  warehouseId?: string,
): Promise<ImportResult> {
  const form = new FormData();
  form.append('file', file);
  if (warehouseId != null) {
    form.append('warehouseId', String(warehouseId));
  }
  const { data } = await http.post<ImportResult>(`/api/stores/${storeId}/inventory/import`, form);
  return data;
}

/** 每日销售导入（multipart）；businessDate 必填（POS 文件无日期列）。 */
export async function importDailySales(
  storeId: string,
  file: File,
  businessDate: string,
): Promise<ImportResult> {
  const form = new FormData();
  form.append('file', file);
  form.append('businessDate', businessDate);
  const { data } = await http.post<ImportResult>(`/api/stores/${storeId}/sales/import`, form);
  return data;
}

export async function listWarehouses(storeId: string): Promise<WarehouseView[]> {
  const { data } = await http.get<WarehouseView[]>(`/api/stores/${storeId}/warehouses`);
  return data;
}
