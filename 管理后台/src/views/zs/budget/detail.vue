<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div
        ><h1 class="zs-page-title">预算详情与修订</h1
        ><div class="zs-page-subtitle"
          >补量、专用价及临时项仅作用于本预算；保存生成新修订，不发布对外报价</div
        ></div
      >
      <div
        ><el-button :disabled="saving" @click="back">返回列表</el-button
        ><el-button v-if="canQuery && detail && !dirty" :disabled="saving" @click="openQuotes"
          >对外报价</el-button
        ><el-button :disabled="saving" :loading="loading" @click="refresh">刷新</el-button></div
      >
    </div>
    <!-- E-11 顶部版本切换：不再需要滚动到页面底部找历史修订 -->
    <div v-if="canQuery && revisions.length" class="zs-revision-switch zs-table-card">
      <span style="font-size: 13px; font-weight: 600; color: #6a3b1b">版本切换</span>
      <el-select
        :model-value="detail?.revisionId"
        size="small"
        style="width: 360px"
        placeholder="切换查看历史修订"
        @change="(id: string) => (id === detail?.revisionId ? viewRevision() : viewRevision(id))"
      >
        <el-option
          v-for="row in revisions"
          :key="row.revisionId"
          :value="row.revisionId"
          :label="`修订 ${row.revisionNo} · ${row.changeReason || '（无原因）'} · ${
            row.revisionId === detail?.revisionId ? '当前' : ''
          }`"
        />
      </el-select>
    </div>
    <el-alert
      v-if="!canQuery"
      title="没有预算查询权限，请联系管理员授权"
      type="warning"
      :closable="false"
    />
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="mb-16px" />
    <div v-if="loading && !detail" class="zs-table-card" v-loading="loading">正在读取预算…</div>
    <template v-if="canQuery && detail">
      <div class="zs-table-card mb-16px">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="预算编号">{{ detail.budgetId }}</el-descriptions-item
          ><el-descriptions-item label="项目"
            >{{ detail.projectName || '未命名' }} · {{ detail.projectId }}</el-descriptions-item
          ><el-descriptions-item label="所属用户">{{ detail.userId }}</el-descriptions-item>
          <el-descriptions-item label="原方案版本">{{
            detail.resultVersionId || '项目级参数'
          }}</el-descriptions-item
          ><el-descriptions-item label="地区">{{
            detail.regionName || detail.regionCode || '待补'
          }}</el-descriptions-item
          ><el-descriptions-item label="当前查看"
            >修订 {{ detail.revisionNo }} ·
            {{ detail.readOnly ? '只读' : '当前版本' }}</el-descriptions-item
          >
          <el-descriptions-item label="服务端完整性">{{
            detail.completeness === 'COMPLETE' ? '完整' : '待补充'
          }}</el-descriptions-item
          ><el-descriptions-item label="已计价小计"
            >{{ formatCents(detail.pricedSubtotalCents) }} 元</el-descriptions-item
          ><el-descriptions-item label="完整总额">{{
            detail.completeness === 'COMPLETE' && detail.totalCents != null
              ? formatCents(detail.totalCents) + ' 元'
              : '待补充，非完整总额'
          }}</el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="detail.readOnly"
          title="历史修订只读；要修改预算请先切换到当前版本。"
          type="info"
          :closable="false"
          class="mt-16px"
        />
        <el-alert
          v-else-if="!canEdit"
          title="只有查询权限；没有预算修订权限。"
          type="warning"
          :closable="false"
          class="mt-16px"
        />
      </div>

      <div class="zs-table-card mb-16px">
        <div class="zs-panel-title">输入参数与来源（只读）</div>
        <el-table :data="inputRows" size="small"
          ><el-table-column prop="field" label="字段" min-width="170" /><el-table-column
            prop="imported"
            label="原导入值"
            min-width="140" /><el-table-column
            prop="current"
            label="当前快照值"
            min-width="140" /><el-table-column prop="source" label="来源" min-width="150"
        /></el-table>
        <el-alert
          v-if="detail.missingFields.length || detail.warnings.length"
          :title="'缺失参数：' + (detail.missingFields.map(fieldLabel).join('、') || '无')"
          :description="
            detail.warnings.map(warningLabel).join('；') ||
            '请在项目参数业务中核对；补行数量不覆盖全局参数或消除全局警告。'
          "
          type="warning"
          :closable="false"
          class="mt-16px"
        />
      </div>

      <div class="zs-table-card mb-16px">
        <div class="revision-toolbar"
          ><div class="zs-panel-title">明细工作副本</div
          ><div v-if="canEdit"
            ><el-button :disabled="saving || loading" @click="openCustom">新增项目临时项</el-button
            ><el-button :disabled="saving || loading" @click="openTemplateAddition"
              >从目录模板补项</el-button
            ></div
          ></div
        >
        <el-alert
          title="输入框中的数量、单价及备注是本预算专用调整。同名但不同编号的行不会合并。标准行不可删除，可明确填写排除原因。"
          type="info"
          :closable="false"
          class="mb-16px"
        />
        <el-alert
          v-if="catalogError"
          :title="catalogError"
          type="warning"
          :closable="false"
          class="mb-16px"
          ><template #default
            ><el-button link @click="loadCatalog">重试目录</el-button></template
          ></el-alert
        >
        <!-- E-2 批量排除：整组不要时无需逐行进弹窗 -->
        <div v-if="canEdit" class="zs-line-toolbar">
          <span style="font-size: 12px; color: #8a8a8a"
            >已选 {{ selectedLines.length }} 行；数量可直接改，保存前可随时预览</span
          >
          <el-button
            size="small"
            type="warning"
            plain
            :disabled="!selectedLines.length || saving"
            @click="bulkExclude"
            >批量排除</el-button
          >
          <el-button
            size="small"
            plain
            :disabled="!selectedLines.length || saving"
            @click="bulkRestore"
            >恢复参与计价</el-button
          >
        </div>
        <el-table
          :data="lines"
          stripe
          :row-class-name="({ row }) => (row.removed ? 'removed-line' : '')"
          @selection-change="(rows: any[]) => (selectedLines = rows)"
        >
          <el-table-column
            v-if="canEdit"
            type="selection"
            width="42"
            :selectable="(row) => !row.removed"
          />
          <el-table-column label="预算项 / 来源" min-width="230"
            ><template #default="{ row }"
              ><div>{{ row.publicName || '未填写名称' }} · {{ row.itemCode }}</div
              ><small
                >{{ row.category === 'BODY' ? '主体' : '外装' }} /
                {{
                  sourceNames[
                    row.original?.source ||
                      (row.kind === 'CATALOG_OPTION' ? 'CUSTOM_TEMPLATE' : 'PROJECT_CUSTOM')
                  ]
                }}
                / {{ row.original?.lineId || '本机新增 ' + row.key }}</small
              ><div>{{
                row.optionLabel || (row.optionId ? '选项 ' + row.optionId : '未选择计价选项')
              }}</div></template
            ></el-table-column
          >
          <el-table-column label="数量 / 单价（元）" min-width="200"
            ><template #default="{ row }"
              ><div
                ><template v-if="canEdit && !isAutomatic(row, 'quantity') && !row.removed"
                  ><el-input
                    v-model="row.quantityText"
                    size="small"
                    style="width: 110px"
                    maxlength="12"
                    :placeholder="'待补量'"
                  /><span style="margin-left: 6px">{{ unitLabel(row.unit) }}</span></template
                ><template v-else
                  >{{
                    isAutomatic(row, 'quantity') ? '保存时按规则核定' : row.quantityText || '待补量'
                  }}
                  {{ unitLabel(row.unit) }}</template
                ></div
              ><div>{{
                isAutomatic(row, 'price') ? '保存时按地区查价' : row.priceYuan || '待补价'
              }}</div></template
            ></el-table-column
          >
          <el-table-column label="原量 / 原价（元）与来源" min-width="220"
            ><template #default="{ row }"
              ><div
                >{{ row.original?.originalQuantity ?? '未记录' }} /
                {{ formatCents(row.original?.originalUnitPriceCents) }}</div
              ><small
                >{{ sourceLabel(row.original?.quantitySource) }} /
                {{ sourceLabel(row.original?.priceSource) }}</small
              ><div>{{ row.original?.sourceReference || '' }}</div></template
            ></el-table-column
          >
          <el-table-column label="工作副本状态 / 金额（元）" min-width="180"
            ><template #default="{ row }"
              ><template v-if="row.removed">保存时移除</template
              ><template v-else
                >{{ previewStatus(row) }}<div>{{ previewAmount(row) }}</div></template
              ></template
            ></el-table-column
          >
          <el-table-column label="说明" min-width="180"
            ><template #default="{ row }"
              ><div v-if="row.freeReason">免费：{{ row.freeReason }}</div
              ><div v-if="row.excludedReason">排除：{{ row.excludedReason }}</div
              ><div v-if="row.internalNote">内部：{{ row.internalNote }}</div></template
            ></el-table-column
          >
          <el-table-column v-if="canEdit" label="操作" min-width="190" fixed="right"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                :disabled="saving || row.removed"
                @click="openLine(row)"
                >编辑</el-button
              ><el-button
                v-if="!row.removed"
                link
                :type="row.excludedReason ? 'primary' : 'warning'"
                :disabled="saving"
                @click="quickExclude(row)"
                >{{ row.excludedReason ? '恢复计价' : '排除' }}</el-button
              ><el-button
                v-if="row.original?.source !== 'STANDARD'"
                link
                :type="row.removed ? 'primary' : 'danger'"
                :disabled="saving"
                @click="toggleRemove(row)"
                >{{ row.removed ? '撤销移除' : '移除' }}</el-button
              ><el-tooltip
                v-if="canTemplate && row.original?.source === 'PROJECT_CUSTOM' && !row.removed"
                :disabled="!dirty"
                content="请先保存或放弃本次修改，再另存模板（模板保存的是服务端已保存行）"
                placement="top"
                ><span
                  ><el-button
                    link
                    type="primary"
                    :disabled="saving || dirty"
                    @click="openTemplate(row)"
                    >另存模板</el-button
                  ></span
                ></el-tooltip
              ></template
            ></el-table-column
          >
        </el-table>
      </div>

      <div class="zs-table-card mb-16px">
        <div class="zs-panel-title">实时预览（最终以服务端保存结果为准）</div>
        <el-alert v-if="previewError" :title="previewError" type="error" :closable="false" />
        <el-descriptions v-else-if="previewResult" :column="4" border
          ><el-descriptions-item label="主体小计"
            >{{ formatCents(previewResult.categoryTotals.BODY) }} 元</el-descriptions-item
          ><el-descriptions-item label="外装小计"
            >{{ formatCents(previewResult.categoryTotals.EXTERIOR) }} 元</el-descriptions-item
          ><el-descriptions-item label="已计价小计"
            >{{ formatCents(previewResult.pricedSubtotalCents) }} 元</el-descriptions-item
          ><el-descriptions-item label="完整总额">{{
            previewResult.totalCents == null
              ? '待补充，非完整总额'
              : formatCents(previewResult.totalCents) + ' 元'
          }}</el-descriptions-item></el-descriptions
        >
        <p class="zs-page-subtitle"
          >待补选项或模板自动量价在保存时由服务端核定；预览不以 0 代替缺失，不清除原参数警告。</p
        >
        <template v-if="canEdit"
          ><el-input
            v-model="reason"
            type="textarea"
            :disabled="saving"
            maxlength="500"
            show-word-limit
            placeholder="修订原因（必填）"
          /><el-alert
            v-if="saveError"
            :title="saveError"
            type="error"
            :closable="false"
            class="mt-16px"
          /><div class="revision-actions"
            ><el-button :disabled="saving || !dirty" @click="discard">放弃本次修改</el-button
            ><el-button
              type="primary"
              :disabled="!dirty || loading || !!previewError"
              :loading="saving"
              @click="save"
              >保存为新修订</el-button
            ></div
          ></template
        >
      </div>

      <div class="zs-table-card">
        <div class="zs-panel-title">历史修订</div>
        <el-alert v-if="historyError" :title="historyError" type="warning" :closable="false"
          ><template #default
            ><el-button link @click="loadHistory">重试历史</el-button></template
          ></el-alert
        >
        <el-table :data="revisions" size="small"
          ><el-table-column label="修订" width="85"
            ><template #default="{ row }">{{ row.revisionNo }}</template></el-table-column
          ><el-table-column prop="changeReason" label="原因" min-width="220" /><el-table-column
            label="操作人"
            min-width="150"
            ><template #default="{ row }"
              >{{ row.actorType }} · {{ row.actorId || '系统' }}</template
            ></el-table-column
          ><el-table-column label="时间" min-width="180"
            ><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column
          ><el-table-column label="已计价小计（元）" min-width="140"
            ><template #default="{ row }">{{
              formatCents(row.pricedSubtotalCents)
            }}</template></el-table-column
          ><el-table-column label="操作" min-width="150"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                :disabled="saving"
                @click="viewRevision(row.revisionId)"
                >查看</el-button
              ><el-button
                link
                type="primary"
                :disabled="saving || row.revisionId === detail.revisionId"
                @click="compare(row.revisionId)"
                >对比</el-button
              ></template
            ></el-table-column
          ></el-table
        >
        <el-button v-if="detail.readOnly" class="mt-16px" @click="viewRevision()"
          >切换到当前版本</el-button
        >
        <el-alert
          v-if="compareError"
          :title="compareError"
          type="error"
          :closable="false"
          class="mt-16px"
        />
        <template v-if="comparison"
          ><h3>修订 {{ comparison.revisionNo }} → 当前查看修订 {{ detail.revisionNo }}</h3
          ><p
            >已计价小计：{{ formatCents(comparison.pricedSubtotalCents) }} →
            {{ formatCents(detail.pricedSubtotalCents) }} 元（对比已保存快照，不包含工作副本）</p
          ><el-table :data="diffRows" empty-text="明细量价及说明无差异"
            ><el-table-column prop="name" label="预算项" min-width="140" /><el-table-column
              prop="source"
              label="来源"
              width="110" /><el-table-column
              prop="oldValue"
              label="对比修订"
              min-width="260" /><el-table-column
              prop="newValue"
              label="当前查看修订"
              min-width="260" /></el-table
        ></template>
      </div>
    </template>

    <el-dialog
      v-model="editorVisible"
      :title="
        editing?.kind === 'PROJECT_CUSTOM' && !editing.original
          ? '新增项目临时项'
          : '编辑工作副本明细'
      "
      width="min(700px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-form v-if="editing" label-width="130px" @submit.prevent="applyLine">
        <template v-if="!editing.original && editing.kind === 'PROJECT_CUSTOM'"
          ><el-form-item label="稳定编码" required
            ><el-input
              v-model="editing.itemCode"
              maxlength="64"
              placeholder="大写字母、数字或下划线" /></el-form-item
          ><el-form-item label="对外名称" required
            ><el-input v-model="editing.publicName" maxlength="100" /></el-form-item
          ><el-form-item label="分类"
            ><el-radio-group v-model="editing.category"
              ><el-radio value="BODY">主体</el-radio
              ><el-radio value="EXTERIOR">外装</el-radio></el-radio-group
            ></el-form-item
          ><el-form-item label="单位"
            ><el-select v-model="editing.unit"
              ><el-option
                v-for="unit in units"
                :key="unit.value"
                :value="unit.value"
                :label="unit.label" /></el-select></el-form-item
        ></template>
        <el-form-item
          v-if="editing.original?.source === 'STANDARD' && !editing.original.optionId"
          label="补选标准选项"
          ><el-select
            :model-value="editing.optionId"
            clearable
            filterable
            :loading="catalogLoading"
            placeholder="仅同预算项和所需选择组"
            @change="selectStandard"
            ><el-option
              v-for="option in standardChoices"
              :key="option.optionId"
              :value="option.optionId!"
              :label="option.label" /></el-select
        ></el-form-item>
        <template v-if="editorAutomatic"
          ><el-alert
            title="默认使用冻结项目参数及当前预算地区价格；开启手动覆盖后，留空表示明确待补。"
            type="info"
            :closable="false"
            class="mb-16px" /><el-form-item label="手动数量"
            ><el-switch v-model="editing.manualQuantity" /></el-form-item
          ><el-form-item label="专用单价"><el-switch v-model="editing.manualPrice" /></el-form-item
        ></template>
        <el-form-item label="数量"
          ><el-input
            v-model="editing.quantityText"
            :disabled="editorAutomatic && !editing.manualQuantity"
            maxlength="12"
            :placeholder="fixedOne(editing) ? '留空待补或填写1' : '留空待补；面积/长度最多4位小数'"
          /><span>{{ unitLabel(editing.unit) }}</span></el-form-item
        >
        <el-form-item label="专用单价（元）"
          ><el-input
            v-model="editing.priceYuan"
            :disabled="editorAutomatic && !editing.manualPrice"
            maxlength="10"
            placeholder="留空待补；0元须说明免费原因"
        /></el-form-item>
        <el-form-item label="免费原因"
          ><el-input
            v-model="editing.freeReason"
            type="textarea"
            maxlength="500"
            show-word-limit /></el-form-item
        ><el-form-item label="排除原因"
          ><el-input
            v-model="editing.excludedReason"
            type="textarea"
            maxlength="500"
            show-word-limit
            placeholder="非空表示明确排除计价；清空恢复参与计算" /></el-form-item
        ><el-form-item label="内部备注"
          ><el-input v-model="editing.internalNote" type="textarea" maxlength="500" show-word-limit
        /></el-form-item> </el-form
      ><el-alert v-if="editorError" :title="editorError" type="error" :closable="false" /><template
        #footer
        ><el-button @click="editorVisible = false">取消</el-button
        ><el-button type="primary" @click="applyLine">应用到工作副本</el-button></template
      >
    </el-dialog>

    <el-dialog
      v-model="addTemplateVisible"
      title="从目录模板补项"
      width="min(620px, 94vw)"
      :close-on-click-modal="false"
      ><el-alert
        title="包含仅后台可选的启用模板；此操作只补入当前预算，不修改模板及地区价。自动数量与价格保存时核定，未知价格保持待补。"
        type="info"
        :closable="false"
      /><el-select
        v-model="templateOptionId"
        filterable
        :loading="catalogLoading"
        placeholder="选择模板 / 计价选项"
        class="template-choice"
        ><el-option
          v-for="choice in templateChoices"
          :key="choice.option.optionId"
          :value="choice.option.optionId!"
          :label="
            choice.item.name + ' / ' + choice.option.label + ' · ' + choice.option.optionId
          " /></el-select
      ><el-alert
        v-if="catalogError"
        :title="catalogError"
        type="warning"
        :closable="false"
      /><el-alert v-if="editorError" :title="editorError" type="error" :closable="false" /><template
        #footer
        ><el-button @click="addTemplateVisible = false">取消</el-button
        ><el-button
          type="primary"
          :disabled="catalogLoading || !templateOptionId"
          @click="addTemplate"
          >加入工作副本</el-button
        ></template
      ></el-dialog
    >

    <el-dialog
      v-model="templateVisible"
      title="另存为私有禁用模板草稿"
      width="min(620px, 94vw)"
      :close-on-click-modal="false"
      :show-close="!saving"
      :close-on-press-escape="!saving"
      ><el-alert
        title="仅创建禁用、仅后台的预算项目录草稿，不复制本项目工程量、单价、备注或任何选项。后续须到预算配置中设置规则、选项和地区价，不改变本预算。"
        type="warning"
        :closable="false"
      /><el-form label-width="100px" class="mt-16px"
        ><el-form-item label="模板编码"
          ><el-input v-model="templateForm.code" :disabled="saving" maxlength="64" /></el-form-item
        ><el-form-item label="模板名称"
          ><el-input v-model="templateForm.name" :disabled="saving" maxlength="100" /></el-form-item
        ><el-form-item label="另存原因"
          ><el-input
            v-model="templateForm.reason"
            :disabled="saving"
            type="textarea"
            maxlength="500"
            show-word-limit /></el-form-item></el-form
      ><el-alert
        v-if="templateError"
        :title="templateError"
        type="error"
        :closable="false"
      /><template #footer
        ><el-button :disabled="saving" @click="templateVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveTemplate"
          >创建模板草稿</el-button
        ></template
      ></el-dialog
    >
    <!-- E-2 吸底保存条：长列表编辑后无需滚回页尾 -->
    <div v-if="canEdit && dirty && detail && !detail.readOnly" class="zs-sticky-save">
      <span class="zs-sticky-hint">有未保存的修订修改</span>
      <el-input
        v-model="reason"
        :disabled="saving"
        maxlength="500"
        style="flex: 1; max-width: 420px"
        placeholder="修订原因（必填，与下方表单同值）"
      />
      <el-button :disabled="saving || !dirty" @click="discard">放弃本次修改</el-button>
      <el-button
        type="primary"
        :disabled="!dirty || loading || !!previewError"
        :loading="saving"
        @click="save"
        >保存为新修订</el-button
      >
    </div>
  </div>
</template>

<script setup lang="ts">
import * as BudgetApi from '@/api/zs/budget-revision'
import * as CatalogApi from '@/api/zs/budget'
import type { BudgetDetail, BudgetLine, RevisionRef } from '@/api/zs/budget-revision'
import type { CatalogRow } from '@/api/zs/budget'
import { checkPermi } from '@/utils/permission'
import { fmtTime } from '@/utils/zsFormat'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { getTenantId, getVisitTenantId } from '@/utils/auth'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import {
  differences,
  eligibleOptions,
  errorText,
  fieldLabel,
  fixedOne,
  formatCents,
  preview,
  revisionPayload,
  sourceNames,
  sourceLabel,
  statusNames,
  templateLine,
  templatePayload,
  units,
  validId,
  warningLabel,
  workLine
} from './revision-form'
import type { WorkLine } from './revision-form'

defineOptions({ name: 'ZsBudgetDetail' })
const route = useRoute(),
  router = useRouter(),
  message = useMessage(),
  userStore = useUserStore()
const actor = () => JSON.stringify([userStore.getUser.id, getTenantId(), getVisitTenantId()])
const canQuery = computed(() => checkPermi(['design:budget:query']))
const canEdit = computed(
  () =>
    canQuery.value && checkPermi(['design:budget:edit']) && !!detail.value && !detail.value.readOnly
)
const canTemplate = computed(() => canEdit.value && checkPermi(['design:budget:configure']))
const detail = ref<BudgetDetail | null>(null),
  lines = ref<WorkLine[]>([]),
  revisions = ref<RevisionRef[]>([])
const loading = ref(false),
  saving = ref(false),
  loadError = ref(''),
  saveError = ref(''),
  historyError = ref(''),
  reason = ref('')
const baseline = ref(''),
  comparison = ref<BudgetDetail | null>(null),
  compareError = ref('')
const dirty = computed(() => JSON.stringify(lines.value) !== baseline.value && !!detail.value)
const diffRows = computed(() =>
  detail.value && comparison.value ? differences(detail.value, comparison.value) : []
)
const previewState = computed(() => {
  try {
    return { value: detail.value ? preview(detail.value, lines.value) : null, error: '' }
  } catch (error) {
    return { value: null, error: errorText(error) }
  }
})
const previewResult = computed(() => previewState.value.value),
  previewError = computed(() => previewState.value.error)
const inputRows = computed(() => {
  const snapshot = detail.value?.inputSnapshot || {}
  const fields = [
    ...new Set([
      ...Object.keys(snapshot.importedValues || {}),
      ...Object.keys(snapshot.currentValues || {})
    ])
  ]
  const show = (value: unknown) => (value == null ? '待补' : String(value))
  return [
    ...fields.map((field) => ({
      field: fieldLabel(field),
      imported: show(snapshot.importedValues?.[field]),
      current: show(snapshot.currentValues?.[field]),
      source: sourceLabel(snapshot.currentSources?.[field] || snapshot.importedSources?.[field])
    })),
    ...Object.entries(snapshot.quantities || {}).map(([field, value]) => ({
      field: '工程量 / ' + fieldLabel(field),
      imported: '冻结快照',
      current: show(value),
      source: '项目核定工程量'
    }))
  ]
})
const unitLabel = (unit: string) => units.find((entry) => entry.value === unit)?.label || unit
const isAutomatic = (line: WorkLine, field: 'quantity' | 'price') =>
  (line.kind === 'CATALOG_OPTION' ||
    (!!line.original && line.optionId !== (line.original.optionId || ''))) &&
  !(field === 'quantity' ? line.manualQuantity : line.manualPrice)
const previewStatus = (line: WorkLine) => {
  const row = previewResult.value?.rows.find((row) => row.key === line.key)
  return row ? statusNames[row.status] : '预览待校验'
}
const previewAmount = (line: WorkLine) => {
  const row = previewResult.value?.rows.find((row) => row.key === line.key)
  return row?.amountCents != null ? formatCents(row.amountCents) : '—'
}
let sequence = 0,
  historySequence = 0,
  compareSequence = 0,
  catalogSequence = 0,
  loadedActor = ''
let pendingCommand: { fingerprint: string; key: string } | undefined
const keyFor = (action: string, payload: unknown) => {
  const fingerprint = JSON.stringify([actor(), action, payload])
  if (pendingCommand?.fingerprint !== fingerprint)
    pendingCommand = { fingerprint, key: crypto.randomUUID() }
  return pendingCommand.key
}
const mutationError = (error: unknown) =>
  errorText(error) + '；工作副本已保留。若版本冲突请刷新后重新核对，不会自动覆盖他人修订。'
function accept(requestId: number, scope: string) {
  if (requestId !== sequence) return false
  if (scope !== actor() || !canQuery.value) {
    clear()
    return false
  }
  return true
}
function clear() {
  sequence++
  historySequence++
  compareSequence++
  catalogSequence++
  detail.value = null
  lines.value = []
  revisions.value = []
  comparison.value = null
  loading.value = false
  saving.value = false
  editorVisible.value = false
  templateVisible.value = false
  addTemplateVisible.value = false
  pendingCommand = undefined
  baseline.value = ''
  loadedActor = ''
  items.value = []
  options.value = []
  catalogLoading.value = false
  catalogError.value = ''
}
function applyDetail(response: BudgetDetail) {
  detail.value = response
  lines.value = response.lines.map((line) => workLine(line))
  baseline.value = JSON.stringify(lines.value)
  reason.value = ''
  saveError.value = ''
  comparison.value = null
  compareError.value = ''
  pendingCommand = undefined
}
async function load() {
  clear()
  loadError.value = ''
  if (!canQuery.value) return
  const budgetId = route.params.budgetId,
    revisionId = route.query.revisionId
  if (!validId(budgetId) || (revisionId != null && !validId(revisionId))) {
    loadError.value = '预算或修订编号无效'
    return
  }
  const requestId = ++sequence,
    scope = actor()
  loading.value = true
  try {
    const response = await BudgetApi.getEstimate(budgetId, revisionId as string | undefined)
    if (!accept(requestId, scope)) return
    if (response.budgetId !== budgetId || (revisionId && response.revisionId !== revisionId))
      throw Error('预算响应上下文不匹配')
    loadedActor = scope
    applyDetail(response)
    void loadHistory()
    if (canEdit.value) void loadCatalog()
  } catch (error) {
    if (accept(requestId, scope)) loadError.value = errorText(error)
  } finally {
    if (requestId === sequence) loading.value = false
  }
}
async function confirmDiscard() {
  if (!dirty.value) return true
  try {
    await message.confirm('当前工作副本尚未保存，继续将放弃本次修改。', '确认放弃修改')
    return true
  } catch {
    return false
  }
}
async function refresh() {
  if (!saving.value && (await confirmDiscard())) await load()
}
async function back() {
  if (!saving.value && (await confirmDiscard())) {
    approvedNavigation = true
    try {
      await router.push('/zs/budget-estimates')
    } finally {
      approvedNavigation = false
    }
  }
}
async function openQuotes() {
  if (!detail.value || saving.value || dirty.value) return
  approvedNavigation = true
  try {
    await router.push('/zs/budget-estimates/' + detail.value.budgetId + '/quotes')
  } finally {
    approvedNavigation = false
  }
}
async function viewRevision(revisionId?: string) {
  if (
    !detail.value ||
    saving.value ||
    (revisionId && !validId(revisionId)) ||
    !(await confirmDiscard())
  )
    return
  approvedNavigation = true
  try {
    await router.push({
      path: '/zs/budget-estimates/' + detail.value.budgetId,
      query: revisionId ? { revisionId } : {}
    })
  } finally {
    approvedNavigation = false
  }
}
async function discard() {
  if (!saving.value && detail.value && (await confirmDiscard())) applyDetail(detail.value)
}
async function loadHistory() {
  if (!detail.value || !canQuery.value) return
  const requestId = ++historySequence,
    scope = actor(),
    budgetId = detail.value.budgetId
  historyError.value = ''
  try {
    const result = await BudgetApi.getRevisions(budgetId)
    if (requestId === historySequence && scope === actor() && canQuery.value)
      revisions.value = result
  } catch (error) {
    if (requestId === historySequence && scope === actor()) historyError.value = errorText(error)
  }
}
async function compare(revisionId: string) {
  if (!detail.value || !canQuery.value || !validId(revisionId)) return
  const requestId = ++compareSequence,
    scope = actor(),
    budgetId = detail.value.budgetId
  comparison.value = null
  compareError.value = ''
  try {
    const result = await BudgetApi.getEstimate(budgetId, revisionId)
    if (requestId !== compareSequence || scope !== actor() || !canQuery.value) return
    if (result.budgetId !== budgetId || result.revisionId !== revisionId)
      throw Error('修订响应不匹配')
    comparison.value = result
  } catch (error) {
    if (requestId === compareSequence && scope === actor()) compareError.value = errorText(error)
  }
}

const items = ref<CatalogRow[]>([]),
  options = ref<CatalogRow[]>([]),
  catalogLoading = ref(false),
  catalogError = ref('')
async function loadCatalog() {
  if (!canEdit.value || catalogLoading.value || saving.value) return
  const requestId = ++catalogSequence,
    scope = actor()
  catalogLoading.value = true
  catalogError.value = ''
  try {
    const fetchAll = async (kind: 'items' | 'options') => {
      const all: CatalogRow[] = []
      for (let pageNo = 1; pageNo <= 50; pageNo++) {
        const response = await CatalogApi.getCatalogPage(kind, { pageNo, pageSize: 100 })
        all.push(...response.list)
        if (all.length >= response.total || !response.list.length) return all
      }
      return all
    }
    const result = await Promise.all([fetchAll('items'), fetchAll('options')])
    if (requestId === catalogSequence && scope === actor() && canEdit.value) {
      items.value = result[0]
      options.value = result[1]
    }
  } catch (error) {
    if (requestId === catalogSequence && scope === actor())
      catalogError.value = '目录加载失败：' + errorText(error)
  } finally {
    if (requestId === catalogSequence) catalogLoading.value = false
  }
}
function editable() {
  if (loadedActor && loadedActor !== actor()) {
    clear()
    loadError.value = '登录身份已变化，请重新加载'
    return false
  }
  return canEdit.value && !saving.value && !loading.value
}
const editorVisible = ref(false),
  editing = ref<WorkLine | null>(null),
  editorError = ref('')
const standardChoices = computed(() =>
  editing.value?.original ? eligibleOptions(editing.value.original, items.value, options.value) : []
)
const editorAutomatic = computed(
  () =>
    !!editing.value &&
    (editing.value.kind === 'CATALOG_OPTION' ||
      (!!editing.value.original &&
        editing.value.optionId !== (editing.value.original.optionId || '')))
)
const selectedLines = ref<WorkLine[]>([])
// E-2 快捷排除/批量排除：非空 excludedReason 即明确排除，清空恢复参与计价
async function quickExclude(row: WorkLine) {
  if (!editable()) return
  if (row.excludedReason) {
    row.excludedReason = ''
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('该行将不参与计价；原因会写入明细。', '排除该行', {
      type: 'warning',
      confirmButtonText: '确认排除',
      cancelButtonText: '取消',
      inputPlaceholder: '排除原因（必填）',
      inputValidator: (v: string) => (v?.trim() ? true : '排除原因必填')
    })
    row.excludedReason = value.trim()
  } catch {
    /* 用户取消 */
  }
}
async function bulkExclude() {
  if (!editable()) return
  const targets = selectedLines.value.filter((row) => !row.removed)
  if (!targets.length) return
  try {
    const { value } = await ElMessageBox.prompt(
      `将选中的 ${targets.length} 行明确排除计价。`,
      '批量排除',
      {
        type: 'warning',
        confirmButtonText: '确认排除',
        cancelButtonText: '取消',
        inputPlaceholder: '排除原因（必填）',
        inputValidator: (v: string) => (v?.trim() ? true : '排除原因必填')
      }
    )
    targets.forEach((row) => (row.excludedReason = value.trim()))
    message.success(`已标记 ${targets.length} 行排除，保存后生效`)
  } catch {
    /* 用户取消 */
  }
}
function bulkRestore() {
  if (!editable()) return
  const targets = selectedLines.value.filter((row) => !row.removed && row.excludedReason)
  targets.forEach((row) => (row.excludedReason = ''))
  if (targets.length) message.success(`已恢复 ${targets.length} 行参与计价，保存后生效`)
}
function openLine(line: WorkLine) {
  if (!editable() || line.removed) return
  editing.value = { ...line, original: line.original ? { ...line.original } : null }
  editorError.value = ''
  editorVisible.value = true
}
function openCustom() {
  if (editable()) {
    editing.value = workLine(undefined, crypto.randomUUID())
    editorError.value = ''
    editorVisible.value = true
  }
}
function selectStandard(optionId: string) {
  if (!editing.value?.original) return
  const option = standardChoices.value.find((option) => option.optionId === optionId)
  if (optionId && !option) return
  editing.value.optionId = optionId || ''
  editing.value.optionLabel = option?.label || ''
  editing.value.unit = option?.unit || editing.value.original.unit
  editing.value.quantityRule = option?.quantitySource || ''
  editing.value.manualQuantity = !option
  editing.value.manualPrice = !option
}
function applyLine() {
  if (!editable() || !editing.value || !detail.value) return
  const candidate = { ...editing.value }
  const next = lines.value.some((line) => line.key === candidate.key)
    ? lines.value.map((line) => (line.key === candidate.key ? candidate : line))
    : [...lines.value, candidate]
  try {
    // Validate the same whitelist and precision rules used for submission.
    const changed = JSON.stringify(next) !== baseline.value
    if (changed) revisionPayload(detail.value, next, '校验工作副本')
    preview(detail.value, next)
    lines.value = next
    editorVisible.value = false
    editorError.value = ''
  } catch (error) {
    editorError.value = errorText(error)
  }
}
function toggleRemove(line: WorkLine) {
  if (editable() && line.original?.source !== 'STANDARD') line.removed = !line.removed
}
const addTemplateVisible = ref(false),
  templateOptionId = ref('')
const templateChoices = computed(() =>
  options.value.flatMap((option) => {
    const item = items.value.find(
      (item) => item.itemId === option.itemId && item.enabled && item.source === 'CUSTOM_TEMPLATE'
    )
    return item && option.enabled ? [{ item, option }] : []
  })
)
function openTemplateAddition() {
  if (editable()) {
    templateOptionId.value = ''
    editorError.value = ''
    addTemplateVisible.value = true
    void loadCatalog()
  }
}
function addTemplate() {
  if (!editable()) return
  try {
    const choice = templateChoices.value.find(
      (choice) => choice.option.optionId === templateOptionId.value
    )
    if (!choice) throw Error('请选择有效模板选项')
    if (lines.value.some((line) => !line.removed && line.optionId === choice.option.optionId))
      throw Error('该选项已存在，不能重复补入')
    lines.value.push(templateLine(choice.option, choice.item, crypto.randomUUID()))
    addTemplateVisible.value = false
  } catch (error) {
    editorError.value = errorText(error)
  }
}
async function save() {
  if (!editable() || !detail.value) return
  saveError.value = ''
  let payload
  try {
    payload = revisionPayload(detail.value, lines.value, reason.value)
    preview(detail.value, lines.value)
  } catch (error) {
    saveError.value = errorText(error)
    return
  }
  const scope = actor(),
    requestId = sequence,
    budgetId = detail.value.budgetId
  saving.value = true
  try {
    const response = await BudgetApi.createRevision(
      budgetId,
      payload,
      keyFor('revision/' + budgetId, payload)
    )
    if (!accept(requestId, scope)) return
    if (!canEdit.value || response.budgetId !== budgetId || !validId(response.revisionId))
      throw Error('修订响应上下文无效，请刷新核对')
    applyDetail(response)
    message.success('已保存为新预算修订，未发布对外报价')
    void loadHistory()
    if (route.query.revisionId) {
      saving.value = false
      try {
        await router.replace({ path: '/zs/budget-estimates/' + budgetId, query: {} })
      } catch {
        loadError.value = '修订已保存，但地址更新失败；请从列表重新打开当前预算'
      }
    }
  } catch (error) {
    if (accept(requestId, scope)) saveError.value = mutationError(error)
  } finally {
    if (requestId === sequence) saving.value = false
  }
}
const templateVisible = ref(false),
  templateTarget = ref<BudgetLine | null>(null),
  templateError = ref('')
const templateForm = reactive({ code: '', name: '', reason: '' })
function openTemplate(line: WorkLine) {
  if (
    !editable() ||
    !canTemplate.value ||
    dirty.value ||
    line.original?.source !== 'PROJECT_CUSTOM'
  )
    return
  templateTarget.value = { ...line.original }
  Object.assign(templateForm, { code: '', name: line.publicName, reason: '' })
  templateError.value = ''
  templateVisible.value = true
}
async function saveTemplate() {
  if (!editable() || !canTemplate.value || !detail.value || !templateTarget.value || dirty.value)
    return
  templateError.value = ''
  let payload
  try {
    payload = templatePayload(
      detail.value,
      templateTarget.value,
      templateForm.code,
      templateForm.name,
      templateForm.reason
    )
  } catch (error) {
    templateError.value = errorText(error)
    return
  }
  const scope = actor(),
    requestId = sequence,
    budgetId = detail.value.budgetId,
    lineId = templateTarget.value.lineId
  saving.value = true
  try {
    const result = await BudgetApi.saveLineTemplate(
      budgetId,
      lineId,
      payload,
      keyFor('template/' + budgetId + '/' + lineId, payload)
    )
    if (!accept(requestId, scope)) return
    if (!validId(result.itemId) || result.enabled || result.publicSelectable)
      throw Error('模板响应状态无效，请刷新核对')
    pendingCommand = undefined
    templateVisible.value = false
    message.success('已创建禁用私有模板草稿；请到预算配置补充选项、数量规则与地区价格')
  } catch (error) {
    if (accept(requestId, scope)) templateError.value = mutationError(error)
  } finally {
    if (requestId === sequence) saving.value = false
  }
}
watch(
  () => [route.params.budgetId, route.query.revisionId, userStore.getUser.id, canQuery.value],
  () => {
    void load()
  }
)
let approvedNavigation = false
async function routeGuard() {
  if (saving.value) return false
  return approvedNavigation || (await confirmDiscard())
}
onBeforeRouteLeave(routeGuard)
onBeforeRouteUpdate(routeGuard)
onBeforeUnmount(clear)
onMounted(load)
</script>

<style scoped lang="scss">
.revision-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.revision-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}

.template-choice {
  width: 100%;
  margin: 16px 0;
}

:deep(.removed-line) {
  color: #999;
  text-decoration: line-through;
}
</style>

<style lang="scss" scoped>
.zs-line-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}

.zs-revision-switch {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 10px 16px;
  margin-bottom: 16px;
}

.zs-sticky-save {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px 16px;
  margin-top: 16px;
  background: #fff;
  border: 1px solid #e6e0d4;
  border-radius: 8px;
  box-shadow: 0 -4px 12px rgb(0 0 0 / 8%);
}

.zs-sticky-hint {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: #a0561f;
  white-space: nowrap;
}
</style>
