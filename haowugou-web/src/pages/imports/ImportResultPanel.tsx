import { Alert, Button, Descriptions, Result, Table } from 'antd';
import type { ImportResult } from '../../api/imports';

interface Props {
  result: ImportResult;
  /** 展示额外统计项（每日销售导入）。 */
  extraItems?: { label: string; value: React.ReactNode }[];
  onViewBatch: () => void;
}

export default function ImportResultPanel({ result, extraItems, onViewBatch }: Props) {
  if (result.status === 'POSTED') {
    return (
      <Result
        status="success"
        title="导入成功"
        subTitle={`批次 #${result.batchId} 已入账`}
        extra={
          <Button type="primary" onClick={onViewBatch}>
            查看批次
          </Button>
        }
      >
        <Descriptions column={3} size="small">
          <Descriptions.Item label="原始行数">{result.totalRows}</Descriptions.Item>
          <Descriptions.Item label="成功行数">{result.successRows}</Descriptions.Item>
          {extraItems?.map((item) => (
            <Descriptions.Item key={item.label} label={item.label}>
              {item.value}
            </Descriptions.Item>
          ))}
        </Descriptions>
      </Result>
    );
  }
  return (
    <>
      <Alert
        type="error"
        showIcon
        message="整批未入账"
        description={`${result.errorRows} 行存在错误，全有或全无，请修正文件后重新上传。`}
        style={{ marginBottom: 16 }}
      />
      <Table
        rowKey="rowNumber"
        size="small"
        dataSource={result.errors}
        pagination={false}
        columns={[
          { title: 'Excel 行号', dataIndex: 'rowNumber', width: 110 },
          { title: '条码', dataIndex: 'barcode', render: (v: string | null) => v ?? '—' },
          { title: '错误原因', dataIndex: 'message' },
        ]}
      />
      {result.errorRows > result.errors.length && (
        <p style={{ marginTop: 8, color: '#8c8c8c' }}>
          仅显示前 {result.errors.length} 条，共 {result.errorRows} 条
        </p>
      )}
    </>
  );
}
