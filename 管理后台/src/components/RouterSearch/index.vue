<template>
  <ElDialog v-if="isModal" v-model="showSearch" :show-close="false" title="菜单搜索">
    <el-select
      filterable
      :reserve-keyword="false"
      remote
      placeholder="请输入菜单内容"
      :remote-method="remoteMethod"
      style="width: 100%"
      @change="handleChange"
    >
      <el-option
        v-for="item in options"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>
  </ElDialog>
  <div v-else class="custom-hover" @click.stop="showTopSearch = !showTopSearch">
    <Icon icon="ep:search" :color="color" />
    <el-select
      @click.stop
      filterable
      :reserve-keyword="false"
      remote
      placeholder="请输入菜单内容"
      :remote-method="remoteMethod"
      class="overflow-hidden transition-all-600"
      :class="showTopSearch ? '!w-220px ml2' : '!w-0'"
      @change="handleChange"
    >
      <el-option
        v-for="item in options"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>
  </div>
</template>

<script lang="ts" setup>
import { propTypes } from '@/utils/propTypes'
import { usePermissionStore } from '@/store/modules/permission'
import { searchProjectMenus } from '@/utils/projectNavigation'
defineProps({
  isModal: {
    type: Boolean,
    default: true
  },
  color: propTypes.string.def('')
})

const router = useRouter() // 路由对象
const showSearch = ref(false) // 是否显示弹框
const showTopSearch = ref(false) // 是否显示顶部搜索框
const value: Ref = ref('') // 用户输入的值

const permissionStore = usePermissionStore()
// 与侧边栏同源，不能从全部已注册路由中重新搜出无关模块或隐藏详情页。
const options = computed(() => searchProjectMenus(permissionStore.getRouters, value.value))

function remoteMethod(data) {
  // 这里可以执行相应的操作（例如打开搜索框等）
  value.value = data
}

function handleChange(path) {
  router.push({ path })
  hiddenSearch()
  hiddenTopSearch()
}

function hiddenSearch() {
  showSearch.value = false
}

function hiddenTopSearch() {
  showTopSearch.value = false
}

onMounted(() => {
  window.addEventListener('keydown', listenKey)
  window.addEventListener('click', hiddenTopSearch)
})

onUnmounted(() => {
  window.removeEventListener('keydown', listenKey)
  window.removeEventListener('click', hiddenTopSearch)
})

// 监听 ctrl + k
function listenKey(event) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'k') {
    // 阻止触发浏览器默认事件
    event.preventDefault()
    showSearch.value = !showSearch.value
    // 这里可以执行相应的操作（例如打开搜索框等）
  }
}

defineExpose({
  openSearch: () => {
    showSearch.value = true
  }
})
</script>
