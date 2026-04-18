/**
 * Vue 3 应用入口
 */
import { AppLayout } from './components/AppLayout.js';

const app = Vue.createApp({});

// 注册 AppLayout 组件
app.component('AppLayout', AppLayout);

// 挂载应用
app.mount('#app');
