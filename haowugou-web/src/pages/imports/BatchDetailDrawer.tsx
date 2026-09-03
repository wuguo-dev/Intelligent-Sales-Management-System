import { Alert, Button, Descriptions, Drawer, Form, Input, Modal, Spin, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  getBatch,
  reverseBatch,
  type ImportBatchDetail,
} from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';
import { useAuthStore } from '../../stores/auth';

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

const PARSE_STATUS_COLOR: Record<string, string> = {
  INVALID: 'red',
  WARNING: 'gold',
  PENDING: 'blue',
};

interface Props {
  storeId: string;
  batchId: string | null;
  open: boolean;
  onClose: () => void;
  onReversed: () => void;
}

export default function BatchDetailDrawer({ storeId, batchId, open, onClose, onReversed }: Props) {
  const profile = useAuthStore((s) => s.profile);
  const [detail, setDetail] = useState<ImportBatchDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [problemPage, setProblemPage] = useState(0);
  const [reverseOpen, setReverseOpen] = useState(false);
  const [reversing, setReversing] = useState(false);
  const [reverseError, setReverseError] = useState<string | null>(null);
  const [reverseForm] = Form.useForm<{ reversedBy: string; reversedReason: string }>();

  const load = useCallback(async () => {
    if (batchId == null) {
      return;
    }
    setLoading(true);
    try {
      setDetail(await getBatch(storeId, batchId, problemPage, 20));
    } finally {
      setLoading(false);
    }
  }, [storeId, batchId, problemPage]);

  useEffect(() => {
    if (open) {
      setProblemPage(0);
      setReverseOpen(false);
      setReverseError(null);
      void load();
    }
  }, [open, load]);

  const openReverse = () => {
    setReverseError(null);
    reverseForm.setFieldsValue({ reversedBy: profile?.displayName ?? '', reversedReason: '' });
    setReverseOpen(true);
  };

  const onReverse = async () => {
    if (batchId == null) {
      return;
    }
    const values = await reverseForm.validateFields();
    setReversing(true);
    setReverseError(null);
    try {
      const result = await reverseBatch(storeId, batchId, values);
      setReverseOpen(false);
      void Modal.success({
        title: '批次已撤销',
        content: `回滚库存商品 ${result.restoredProducts} 个、反向流水 ${result.reversedMovements} 条。同一文件可重新上传。`,
      });
      onReversed();
    } catch (e) {
      setReverseError(getProblemDetailMessage(e) ?? '撤销失败，请稍后重试');
    } finally {
      setReversing(false);
    }
  };

  const batch = detail?.batch;

  return (
    <>
      <Drawer
        title={`批次详情 #${batchId ?? ''}`}
        width={720}
        open={open}
        onClose={onClose}
        extra={
          batch?.reversible && (
            <Button type="primary" danger onClick={openReverse}>
              撤销批次
            </Button>
          )
        }
      >
        <Spin spinning={loading}>
          {batch && detail && (
            <>
              <Descriptions column={2} bordered size="small">
                <Descriptions.Item label="类型">
                  <Tag>{TYPE_LABEL[batch.importType] ?? batch.importType}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="状态">
                  <Tag color={STATUS_COLOR[batch.status]}>{batch.status}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="数据日期">{batch.dataDate ?? '—'}</Descriptions.Item>
                <Descriptions.Item label="文件名">{batch.fileName}</Descriptions.Item>
                <Descriptions.Item label="行数" span={2}>
                  总 {batch.totalRows} / 成功 {batch.successRows} / 错误 {batch.errorRows}
                </Descriptions.Item>
                <Descriptions.Item label="操作人">{batch.operatorName}</Descriptions.Item>
                <Descriptions.Item label="上传时间">{batch.importedAt}</Descriptions.Item>
                <Descriptions.Item label="入账时间">{batch.postedAt ?? '—'}</Descriptions.Item>
                <Descriptions.Item label="撤销时间">{batch.reversedAt ?? '—'}</Descriptions.Item>
                <Descriptions.Item label="文件指纹" span={2}>
                  <Typography.Text copyable style={{ fontSize: 12 }} ellipsis>
                    {batch.fileHash}
                  </Typography.Text>
                </Descriptions.Item>
                {batch.errorMessage && (
                  <Descriptions.Item label="批次错误" span={2}>
                    <Typography.Text type="danger">{batch.errorMessage}</Typography.Text>
                  </Descriptions.Item>
                )}
                {batch.reversedBy && (
                  <>
                    <Descriptions.Item label="撤销人">{batch.reversedBy}</Descriptions.Item>
                    <Descriptions.Item label="撤销原因">{batch.reversedReason ?? '—'}</Descriptions.Item>
                  </>
                )}
              </Descriptions>

              <Typography.Title level={5} style={{ marginTop: 24 }}>
                问题行（{detail.problemRows.totalElements}）
              </Typography.Title>
              <Table
                rowKey="rowNumber"
                size="small"
                dataSource={detail.problemRows.items}
                pagination={{
                  current: detail.problemRows.page + 1,
                  pageSize: detail.problemRows.size,
                  total: detail.problemRows.totalElements,
                  onChange: (p) => setProblemPage(p - 1),
                }}
                columns={[
                  { title: 'Excel 行号', dataIndex: 'rowNumber', width: 110 },
                  { title: '条码', dataIndex: 'barcode', render: (v: string | null) => v ?? '—' },
                  {
                    title: '解析状态',
                    dataIndex: 'parseStatus',
                    width: 110,
                    render: (v: string) => <Tag color={PARSE_STATUS_COLOR[v]}>{v}</Tag>,
                  },
                  { title: '错误信息', dataIndex: 'errorMessage' },
                ]}
              />
            </>
          )}
        </Spin>
      </Drawer>

      <Modal
        title="撤销批次"
        open={reverseOpen}
        onOk={onReverse}
        onCancel={() => setReverseOpen(false)}
        confirmLoading={reversing}
        okText="确认撤销"
      >
        <Form form={reverseForm} layout="vertical">
          <Form.Item
            label="操作人"
            name="reversedBy"
            rules={[{ required: true, message: '请输入操作人' }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label="撤销原因"
            name="reversedReason"
            rules={[{ required: true, message: '请输入撤销原因' }]}
          >
            <Input.TextArea placeholder="撤销原因" rows={3} />
          </Form.Item>
          {reverseError && <Alert type="error" title={reverseError} showIcon />}
        </Form>
      </Modal>
    </>
  );
}
