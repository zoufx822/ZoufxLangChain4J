/**
 * Sidebar 组件 - 多会话列表
 * 支持新建、切换、删除会话；生成中时禁用操作
 */
import {
    closeMobileSidebar,
    createSession,
    currentSessionId,
    deleteSession,
    isLoading,
    mobileSidebarOpen,
    relativeTime,
    sessions,
    switchSession,
} from '../store.js';

export const Sidebar = {
    name: 'Sidebar',
    setup() {
        // 相对时间每分钟刷新
        const tick = Vue.ref(0);
        let timer = null;
        Vue.onMounted(() => {
            timer = setInterval(() => {
                tick.value++;
            }, 60000);
        });
        Vue.onUnmounted(() => clearInterval(timer));

        const timeOf = (ts) => {
            void tick.value;
            return relativeTime(ts);
        };

        const handleCreate = () => {
            if (!isLoading.value) createSession();
        };
        const handleSwitch = (id) => {
            if (!isLoading.value) switchSession(id);
        };
        const handleDelete = (e, id) => {
            e.stopPropagation();
            if (!isLoading.value) deleteSession(id);
        };

        const handleSwitch_ = (id) => {
            handleSwitch(id);
            closeMobileSidebar();
        };
        const handleCreate_ = () => {
            handleCreate();
            closeMobileSidebar();
        };

        return {
            sessions, currentSessionId, isLoading, mobileSidebarOpen, timeOf,
            handleCreate: handleCreate_, handleSwitch: handleSwitch_, handleDelete, closeMobileSidebar
        };
    },
    template: `
        <aside class="sidebar" :class="{ 'mobile-open': mobileSidebarOpen }">
            <div class="sidebar-header">
                <h1>AI 对话</h1>
                <div class="sidebar-subtitle">专业AI助手</div>
                <button class="sidebar-close-btn" @click="closeMobileSidebar" aria-label="关闭侧边栏">✕</button>
            </div>

            <div class="new-session-btn-wrap">
                <button class="new-session-btn"
                        @click="handleCreate"
                        :disabled="isLoading"
                        title="新建对话">
                    <span class="new-session-icon">＋</span>
                    <span class="new-session-label">新建对话</span>
                </button>
            </div>

            <div class="sessions-list">
                <div
                    v-for="s in sessions"
                    :key="s.id"
                    class="session-item"
                    :class="{ active: s.id === currentSessionId, disabled: isLoading && s.id !== currentSessionId }"
                    @click="handleSwitch(s.id)"
                    :title="s.title"
                >
                    <div class="session-icon">💬</div>
                    <div class="session-info">
                        <div class="session-title">{{ s.title }}</div>
                        <div class="session-time">{{ timeOf(s.createdAt) }}</div>
                    </div>
                    <button
                        v-if="sessions.length > 1"
                        class="session-delete-btn"
                        @click="handleDelete($event, s.id)"
                        :disabled="isLoading"
                        title="删除此对话"
                    >×</button>
                </div>
            </div>

            <div class="sidebar-footer">
                <div class="status-indicator">
                    <span class="status-dot" :class="{ online: !isLoading }"></span>
                    <span>{{ isLoading ? 'AI 生成中…' : 'AI 在线' }}</span>
                </div>
            </div>
        </aside>
    `,
};
