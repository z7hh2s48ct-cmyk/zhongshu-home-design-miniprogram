<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">审核详情</h1>
        <div class="zs-page-subtitle">投稿编号 {{ submissionId }}</div>
      </div>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重新读取</el-button>
    <el-descriptions v-if="submission" :column="2" border class="mb-16px">
      <el-descriptions-item label="审核状态">{{
        statusText[submission.status] || submission.status
      }}</el-descriptions-item>
      <el-descriptions-item label="当前轮次">{{ submission.currentRound }}</el-descriptions-item>
      <el-descriptions-item label="投稿用户">{{ submission.userId }}</el-descriptions-item>
      <el-descriptions-item label="提交时间">{{
        fmtTime(submission.submittedAt)
      }}</el-descriptions-item>
      <el-descriptions-item label="公开展示授权">
        <span
          class="zs-tag"
          :class="submission.publicDisplayGranted ? 'zs-tag--green' : 'zs-tag--red'"
          >{{ submission.publicDisplayGranted ? '已授权' : '未授权' }}</span
        >
      </el-descriptions-item>
      <el-descriptions-item label="生成参考授权">
        <span
          class="zs-tag"
          :class="submission.generationReferenceGranted ? 'zs-tag--green' : 'zs-tag--gray'"
          >{{ submission.generationReferenceGranted ? '已授权' : '未授权' }}</span
        >
      </el-descriptions-item>
      <el-descriptions-item label="发布状态">{{
        submission.publicationStatus || '尚未发布'
      }}</el-descriptions-item>
      <el-descriptions-item label="审核意见">{{
        submission.reviewComment || '暂无审核意见'
      }}</el-descriptions-item>
      <el-descriptions-item label="投稿说明" :span="2">{{
        submission.note || '未填写'
      }}</el-descriptions-item>
    </el-descriptions>
    <div v-if="history.length" class="zs-table-card mb-16px">
      <div class="zs-panel-title">历轮审核记录（最新在前）</div>
      <el-table :data="history" size="small" stripe>
        <el-table-column label="轮次" prop="round_no" width="70" />
        <el-table-column label="决定" width="110">
          <template #default="{ row }">{{ decisionText(row.decision) }}</template>
        </el-table-column>
        <el-table-column label="意见" prop="comment" min-width="200" show-overflow-tooltip />
        <el-table-column label="审核人" prop="reviewer_user_id" width="120" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.create_time) }}</template>
        </el-table-column>
      </el-table>
    </div>
    <el-row :gutter="16" v-loading="loading">
      <el-col :xs="24" :md="14">
        <div class="zs-table-card">
          <div class="zs-panel-title">设计方案预览</div>
          <div class="zs-preview-grid">
            <div class="zs-preview-item" v-for="(asset, i) in previewAssets" :key="i">
              <el-image
                v-if="asset.url"
                :src="asset.url"
                fit="contain"
                :preview-src-list="[asset.url]"
                preview-teleported
                :alt="asset.label"
              />
              <div class="zs-preview-placeholder" v-else>
                {{ asset.error || '正在读取图纸…' }}
                <el-button v-if="asset.error" link @click="loadAsset(asset)">重试</el-button>
              </div>
              <span>{{ asset.label }}</span>
            </div>
            <el-empty
              v-if="!previewAssets.length"
              description="该冻结版本没有可用图纸，请核对投稿资料"
              :image-size="80"
            />
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :md="10">
        <div class="zs-table-card">
          <div class="zs-panel-title">审核操作</div>
          <el-form v-if="canReview" label-position="top">
            <el-form-item label="审核意见">
              <el-input
                v-model="comment"
                type="textarea"
                :rows="4"
                maxlength="1024"
                show-word-limit
                placeholder="通过/退回时给出的说明（退回必填）"
              />
            </el-form-item>
            <el-form-item>
              <div class="zs-review-actions">
                <el-button
                  v-if="allows('APPROVE')"
                  class="zs-btn-primary"
                  :disabled="saving"
                  :loading="saving"
                  @click="decide('APPROVE')"
                  >通过</el-button
                >
                <el-button
                  v-if="allows('CHANGES_REQUESTED')"
                  type="warning"
                  :disabled="saving"
                  :loading="saving"
                  @click="decide('CHANGES_REQUESTED')"
                  >要求修改</el-button
                >
                <el-button
                  v-if="allows('REJECT')"
                  type="danger"
                  :disabled="saving"
                  :loading="saving"
                  @click="decide('REJECT')"
                  >拒绝</el-button
                >
              </div>
            </el-form-item>
          </el-form>
          <el-button
            v-if="allows('PUBLISH')"
            class="zs-btn-primary mb-16px"
            :loading="saving"
            :disabled="saving"
            @click="publish"
            >发布至户型库</el-button
          >
          <el-alert
            type="info"
            :closable="false"
            title="发布是独立命令"
            description="通过后请在此单独点击发布；只发布投稿冻结版本。生成参考许可不随审核自动授予。已审结轮次不可再次审核。"
          />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'
import { ElMessageBox } from 'element-plus'

defineOptions({ name: 'ZsReviewDetail' })
const message = useMessage()
const route = useRoute()
const submissionId = String(route.params.submissionId || route.query.id || '')

const comment = ref('')
const saving = ref(false)
const previewAssets = ref<any[]>([])
const submission = ref<any>(null)
const history = ref<any[]>([])
const loading = ref(false)
const loadError = ref('')
const statusText = {
  SUBMITTED: '待审核',
  RESUBMITTED: '重提待审核',
  IN_REVIEW: '审核中',
  APPROVED: '已通过',
  CHANGES_REQUESTED: '待用户修改',
  REJECTED: '已拒绝'
}
const decisionText = (d: string) =>
  ({ APPROVE: '通过', CHANGES_REQUESTED: '退回修改', REJECT: '拒绝' })[d] || d || '未决'
const allows = (action: string) => submission.value?.allowedActions?.includes(action)
const canReview = computed(() => ['APPROVE', 'CHANGES_REQUESTED', 'REJECT'].some(allows))
let generation = 0
const clearImages = () => {
  previewAssets.value.forEach((a) => {
    if (a.url) URL.revokeObjectURL(a.url)
  })
}
const loadAsset = async (asset: any) => {
  const seq = generation
  asset.error = ''
  try {
    const blob = await ZsApi.getSubmissionAsset(submissionId, asset.assetId)
    if (seq !== generation) return
    if (!(blob instanceof Blob) || !blob.type.startsWith('image/')) throw Error('图纸格式不可预览')
    if (asset.url) URL.revokeObjectURL(asset.url)
    asset.url = URL.createObjectURL(blob)
  } catch (error: any) {
    if (seq === generation) asset.error = error?.msg || error?.message || '图纸读取失败'
  }
}
const load = async () => {
  const seq = ++generation
  clearImages()
  previewAssets.value = []
  submission.value = null
  loading.value = true
  loadError.value = ''
  try {
    const sub = await ZsApi.getSubmission(submissionId)
    if (seq !== generation) return
    submission.value = sub
    history.value = sub.reviewHistory || []
    previewAssets.value = (sub.previewAssets || []).map((a) => ({ ...a, url: '', error: '' }))
    await Promise.all(previewAssets.value.map(loadAsset))
  } catch (e: any) {
    if (seq === generation) loadError.value = e?.msg || '投稿读取失败，请重试'
  } finally {
    if (seq === generation) loading.value = false
  }
}

const decide = async (decision: string) => {
  if (saving.value || !allows(decision)) return
  if (decision === 'CHANGES_REQUESTED' && !comment.value.trim()) {
    message.error('退回时请填写修改意见')
    return
  }
  saving.value = true
  try {
    await ZsApi.reviewDecision(submissionId, { decision, comment: comment.value })
    message.success('审核决定已提交')
    comment.value = ''
    await load()
  } catch (e: any) {
    message.error(e?.msg || '提交失败')
  } finally {
    saving.value = false
  }
}

const publish = async () => {
  if (saving.value || !allows('PUBLISH')) return
  // D2-4 发布原因必填：与预算/报价发布的留痕强度一致（对业主可见级操作）
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      '确认将该投稿的冻结图纸发布至户型库？发布原因将写入审计（必填）。',
      '发布至户型库',
      {
        type: 'warning',
        confirmButtonText: '确认发布',
        cancelButtonText: '取消',
        inputPlaceholder: '发布原因（必填）',
        inputValidator: (v: string) => (v?.trim() ? true : '发布原因必填')
      }
    )
    reason = value.trim()
  } catch {
    return
  }
  saving.value = true
  try {
    await ZsApi.publishSubmission(submissionId, reason)
    message.success('已发布至户型库')
    await load()
  } catch (e: any) {
    message.error(e?.msg || '发布失败，请核对版本和许可')
  } finally {
    saving.value = false
  }
}
onMounted(load)
onBeforeUnmount(() => {
  generation++
  clearImages()
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

    .el-image {
      display: block;
      width: 100%;
      height: 260px;
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
  flex-wrap: wrap;
  gap: 12px;
  width: 100%;
}
</style>
