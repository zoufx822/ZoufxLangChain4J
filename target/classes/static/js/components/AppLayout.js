/**
 * AppLayout 组件 - 整体布局容器
 */
import {Sidebar} from './Sidebar.js';
import {ChatWindow} from './ChatWindow.js';
import {closeMobileSidebar, mobileSidebarOpen} from '../store.js';

export const AppLayout = {
    name: 'AppLayout',
    components: {
        Sidebar,
        ChatWindow
    },
    setup() {
        return {mobileSidebarOpen, closeMobileSidebar};
    },
    template: `
        <div class="app-layout">
            <Sidebar />
            <!-- 移动端遮罩层，点击关闭侧边栏 -->
            <div class="sidebar-backdrop"
                 v-if="mobileSidebarOpen"
                 @click="closeMobileSidebar">
            </div>
            <main class="main-content">
                <ChatWindow />
            </main>
        </div>
    `
};