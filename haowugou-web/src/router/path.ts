/**
 * 把路径里的 /stores/<id>/ 段换成新门店；路径没有该段（如首页）时
 * 跳到默认业务页「导入批次」。storeId 是字符串：64 位主键经 Number()
 * 会丢精度（JSON.parse 层同理），全程不透传数值。
 */
export function swapStoreIdInPath(pathname: string, storeId: string): string {
  const replaced = pathname.replace(/^\/stores\/\d+(?=\/|$)/, `/stores/${storeId}`);
  return replaced === pathname ? `/stores/${storeId}/import-batches` : replaced;
}
