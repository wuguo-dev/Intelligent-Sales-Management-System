import { describe, expect, it } from 'vitest';
import { swapStoreIdInPath } from './path';

describe('swapStoreIdInPath', () => {
  it('替换 /stores/ 段的 storeId，保留子路径', () => {
    expect(swapStoreIdInPath('/stores/1/import-batches', '2')).toBe('/stores/2/import-batches');
    expect(swapStoreIdInPath('/stores/1/imports/sales', '9')).toBe('/stores/9/imports/sales');
  });

  it('路径无 /stores/:id 段时跳到默认业务页', () => {
    expect(swapStoreIdInPath('/', '3')).toBe('/stores/3/import-batches');
    expect(swapStoreIdInPath('/login', '3')).toBe('/stores/3/import-batches');
  });
});
