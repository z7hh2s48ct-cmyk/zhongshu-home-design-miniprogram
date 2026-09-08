<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">批量生成授权码</h1>
        <div class="zs-page-subtitle"
          >完整明文仅一次交付：INLINE 在创建响应内返回 / TICKET 通过一次性票据下载</div
        >
      </div>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <div class="zs-table-card zs-form">
      <el-form label-width="130px" label-position="left">
        <el-form-item label="生成数量" required>
          <el-input-number v-model="form.quantity" :min="1" :max="10000" />
        </el-form-item>
        <el-form-item label="交付方式" required>
          <el-radio-group v-model="form.deliveryMode">
            <el-radio value="INLINE">INLINE：创建后立即显示明文（仅此一次）</el-radio>
            <el-radio value="TICKET">TICKET：生成一次性下载票据</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期（天）">
          <el-input-number
            v-model="form.validityDays"
            :min="1"
            :max="3650"
            placeholder="留空=长期有效"
          />
        </el-form-item>
        <el-form-item label="用途备注">
          <el-input
            v-model="form.purposeNote"
            placeholder="如：渠道A体验 / 核心客户"
            maxlength="100"
            style="max-width: 420px"
          />
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" :loading="creating" @click="create">生成批次</el-button>
        </el-form-item>
      </el-form>

      <!-- INLINE 明文结果 -->
      <div v-if="inlineCodes.length" class="zs-codes">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="以下完整明文仅此一次显示，关闭页面后无法再次获取，请立即离线保存！"
        />
        <div class="zs-codes-list">
          <code v-for="c in inlineCodes" :key="c">{{ c }}</code>
        </div>
        <el-button @click="copyAll">复制全部</el-button>
      </div>

      <!-- TICKET 结果 -->
      <div v-if="ticket" class="zs-codes">
        <el-alert
          type="success"
          :closable="false"
          show-icon
          title="交付票据已生成（10 分钟内有效、单次消费）"
        />
        <div class="zs-ticket-row">
          <code>{{ ticket }}</code>
          <el-button size="small" @click="downloadByTicket">下载加密文件</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'

defineOptions({ name: 'ZsAccessCodeBatch' })
const message = useMessage()

const creating = ref(false)
const inlineCodes = ref<string[]>([])
const ticket = ref('')
const form = reactive({
  quantity: 100,
  deliveryMode: 'INLINE',
  validityDays: 365,
  purposeNote: ''
})

let lastBatchId: string = ''
const create = async () => {
  creating.value = true
  inlineCodes.value = []
  ticket.value = ''
  try {
    const res = await ZsApi.createAccessCodeBatch({
      quantity: form.quantity,
      deliveryMode: form.deliveryMode,
      validityDays: form.validityDays || undefined,
      purposeNote: form.purposeNote || undefined
    })
    lastBatchId = res?.id
    if (form.deliveryMode === 'INLINE') {
      inlineCodes.value = res?.oneTimeCodes || []
      if (!inlineCodes.value.length) message.warning('未返回明文（请检查交付方式）')
    } else {
      const t = await ZsApi.createDeliveryTicket(res?.id)
      ticket.value = t?.ticket
    }
  } catch (e: any) {
    message.error(e?.msg || '生成失败')
  } finally {
    creating.value = false
  }
}

const copyAll = async () => {
  await navigator.clipboard.writeText(inlineCodes.value.join('\n'))
  message.success('已复制')
}

const downloadByTicket = async () => {
  const res = await ZsApi.exportByTicket(lastBatchId, ticket.value)
  const codes = res?.codes || []
  const blob = new Blob([codes.join('\n')], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `授权码批次-${lastBatchId}.txt`
  a.click()
  URL.revokeObjectURL(url)
  message.success('已下载，票据已作废')
}
</script>

<style lang="scss" scoped>
.zs-form {
  max-width: 760px;
}

.zs-codes {
  margin-top: 24px;

  .zs-codes-list {
    max-height: 300px;
    padding: 16px;
    margin: 12px 0;
    overflow: auto;
    font-size: 13px;
    line-height: 1.9;
    background: #faf6f0;
    border-radius: 8px;

    code {
      display: block;
      color: #714320;
    }
  }

  .zs-ticket-row {
    display: flex;
    margin-top: 12px;
    align-items: center;
    gap: 12px;

    code {
      padding: 8px 14px;
      word-break: break-all;
      background: #faf6f0;
      border-radius: 6px;
    }
  }
}
</style>
