/**
 * QDate native bridge — injected by MainActivity on every app-host page load.
 * Coordinates the AdMob banner spacer with the web app's flex-column layout
 * (templates/app.php: #app = .top-bar + .views + .bottom-nav), counts profile
 * browses for the interstitial, and syncs the FCM token with the backend.
 * The native side ignores bridge calls unless the current page is on the app
 * host.
 */
(function () {
  if (window.__qdateBridgeInstalled) return;
  window.__qdateBridgeInstalled = true;

  var native = window.QDateNative;
  if (!native) return;

  var FLAME = window.FLAME || {};
  var base = FLAME.base || "";
  var navHeightReported = 0;

  /* ---------- Banner slot: a spacer above .bottom-nav ---------- */

  function ensureSlot() {
    var app = document.getElementById("app");
    var nav = document.querySelector(".bottom-nav");
    if (!app || !nav) return null;
    var slot = document.getElementById("native-banner-slot");
    // Insert only once native has reported a real banner height (i.e. an ad has
    // loaded) — otherwise the page would reserve space for nothing.
    if (typeof window.__qdateBannerHeight !== "number") return null;
    if (!slot || slot.parentNode !== app || slot.nextElementSibling !== nav) {
      if (slot) slot.remove();
      slot = document.createElement("div");
      slot.id = "native-banner-slot";
      slot.style.height = window.__qdateBannerHeight + "px";
      slot.style.flexShrink = "0";
      app.insertBefore(slot, nav);
    }
    return slot;
  }

  // Native reports the real banner height once the ad has loaded and sized.
  window.__qdateSetBannerHeight = function (px) {
    px = Math.max(0, Math.round(Number(px) || 0));
    window.__qdateBannerHeight = px;
    if (px <= 0) {
      var old = document.getElementById("native-banner-slot");
      if (old) old.remove();
      return;
    }
    var slot = document.getElementById("native-banner-slot");
    if (slot) {
      slot.style.height = px + "px";
    } else {
      ensureSlot();
    }
  };

  /* ---------- Nav geometry reporting ---------- */

  function navVisible(nav) {
    // offsetParent is null for display:none elements (the nav is a flex
    // child, not position:fixed, so this check is reliable here).
    return !!(nav && nav.offsetParent !== null && nav.offsetHeight > 0);
  }

  function reportNav() {
    var nav = document.querySelector(".bottom-nav");
    var visible = navVisible(nav);
    if (visible) {
      ensureSlot();
      var h = Math.round(nav.getBoundingClientRect().height);
      if (h > 0 && h !== navHeightReported) {
        navHeightReported = h;
        try { native.setNavHeight(h); } catch (e) {}
      }
    } else {
      var slot = document.getElementById("native-banner-slot");
      if (slot) slot.remove();
      navHeightReported = 0;
    }
    try { native.setNavVisible(visible); } catch (e) {}
  }

  function startObserver() {
    reportNav();
    var app = document.getElementById("app");
    if (app && typeof MutationObserver !== "undefined") {
      new MutationObserver(reportNav).observe(app, {
        attributes: true,
        attributeFilter: ["class"],
        childList: true,
        subtree: true,
      });
    }
    // Belt and braces: the chat page hides the nav via CSS class change the
    // observer covers, but resizes/safe-area changes are not DOM events.
    setInterval(reportNav, 2000);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", startObserver);
  } else {
    startObserver();
  }

  /* ---------- Profile browse counting (interstitial) ---------- */

  try {
    var origFetch = window.fetch;
    window.fetch = function () {
      var args = arguments;
      var url = String(args[0] || "");
      var opts = args[1] || {};
      var isSwipe =
        (opts.method || "GET").toUpperCase() === "POST" &&
        url.indexOf("/api/swipe") !== -1;
      return origFetch.apply(this, args).then(function (res) {
        if (isSwipe && res.ok) {
          try { native.onProfileBrowsed(); } catch (e) {}
        }
        return res;
      });
    };
  } catch (e) {}

  // Opening a full profile page (/user/{id}) counts as a browse too.
  if (base && location.pathname.indexOf(base + "/user/") === 0) {
    try { native.onProfileBrowsed(); } catch (e) {}
  }

  /* ---------- FCM token registration sync ---------- */

  var lastTokenAttempt = "";
  var tokenRetryTimer = null;

  function syncToken(token) {
    if (!token || !origFetch) return;
    if (token === lastTokenAttempt) return;
    lastTokenAttempt = token;
    var body = new URLSearchParams();
    if (FLAME.uid && FLAME.csrf) {
      body.set("_csrf", FLAME.csrf);
      body.set("token", token);
      origFetch(base + "/api/push/register", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: body.toString(),
        credentials: "same-origin",
      }).then(function (res) {
        if (!res.ok) {
          lastTokenAttempt = "";
          console.warn("QDate push token registration failed:", res.status);
          retryTokenSync();
        }
      }).catch(function () {
        lastTokenAttempt = "";
        retryTokenSync();
      });
    } else {
      // Logged out (auth page): drop the binding so pushes stop.
      body.set("token", token);
      origFetch(base + "/api/push/unregister", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: body.toString(),
        credentials: "same-origin",
      }).catch(function () {});
    }
  }

  function retryTokenSync() {
    if (!window.__qdateFcmToken || !FLAME.uid || !FLAME.csrf) return;
    if (tokenRetryTimer) clearTimeout(tokenRetryTimer);
    tokenRetryTimer = setTimeout(function () {
      tokenRetryTimer = null;
      lastTokenAttempt = "";
      syncToken(window.__qdateFcmToken);
    }, 1500);
  }

  // Native can call this at any time (token arrives after page load, or
  // rotates while the app is running).
  window.__qdateSetFcmToken = function (token) {
    window.__qdateFcmToken = token;
    lastTokenAttempt = "";
    syncToken(token);
  };

  // Native injects window.__qdateFcmToken before this script runs.
  if (window.__qdateFcmToken) syncToken(window.__qdateFcmToken);
  window.addEventListener("pageshow", retryTokenSync);
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible") retryTokenSync();
  });
})();
