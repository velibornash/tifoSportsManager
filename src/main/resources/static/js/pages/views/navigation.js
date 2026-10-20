// pages/views/navigation.js
// Navigation state management extracted from pages.js

export function createNavigationManager() {
    let currentPageId = 'dashboard';
    let currentNavState = { type: 'dashboard' };
    const navHistoryStack = [];
    let navReplayMode = false;
    let navBusy = false;

    function sameNavState(a, b) {
        if (!a || !b) return false;
        if (a.type !== b.type) return false;
        return JSON.stringify(a) === JSON.stringify(b);
    }

    function pushNavState(nextState) {
        if (navReplayMode) return;
        if (sameNavState(currentNavState, nextState)) return;
        if (currentNavState) navHistoryStack.push(currentNavState);
        if (navHistoryStack.length > 50) navHistoryStack.shift();
        currentNavState = nextState;
    }

    function getNavState() {
        return currentNavState;
    }

    function getPageId() {
        return currentPageId;
    }

    function setPageId(id) {
        currentPageId = id;
    }

    function getHistoryLength() {
        return navHistoryStack.length;
    }

    return {
        pushNavState,
        getNavState,
        setNavState: (s) => { currentNavState = s; },
        getPageId,
        setPageId,
        getHistoryLength,
        getHistoryStack: () => navHistoryStack,
        setNavReplayMode: (v) => { navReplayMode = v; },
        isNavReplayMode: () => navReplayMode,
        isNavBusy: () => navBusy,
        setNavBusy: (v) => { navBusy = v; },
        sameNavState,
    };
}
