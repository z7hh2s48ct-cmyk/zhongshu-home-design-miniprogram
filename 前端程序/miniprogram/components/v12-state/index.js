"use strict";

// 统一的空 / 加载 / 错误态展示组件。收敛各页各写各的内联态（record-empty / message-empty /
// empty-tip / state-card），保证 loading·empty·error 三态的图标、主文案、次要说明与动作按钮布局一致。
// 卡片背景与动作按钮样式在组件内自带（不依赖页面级 .v12-card / .record-button），避免样式隔离下失效。
Component({
  properties: {
    // loading | empty | error：决定 text 留空时的默认文案与语义
    status: { type: String, value: 'empty' },
    // 主文案；留空则回落到 status 默认文案
    text: { type: String, value: '' },
    // 次要说明（如预算页「无需重新生成AI方案」）；留空不渲染
    hint: { type: String, value: '' },
    // t-icon 名称；留空不渲染图标
    icon: { type: String, value: '' },
    // 动作按钮文案（如「重试」「重新加载」）；留空不渲染按钮，点击派发 action 事件由页面处理
    actionText: { type: String, value: '' },
    // 是否用卡片背景包裹；library 等纯文本场景传 false
    card: { type: Boolean, value: true }
  },
  data: {
    defaults: { loading: '正在加载…', empty: '暂无内容', error: '加载失败，请重试' }
  },
  methods: {
    onAction() { this.triggerEvent('action'); }
  }
});
