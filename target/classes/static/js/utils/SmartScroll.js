/**
 * 智能滚动（事件驱动）
 *
 * 暂停跟随：wheel 向上 / touchmove 向下滑 → 同步立即置 paused=true
 * 恢复跟随：用户向下滚至底部 5px 内
 *
 * 关键：用 #pendingScrollEvents 计数器区分 programmatic 与用户的 scroll 事件，
 * 避免 programmatic scroll event 在 wheel event 之后到达时误重置 paused。
 */
class SmartScroll {
    #el;
    #paused = false;
    #pendingScrollEvents = 0;
    #lastScrollTop = 0;
    #touchStartY = 0;
    #resumeThreshold;
    #onWheel;
    #onTouchStart;
    #onTouchMove;
    #onScroll;

    constructor(el, {resumeThreshold = 5} = {}) {
        this.#el = el;
        this.#lastScrollTop = el.scrollTop;
        this.#resumeThreshold = resumeThreshold;

        this.#onWheel = (e) => {
            if (e.deltaY < 0) this.#paused = true;
        };

        this.#onTouchStart = (e) => {
            this.#touchStartY = e.touches[0].clientY;
        };

        this.#onTouchMove = (e) => {
            if (e.touches[0].clientY > this.#touchStartY) this.#paused = true;
        };

        this.#onScroll = () => {
            // programmatic 触发的 scroll event：消费一个待处理计数后返回
            if (this.#pendingScrollEvents > 0) {
                this.#pendingScrollEvents--;
                this.#lastScrollTop = this.#el.scrollTop;
                return;
            }
            // 此时一定是用户事件（暂停期间没有 programmatic scroll）
            const {scrollTop, scrollHeight, clientHeight} = this.#el;
            const scrollingDown = scrollTop > this.#lastScrollTop;
            const dist = scrollHeight - scrollTop - clientHeight;
            if (this.#paused && scrollingDown && dist <= this.#resumeThreshold) {
                this.#paused = false;
            }
            this.#lastScrollTop = scrollTop;
        };

        el.addEventListener('wheel', this.#onWheel, {passive: true});
        el.addEventListener('touchstart', this.#onTouchStart, {passive: true});
        el.addEventListener('touchmove', this.#onTouchMove, {passive: true});
        el.addEventListener('scroll', this.#onScroll, {passive: true});
    }

    /** 若未暂停则滚到底部（流式输出每个 token 调用） */
    scrollToBottom() {
        if (this.#paused) return;
        const before = this.#el.scrollTop;
        this.#el.scrollTop = this.#el.scrollHeight;
        if (this.#el.scrollTop !== before) this.#pendingScrollEvents++;
    }

    /** 强制滚到底并恢复跟随（发送新消息 / 切换会话调用） */
    forceScrollToBottom() {
        this.#paused = false;
        const before = this.#el.scrollTop;
        this.#el.scrollTop = this.#el.scrollHeight;
        if (this.#el.scrollTop !== before) this.#pendingScrollEvents++;
    }

    destroy() {
        this.#el.removeEventListener('wheel', this.#onWheel);
        this.#el.removeEventListener('touchstart', this.#onTouchStart);
        this.#el.removeEventListener('touchmove', this.#onTouchMove);
        this.#el.removeEventListener('scroll', this.#onScroll);
    }
}

export default SmartScroll;
