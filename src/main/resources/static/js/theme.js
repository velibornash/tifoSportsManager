// theme.js
//
// Palette switching for the manager's UI (redesign.md R-1/R-2).
//
// Three palettes ship: "midnight" (today's palette, the default), "ember"
// (warm dark + amber) and "pitch" (green-black + lime). All colours live in
// dashboard.css as CSS custom properties; applying a palette is just setting
// data-theme on <html>, which the browser resolves as an instant repaint -
// no re-render, no re-fetch.
//
// Every page that loads dashboard.css runs the inline pre-paint snippet in
// its <head> BEFORE the stylesheet link, so the first paint already carries
// the saved choice and there is no flash of the wrong palette. This module
// owns everything after that: validation, persistence, runtime switching.

(function () {
    'use strict';

    var VALID = ['midnight', 'ember', 'pitch', 'daylight'];
    var DEFAULT = 'midnight';
    // Daylight is the light palette the owner asked for (2026-10-19); the other
    // three are dark, so prefers-color-scheme still has no real choice to make.
    var CHANGE_EVENT = 'fm-theme-change';

    function isValid(theme) {
        return VALID.indexOf(theme) !== -1;
    }

    /**
     * The signed-in account, taken from the session token's `sub` claim.
     * The claim is the account's own e-mail (measured 2026-10-19 on a real
     * token), which is exactly the stable per-user identity the palette switch
     * is namespaced on — no extra request, and it is available before paint.
     * There used to be a single device-wide key, which meant two users of one
     * browser had to share a palette; the owner asked for that to go.
     */
    function subject() {
        try {
            var token = sessionStorage.getItem('token') || '';
            var part = token.split('.')[1] || '';
            if (!part) return '';
            var pad = part.replace(/-/g, '+').replace(/_/g, '/');
            var payload = JSON.parse(atob(pad.length % 4 ? pad + ' '.repeat(4 - pad.length % 4) : pad));
            var sub = payload && payload.sub;
            return typeof sub === 'string' ? sub : '';
        } catch (error) {
            return '';
        }
    }

    function storageKey() {
        if (typeof window.__fmThemeKeyOverride === 'string') return window.__fmThemeKeyOverride;
        var who = subject();
        return who ? 'fm-theme:' + who : 'fm-theme:device';
    }

    function stored() {
        try {
            var value = localStorage.getItem(storageKey());
            return isValid(value) ? value : null;
        } catch (error) {
            return null;
        }
    }

    function apply(theme) {
        if (!isValid(theme)) theme = DEFAULT;
        if (theme === DEFAULT) {
            document.documentElement.removeAttribute('data-theme');
        } else {
            document.documentElement.setAttribute('data-theme', theme);
        }
    }

    function get() {
        return stored() || DEFAULT;
    }

    function set(theme) {
        if (!isValid(theme)) return get();
        try {
            localStorage.setItem(storageKey(), theme);
            // The old device-wide key is retired, not migrated: migrating it would
            // copy whoever used the browser last onto the named users, which is the
            // very bleed we are removing.
            localStorage.removeItem('fm-theme');
        } catch (error) {
            // Storage may be unavailable (private mode); the choice then just
            // lives for the session instead of being persisted.
        }
        apply(theme);
        try {
            window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: { theme: theme } }));
        } catch (error) {
            // A missing CustomEvent constructor must not break the switch.
        }
        return theme;
    }

    // Pre-paint helpers for the inline <head> snippet, exposed so the snippet
    // stays a three-liner and the logic is testable in one place.
    function applyBeforePaint() {
        apply(stored() || DEFAULT);
    }

    // ---- Shared switcher wiring (redesign.md R-2) ----
    // The Settings cards and the drawer dots hold no logic of their own: the
    // delegated listeners below read the palette from the control's value, and
    // the active-state marking runs off the change event so every visible
    // picker stays in step no matter which one was touched.

    function markAll() {
        var current = get();
        document.querySelectorAll('.fm-theme-dot').forEach(function (dot) {
            dot.classList.toggle('is-active', dot.getAttribute('data-theme-dot') === current);
        });
        document.querySelectorAll('.fm-appearance-card').forEach(function (card) {
            card.classList.toggle('is-active', card.getAttribute('data-appearance-card') === current);
            var input = card.querySelector('input[data-theme-option]');
            if (input) input.checked = card.getAttribute('data-appearance-card') === current;
        });
    }

    // Marks a scoped root (one rendered page); exposed because loadSettingsPage
    // calls it right after building the cards, before any change event fires.
    function markCards(root) {
        var current = get();
        (root || document).querySelectorAll('[data-appearance-card]').forEach(function (card) {
            card.classList.toggle('is-active', card.getAttribute('data-appearance-card') === current);
        });
    }

    document.addEventListener('change', function (event) {
        var input = event.target;
        if (input && input.matches && input.matches('input[data-theme-option]') && input.checked) {
            set(input.value);
        }
    });

    document.addEventListener('click', function (event) {
        var dot = event.target && event.target.closest && event.target.closest('.fm-theme-dot');
        if (dot) set(dot.getAttribute('data-theme-dot'));
    });

    window.addEventListener(CHANGE_EVENT, markAll);

    // Initial marking for the static drawer dots.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', markAll);
    } else {
        markAll();
    }

    window.FmTheme = {
        get: get,
        set: set,
        apply: apply,
        markCards: markCards,
        VALID: VALID,
        storageKey: storageKey,
        CHANGE_EVENT: CHANGE_EVENT,
    };

    // The inline <head> snippet should have applied the saved palette before
    // the stylesheet finished loading; re-applying here is idempotent and
    // covers any page that licences this module but not the snippet.
    applyBeforePaint();
})();
