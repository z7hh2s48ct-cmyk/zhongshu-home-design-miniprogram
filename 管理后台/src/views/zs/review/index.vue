<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">AI案例审核</h1>
        <div class="zs-page-subtitle">用户投稿的设计方案，审核通过后可发布至户型库</div>
      </div>
    </div>

    <div class="zs-table-card">
      <div class="zs-review-tabs-bar">
        <el-tabs v-model="activeTab" @tab-change="search">
          <el-tab-pane label="待审核（含重提）" name="SUBMITTED" />
          <el-tab-pane label="已通过" name="APPROVED" />
          <el-tab-pane label="已退回" name="CHANGES_REQUESTED" />
          <el-tab-pane label="已拒绝" name="REJECTED" />
        </el-tabs>
        <!-- E-6 批量操作常驻页签右侧：不再依赖滚动到表格下方才发现 -->
        <div v-if="activeTab === 'SUBMITTED'" class="zs-review-bulk">
          <el-button
            size="small"
            type="success"
            plain
            :disabled="!selection.length"
            :loading="bulkLoading"
            @click="doBulkReview('APPROVE')"
          >
            批量通过（{{ selection.length }}）
          </el-button>
          <el-button
            size="small"
            type="warning"
            plain
            :disabled="!selection.length"
            :loading="bulkLoading"
            @click="doBulkReview('CHANGES_REQUESTED')"
          >
            批量退回（{{ selection.length }}）
          </el-button>
          <el-button
            size="small"
            type="danger"
            plain
            :disabled="!selection.length"
            :loading="bulkLoading"
            @click="doBulkReview('REJECT')"
          >
            批量拒绝（{{ selection.length }}）
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="投稿列表加载失败，请重试"
        style="margin-bottom: 12px"
        ><el-button @click="load">重新加载</el-button></el-alert
      >

      <el-table
        v-if="activeTab === 'SUBMITTED'"
        :data="list"
        v-loading="loading"
        stripe
        @selection-change="(rows: any[]) => (selection = rows)"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column label="投稿编号" prop="submissionId" width="180" />
        <el-table-column label="投稿用户" prop="userId" width="110" />
        <el-table-column label="结果版本" prop="resultVersionId" width="180" />
        <el-table-column label="当前轮次" prop="currentRound" width="90" />
        <el-table-column label="公开展示" width="90">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.publicDisplayGranted ? 'zs-tag--green' : 'zs-tag--gray'"
            >
              {{ row.publicDisplayGranted ? '已授权' : '未授权' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="生成参考" width="90">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.generationReferenceGranted ? 'zs-tag--green' : 'zs-tag--gray'"
            >
              {{ row.generationReferenceGranted ? '已授权' : '未授权' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="120">
          <template #default="{ row }">
            <span class="zs-link" @click="$router.push(`/zs/review/${row.submissionId}`)"
              >去审核 ›</span
            >
          </template>
        </el-table-column>
      </el-table>

      <el-table v-else :data="list" v-loading="loading" stripe>
        <el-table-column label="投稿编号" prop="submissionId" width="180" />
        <el-table-column label="投稿用户" prop="userId" width="110" />
        <el-table-column label="结果版本" prop="resultVersionId" width="180" />
        <el-table-column label="当前轮次" prop="currentRound" width="90" />
        <el-table-column label="公开展示" width="90">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.publicDisplayGranted ? 'zs-tag--green' : 'zs-tag--gray'"
            >
              {{ row.publicDisplayGranted ? '已授权' : '未授权' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="生成参考" width="90">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.generationReferenceGranted ? 'zs-tag--green' : 'zs-tag--gray'"
            >
              {{ row.generationReferenceGranted ? '已授权' : '未授权' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="120">
          <template #default="{ row }">
            <span class="zs-link" @click="$router.push(`/zs/review/${row.submissionId}`)"
              >查看详情 ›</span
            >
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-16px"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        v-model:current-page="query.pageNo"
        @current-change="load"
      />
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'
import { ElMessage, ElMessageBox } from 'element-plus'

defineOptions({ name: 'ZsReview' })

const activeTab = ref('SUBMITTED')
const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ status: 'SUBMITTED', pageNo: 1, pageSize: 10 })
const selection = ref<any[]>([])
const bulkLoading = ref(false)

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  query.status = activeTab.value
  selection.value = []
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getSubmissionPage({ ...query })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 加载失败必须与"没有待审内容"区分，避免审核员误判为已清空
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}

// 批量审核：后端逐项幂等、单项失败不中断；退回/拒绝要求填写意见
const doBulkReview = async (decision: string) => {
  const needComment = decision !== 'APPROVE'
  // E-6 回显选中项授权状态：未授权公开展示的稿件不适合批量通过，避免误放
  const unauthorized = selection.value.filter((r) => !r.publicDisplayGranted).length
  const summary = selection.value
    .slice(0, 5)
    .map(
      (r) => `${r.submissionId.slice(-6)}（${r.publicDisplayGranted ? '已授权' : '未授权展示'}）`
    )
  const preview = `将${decision === 'APPROVE' ? '通过' : decision === 'CHANGES_REQUESTED' ? '退回修改' : '拒绝'} ${selection.value.length} 件：${summary.join('、')}${selection.value.length > 5 ? ' 等' : ''}${unauthorized ? `；其中 ${unauthorized} 件未授权公开展示` : ''}`
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(`${preview}，请输入审核意见`, '批量审核', {
      inputValidator: (v: string) => (needComment && !v?.trim() ? '审核意见必填' : true),
      confirmButtonText: '确认执行',
      cancelButtonText: '取消'
    })
    comment = value?.trim() || ''
  } catch {
    return
  }
  bulkLoading.value = true
  try {
    const res = await ZsApi.bulkReview({
      decision,
      comment,
      submissionIds: selection.value.map((r) => r.submissionId)
    })
    const items = res?.items || []
    const ok = items.filter((i: any) => i.success).length
    const failed = items.filter((i: any) => !i.success)
    if (failed.length) {
      ElMessage.warning(
        `完成 ${ok} 项，失败 ${failed.length} 项：${failed.map((f: any) => f.targetId).join('、')}`
      )
    } else {
      ElMessage.success(`已完成 ${ok} 项`)
    }
    load()
  } finally {
    bulkLoading.value = false
  }
}
onMounted(load)
</script>

<style lang="scss" scoped>
.zs-review-tabs-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  :deep(.el-tabs) {
    flex: 1;
  }
}

.zs-review-bulk {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
  white-space: nowrap;
}
</style>
