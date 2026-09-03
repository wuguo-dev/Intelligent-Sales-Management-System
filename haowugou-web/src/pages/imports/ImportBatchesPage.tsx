import { Button, DatePicker, Form, Select, Space, Table, Tag } from 'antd';
import type { Dayjs } from 'dayjs';
import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  listBatches,
  type ImportBatchItem,
  type ImportBatchPage,
  type ImportBatchStatus,
  type ImportType,
} from '../../api/imports';
import { useStoreSync } from '../../hooks/useStoreSync';
import BatchDetailDrawer from './BatchDetailDrawer';

const STATUS_COLOR: Record<string, string> = {
  POSTED: 'green',
  REVERSED: 'default',
  FAILED: 'red',
  VALIDATING: 'blue',
  POSTING: 'gold',
};

const TYPE_LABEL: Record<string, string> = {
  INITIAL_INVENTORY: '初始库存',
  DAILY_SALES: '每日销售',
};

interface FilterValues {
  importType?: ImportType;
  status?: ImportBatchStatus;
  dateRange?: [Dayjs, Dayjs] | null;
}

export default function ImportBatchesPage() {
  // storeId 是 URL 段的字符串：64 位主键不做 Number() 转换（会丢精度）
  const storeId = useParams().storeId ?? '';
  useStoreSync(storeId);

  const [applied, setApplied] = useState<FilterValues>({});
  const [data, setData] = useState<ImportBatchPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<ImportBatchItem | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(
        await listBatches(storeId, {
          importType: applied.importType,
          status: applied.status,
          dataDateFrom: applied.dateRange?.[0]?.format('YYYY-MM-DD'),
          dataDateTo: applied.dateRange?.[1]?.format('YYYY-MM-DD'),
          page,
          size: 20,
        }),
      );
    } finally {
      setLoading(false);
    }
  }, [storeId, applied, page]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <>
      <Form<FilterValues>
        layout="inline"
        style={{ marginBottom: 16 }}
        onFinish={(values) => {
          setApplied(values);
          setPage(0);
        }}
      >
        <Form.Item name="importType" label="导入类型">
          <Select
            allowClear
            style={{ width: 140 }}
            placeholder="全部"
            options={[
              { value: 'INITIAL_INVENTORY', label: '初始库存' },
              { value: 'DAILY_SALES', label: '每日销售' },
            ]}
          />
        </Form.Item>
        <Form.Item name="status" label="状态">
          <Select
            allowClear
            style={{ width: 140 }}
            placeholder="全部"
            options={['VALIDATING', 'POSTING', 'POSTED', 'REVERSED', 'FAILED'].map((s) => ({
              value: s,
              label: s,
            }))}
          />
        </Form.Item>
        <Form.Item name="dateRange" label="数据日期">
          <DatePicker.RangePicker />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              查 询
            </Button>
            <Button
              onClick={() => {
                setApplied({});
                setPage(0);
              }}
            >
              重 置
            </Button>
          </Space>
        </Form.Item>
      </Form>

      <Table<ImportBatchItem>
        rowKey="batchId"
        loading={loading}
        dataSource={data?.items ?? []}
        onRow={(record) => ({ onClick: () => setSelected(record), style: { cursor: 'pointer' } })}
        pagination={{
          current: (data?.page ?? 0) + 1,
          pageSize: data?.size ?? 20,
          total: data?.totalElements ?? 0,
          onChange: (p) => setPage(p - 1),
        }}
        columns={[
          { title: '批次ID', dataIndex: 'batchId', width: 90 },
          {
            title: '类型',
            dataIndex: 'importType',
            width: 110,
            render: (v: string) => <Tag>{TYPE_LABEL[v] ?? v}</Tag>,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 120,
            render: (v: string) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
          },
          {
            title: '数据日期',
            dataIndex: 'dataDate',
            width: 110,
            render: (v: string | null) => v ?? '—',
          },
          { title: '文件名', dataIndex: 'fileName', ellipsis: true },
          {
            title: '行数(成功/总)',
            width: 130,
            render: (_, r) =>
              r.errorRows > 0 ? (
                <span>
                  {r.successRows}/{r.totalRows}{' '}
                  <span style={{ color: '#cf1322' }}>错误{r.errorRows}</span>
                </span>
              ) : (
                `${r.successRows}/${r.totalRows}`
              ),
          },
          { title: '上传时间', dataIndex: 'importedAt', width: 170 },
          {
            title: '撤销时间',
            dataIndex: 'reversedAt',
            width: 170,
            render: (v: string | null) => v ?? '—',
          },
          {
            title: '操作',
            width: 90,
            render: () => (
              <Button type="link" size="small">
                详情
              </Button>
            ),
          },
        ]}
      />

      <BatchDetailDrawer
        storeId={storeId}
        batchId={selected?.batchId ?? null}
        open={selected != null}
        onClose={() => setSelected(null)}
        onReversed={() => {
          setSelected(null);
          void load();
        }}
      />
    </>
  );
}
