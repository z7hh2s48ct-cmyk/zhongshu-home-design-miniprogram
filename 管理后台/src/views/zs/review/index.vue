<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">AI案例审核</h1>
        <div class="zs-page-subtitle">用户投稿的设计方案，审核通过后可发布至户型库</div>
      </div>
    </div>

    <div class="zs-table-card">
      <el-tabs v-model="activeTab" @tab-change="search">
        <el-tab-pane label="待审核" name="SUBMITTED" />
        <el-tab-pane label="已通过" name="APPROVED" />
        <el-tab-pane label="已退回" name="CHANGES_REQUESTED" />
        <el-tab-pane label="已拒绝" name="REJECTED" />
      </el-tabs>

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

      <div v-if="activeTab === 'SUBMITTED' && selection.length" class="mt-12px">
        <el-button type="success" plain :loading="bulkLoading" @click="doBulkReview('APPROVE')">
          批量通过（{{ selection.length }}）
        </el-button>
        <el-button
          type="warning"
          plain
          :loading="bulkLoading"
          @click="doBulkReview('CHANGES_REQUESTED')"
        >
          批量退回（{{ selection.length }}）
        </el-button>
        <el-button type="danger" plain :loading="bulkLoading" @click="doBulkReview('REJECT')">
          批量拒绝（{{ selection.length }}）
        </el-button>
      </div>

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
  try {
    const res = await ZsApi.getSubmissionPage({ ...query })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// 批量审核：后端逐项幂等、单项失败不中断；退回/拒绝要求填写意见
const doBulkReview = async (decision: string) => {
  const needComment = decision !== 'APPROVE'
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `对选中的 ${selection.value.length} 项执行「${decision === 'APPROVE' ? '通过' : decision === 'CHANGES_REQUESTED' ? '退回修改' : '拒绝'}」，请输入审核意见`,
      '批量审核',
      {
        inputValidator: (v: string) => (needComment && !v?.trim() ? '审核意见必填' : true),
        confirmButtonText: '确认执行',
        cancelButtonText: '取消'
      }
    )
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
