/**
 * ScamShield background service worker.
 * Tracks high-risk counts per tab to set badge, and handles future App ↔ Extension sync.
 */

const tabRiskCounts = new Map();

// Listen for results from content scripts
chrome.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === "SCAMSHIELD_RESULT" && sender.tab) {
    const tabId = sender.tab.id;
    const prev = tabRiskCounts.get(tabId) || { high: 0, medium: 0 };
    const risk = (msg.result && msg.result.risk || "").toLowerCase();
    if (risk === "high") prev.high += 1;
    else if (risk === "medium") prev.medium += 1;
    tabRiskCounts.set(tabId, prev);

    const totalHigh = prev.high;
    if (totalHigh > 0) {
      chrome.action.setBadgeText({ tabId, text: String(totalHigh) });
      chrome.action.setBadgeBackgroundColor({ tabId, color: "#e53935" });
    } else if (prev.medium > 0) {
      chrome.action.setBadgeText({ tabId, text: String(prev.medium) });
      chrome.action.setBadgeBackgroundColor({ tabId, color: "#fb8c00" });
    }
  }
});

// Clear on navigation
chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === "loading") {
    tabRiskCounts.delete(tabId);
    chrome.action.setBadgeText({ tabId, text: "" });
  }
});

// Optional future: poll backend flag that mirrors Android App's "extension enabled" setting.
// If user enables in the app, backend could expose GET /extension/config { enabled: bool }
// The background could sync chrome.storage.sync.enabled from that poll.
// Left as hook for demo — see popup for manual toggle today.
