<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">审核详情</h1>
        <div class="zs-page-subtitle">投稿编号 {{ submissionId }}</div>
      </div>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="14">
        <div class="zs-table-card">
          <div class="zs-panel-title">设计方案预览</div>
          <div class="zs-preview-grid">
            <div class="zs-preview-item" v-for="(asset, i) in previewAssets" :key="i">
              <img v-if="asset.url" :src="asset.url" />
              <div class="zs-preview-placeholder" v-else>资产 {{ asset.assetId }}</div>
              <span>{{ asset.label }}</span>
            </div>
            <el-empty
              v-if="!previewAssets.length"
              description="暂无预览（联调后显示方案图）"
              :image-size="80"
            />
          </div>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="zs-table-card">
          <div class="zs-panel-title">审核操作</div>
          <el-form label-position="top">
            <el-form-item label="审核意见">
              <el-input
                v-model="comment"
                type="textarea"
                :rows="4"
                placeholder="通过/退回时给出的说明（退回必填）"
              />
            </el-form-item>
            <el-form-item>
              <div class="zs-review-actions">
                <el-button class="zs-btn-primary" :loading="saving" @click="decide('APPROVE')"
                  >通过</el-button
                >
                <el-button type="warning" :loading="saving" @click="decide('CHANGES_REQUESTED')"
                  >要求修改</el-button
                >
                <el-button type="danger" :loading="saving" @click="decide('REJECT')"
                  >拒绝</el-button
                >
              </div>
            </el-form-item>
            <el-alert
              type="info"
              :closable="false"
              title="发布是独立命令"
              description="审核通过后还需在案例库中对 AI 案例执行上架；生成参考授权不随审核自动获得。"
            />
          </el-form>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'

defineOptions({ name: 'ZsReviewDetail' })
const message = useMessage()
const route = useRoute()
const submissionId = String(route.params.submissionId || route.query.id || '')

const comment = ref('')
const saving = ref(false)
const previewAssets = ref<any[]>([])

const decide = async (decision: string) => {
  if (decision === 'CHANGES_REQUESTED' && !comment.value) {
    message.error('退回时请填写修改意见')
    return
  }
  saving.value = true
  try {
    await ZsApi.reviewDecision(submissionId, { decision, comment: comment.value })
    message.success('审核决定已提交')
    history.back()
  } catch (e: any) {
    message.error(e?.msg || '提交失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    const sub = await ZsApi.getSubmission(submissionId)
    // 候选资产预览：按项目候选拉取（联调后由详情端点返回；此处以结果版本挂接）
    previewAssets.value = sub?.previewAssets || []
  } catch {}
})
</script>

<style lang="scss" scoped>
.zs-preview-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;

  .zs-preview-item {
    overflow: hidden;
    font-size: 13px;
    color: #6f6f6f;
    text-align: center;
    background: #faf6f0;
    border-radius: 8px;

    img {
      display: block;
      width: 100%;
      height: 150px;
      object-fit: cover;
    }

    .zs-preview-placeholder {
      display: flex;
      height: 150px;
      color: #b8b0a4;
      align-items: center;
      justify-content: center;
    }

    span {
      display: block;
      padding: 8px;
    }
  }
}

.zs-review-actions {
  display: flex;
  gap: 12px;
  width: 100%;
}
</style>
