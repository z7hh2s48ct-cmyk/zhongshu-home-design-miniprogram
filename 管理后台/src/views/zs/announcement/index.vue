<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">运营公告</h1>
        <div class="zs-page-subtitle"
          >向全部激活用户发送小程序站内消息；用户消息中心与未读角标即时生效</div
        >
      </div>
    </div>

    <div class="zs-table-card zs-form">
      <el-alert
        v-if="!canSend"
        title="没有公告发送权限，请联系管理员授权"
        type="warning"
        :closable="false"
      />
      <template v-else>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="公告将送达全部激活用户（上限 2 万），发送后不可撤回；请确认内容无误后发送。"
          style="margin-bottom: 16px"
        />
        <el-form label-width="80px">
          <el-form-item label="标题" required>
            <el-input
              v-model="form.title"
              maxlength="128"
              show-word-limit
              placeholder="如：系统升级通知（≤128 字）"
              style="max-width: 560px"
            />
          </el-form-item>
          <el-form-item label="内容" required>
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="6"
              maxlength="1024"
              show-word-limit
              placeholder="公告正文（≤1024 字），将完整展示在用户消息中心"
              style="max-width: 720px"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              class="zs-btn-primary"
              :loading="sending"
              :disabled="!form.title.trim() || !form.content.trim()"
              @click="send"
              >发送公告</el-button
            >
          </el-form-item>
        </el-form>
        <el-alert
          v-if="result"
          type="success"
          :closable="false"
          :title="`已发送：本次送达 ${result.recipients} 个激活账号（发送记录见审计事件）。`"
        />
      </template>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { checkPermi } from '@/utils/permission'
import { ElMessageBox } from 'element-plus'

defineOptions({ name: 'ZsAnnouncement' })
const message = useMessage()

const canSend = computed(() => checkPermi(['design:announcement:send']))
const sending = ref(false)
const result = ref<{ recipients: number } | null>(null)
const form = reactive({ title: '', content: '' })

const send = async () => {
  if (sending.value) return
  try {
    await ElMessageBox.confirm(
      `确认向全部激活用户发送公告「${form.title.trim()}」？发送后不可撤回。`,
      '发送确认',
      {
        type: 'warning',
        confirmButtonText: '确认发送',
        cancelButtonText: '再检查一下'
      }
    )
  } catch {
    return
  }
  sending.value = true
  try {
    result.value = await ZsApi.createAnnouncement({
      title: form.title.trim(),
      content: form.content.trim()
    })
    message.success('公告已发送')
    form.title = ''
    form.content = ''
  } catch (e: any) {
    message.error(e?.msg || '发送失败，请重试')
  } finally {
    sending.value = false
  }
}
</script>

<style lang="scss" scoped>
.zs-form {
  max-width: 780px;
}
</style>
