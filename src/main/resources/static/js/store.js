/**
 * 多会话共享状态
 * sessions、currentSessionId、isLoading 由 ChatWindow 和 Sidebar 共享
 */

import MarkdownRenderer from './utils/MarkdownRenderer.js';

const md = MarkdownRenderer.getInstance();

const WELCOME_CONTENT = '你好！我是 AI 助手，有什么可以帮助你的吗？';

function makeWelcomeMsg() {
    return {
        role: 'ai',
        content: WELCOME_CONTENT,
        thinking: '',
        thinkingExpanded: false,
        html: md.render(WELCOME_CONTENT),
        streaming: false,
    };
}

function generateId() {
    return typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : Math.random().toString(36).slice(2);
}

// ── 核心状态 ──────────────────────────────────────────────────────────────────
export const sessions = Vue.reactive([
    {id: generateId(), title: '新对话', messages: Vue.reactive([makeWelcomeMsg()]), createdAt: Date.now()}
]);

export const currentSessionId = Vue.ref(sessions[0].id);

export const currentSession = Vue.computed(() =>
    sessions.find(s => s.id === currentSessionId.value)
);

/** 是否正在加载（生成中），由 ChatWindow 写入，Sidebar 读取用于禁用操作 */
export const isLoading = Vue.ref(false);

/** 移动端侧边栏展开状态 */
export const mobileSidebarOpen = Vue.ref(false);

export function toggleMobileSidebar() {
    mobileSidebarOpen.value = !mobileSidebarOpen.value;
}

export function closeMobileSidebar() {
    mobileSidebarOpen.value = false;
}

// ── 操作函数 ──────────────────────────────────────────────────────────────────

export function createSession() {
    const s = {
        id: generateId(),
        title: '新对话',
        messages: Vue.reactive([makeWelcomeMsg()]),
        createdAt: Date.now(),
    };
    sessions.unshift(s);
    currentSessionId.value = s.id;
}

export function switchSession(id) {
    if (id !== currentSessionId.value) {
        currentSessionId.value = id;
    }
}

export function deleteSession(id) {
    if (sessions.length === 1) return; // 至少保留一个
    const idx = sessions.findIndex(s => s.id === id);
    if (idx === -1) return;
    sessions.splice(idx, 1);
    if (currentSessionId.value === id) {
        currentSessionId.value = sessions[Math.min(idx, sessions.length - 1)].id;
    }
}

/** 将会话标题设为用户首条消息（只改一次，保持"新对话"时才改） */
export function updateSessionTitle(id, title) {
    const s = sessions.find(s => s.id === id);
    if (s && s.title === '新对话') {
        s.title = title.slice(0, 20);
    }
}

/** 格式化相对时间 */
export function relativeTime(ts) {
    const diff = Math.floor((Date.now() - ts) / 1000);
    if (diff < 60) return '刚刚';
    if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
    if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
    return `${Math.floor(diff / 86400)} 天前`;
}
