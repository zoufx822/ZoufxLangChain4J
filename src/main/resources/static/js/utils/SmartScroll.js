/**
 * 智能滚动
 * 规则：滚动后检查是否在底部 → 在底部则恢复自动跟随，否则暂停
 * 无 timer，无方向检测
 */
class SmartScroll {
    #el;
    #paused = false;
    #onScroll;

    constructor(el, {threshold = 80} = {}) {
        this.#el = el;
        this.#onScroll = () => {
            this.#paused = !this.#isNearBottom(threshold);
        };
        el.addEventListener('scroll', this.#onScroll, {passive: true});
    }

    #isNearBottom(threshold) {
        const {scrollTop, scrollHeight, clientHeight} = this.#el;
        return scrollHeight - scrollTop - clientHeight <= threshold;
    }

    /** 若未暂停则滚到底部（流式输出时调用） */
    scrollToBottom() {
        if (!this.#paused) {
            this.#el.scrollTop = this.#el.scrollHeight;
        }
    }

    /** 强制滚到底部并恢复自动跟随（发送新消息时调用） */
    forceScrollToBottom() {
        this.#paused = false;
        this.#el.scrollTop = this.#el.scrollHeight;
    }

    destroy() {
        this.#el.removeEventListener('scroll', this.#onScroll);
    }
}

export default SmartScroll;
