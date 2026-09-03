import { InboxOutlined } from '@ant-design/icons';
import { Alert, Button, Card, DatePicker, Form, Typography, Upload, type UploadFile } from 'antd';
import type { Dayjs } from 'dayjs';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { importDailySales, type ImportResult } from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';
import { useStoreSync } from '../../hooks/useStoreSync';
import ImportResultPanel from './ImportResultPanel';

interface FormValues {
  file?: UploadFile[];
  businessDate: Dayjs;
}

/** antd Upload 与 Form 的标准接线：表单态存 fileList，提交时取 originFileObj。 */
function normFile(e: { fileList: UploadFile[] }): UploadFile[] {
  return e?.fileList ?? [];
}

export default function SalesImportPage() {
  // storeId 是 URL 段的字符串：64 位主键不做 Number() 转换（会丢精度）
  const storeId = useParams().storeId ?? '';
  useStoreSync(storeId);
  const navigate = useNavigate();

  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

  const onFinish = async (values: FormValues) => {
    const file = values.file?.[0]?.originFileObj as File | undefined;
    if (!file) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    setResult(null);
    try {
      setResult(await importDailySales(storeId, file, values.businessDate.format('YYYY-MM-DD')));
      form.resetFields();
    } catch (e) {
      setSubmitError(getProblemDetailMessage(e) ?? '导入失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="每日销售导入">
      {submitError && (
        <Alert type="error" title={submitError} showIcon style={{ marginBottom: 16 }} />
      )}
      {result ? (
        <ImportResultPanel
          result={result}
          extraItems={[
            { label: '销售事实条数', value: result.salesRows ?? '—' },
            { label: '新建待完善商品', value: result.pendingProductsCreated ?? '—' },
            { label: '扣库存商品数', value: result.deductedProducts ?? '—' },
          ]}
          onViewBatch={() => navigate(`/stores/${storeId}/import-batches`)}
        />
      ) : (
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="file"
            valuePropName="fileList"
            getValueFromEvent={normFile}
            label="POS 商品销售汇总工作簿（.xls / .xlsx）"
          >
            <Upload.Dragger
              maxCount={1}
              beforeUpload={(file) => {
                const ok = /\.(xlsx?|xls)$/i.test(file.name);
                if (!ok) {
                  form.setFields([{ name: 'file', errors: ['仅支持 .xls / .xlsx 文件'] }]);
                  return Upload.LIST_IGNORE;
                }
                return false; // 由表单接管，提交时统一上传
              }}
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">点击或拖拽文件到此区域</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item
            name="businessDate"
            label="业务日期"
            rules={[{ required: true, message: '请选择业务日期' }]}
          >
            <DatePicker style={{ width: 200 }} placeholder="请选择业务日期" />
          </Form.Item>
          <Typography.Text type="secondary">
            注意：未识别供应商将按归并口径入账；未知条码自动新建「待完善」商品，销售照常入账。
          </Typography.Text>
          <Form.Item noStyle shouldUpdate>
            {() => (
              <Button
                type="primary"
                htmlType="submit"
                loading={submitting}
                disabled={!form.getFieldValue('file')?.length}
                style={{ marginTop: 16 }}
              >
                开始导入
              </Button>
            )}
          </Form.Item>
        </Form>
      )}
    </Card>
  );
}
