import { InboxOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Form, Select, Typography, Upload, type UploadFile } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  importInventory,
  listWarehouses,
  type ImportResult,
  type WarehouseView,
} from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';
import { useStoreSync } from '../../hooks/useStoreSync';
import ImportResultPanel from './ImportResultPanel';

interface FormValues {
  file?: UploadFile[];
  warehouseId?: number;
}

/** antd Upload 与 Form 的标准接线：表单态存 fileList，提交时取 originFileObj。 */
function normFile(e: { fileList: UploadFile[] }): UploadFile[] {
  return e?.fileList ?? [];
}

export default function InventoryImportPage() {
  const storeId = Number(useParams().storeId);
  useStoreSync(storeId);
  const navigate = useNavigate();

  const [form] = Form.useForm<FormValues>();
  const [warehouses, setWarehouses] = useState<WarehouseView[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

  useEffect(() => {
    void listWarehouses(storeId).then(setWarehouses).catch(() => setWarehouses([]));
  }, [storeId]);

  const onFinish = async (values: FormValues) => {
    const file = values.file?.[0]?.originFileObj as File | undefined;
    if (!file) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    setResult(null);
    try {
      setResult(await importInventory(storeId, file, values.warehouseId));
      form.resetFields();
    } catch (e) {
      setSubmitError(getProblemDetailMessage(e) ?? '导入失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="初始库存导入">
      {submitError && (
        <Alert type="error" title={submitError} showIcon style={{ marginBottom: 16 }} />
      )}
      {result ? (
        <ImportResultPanel
          result={result}
          onViewBatch={() => navigate(`/stores/${storeId}/import-batches`)}
        />
      ) : (
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="file"
            valuePropName="fileList"
            getValueFromEvent={normFile}
            label="POS 商品资料工作簿（.xls / .xlsx）"
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
          <Form.Item name="warehouseId" label="入库仓库（可选，不选则仓库待分配）">
            <Select
              allowClear
              style={{ width: 320 }}
              placeholder="不指定"
              options={warehouses.map((w) => ({
                value: w.id,
                label: `${w.warehouseName}（${w.warehouseCode ?? '-'}）`,
              }))}
            />
          </Form.Item>
          <Typography.Text type="secondary">
            注意：遇到未知条码将导致整批失败（全有或全无）。
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
