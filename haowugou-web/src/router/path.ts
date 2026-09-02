/**
 * 把路径里的 /stores/<id>/ 段换成新门店；路径没有该段（如首页）时
 * 跳到默认业务页「导入批次」。
 */
export function swapStoreIdInPath(pathname: string, storeId: number): string {
  const replaced = pathname.replace(/^\/stores\/\d+(?=\/|$)/, `/stores/${storeId}`);
  return replaced === pathname ? `/stores/${storeId}/import-batches` : replaced;
}
