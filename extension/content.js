/**
 * ScamShield Content Script — scans <a> links and visible URL-like text.
 * Calls backend POST /scan-url for each unique URL and highlights risk.
 * Respects toggle in chrome.storage.sync { enabled, backendUrl }.
 *
 * When enabled in ScamShield app, user flips toggle ON here (or via future sync with backend flag).
 */

const DEFAULT_BACKEND = "http://localhost:8000";
let scannedUrls = new Set();
let isScanning = false;

async function getConfig() {
  return new Promise(resolve => {
    chrome.storage.sync.get({ enabled: true, backendUrl: DEFAULT_BACKEND }, resolve);
  });
}

function collectLinks() {
  // Collect from <a href> + any URL-like text in page (for plain text phishing)
  const anchors = Array.from(document.querySelectorAll("a[href]"));
  const urls = new Set();

  for (const a of anchors) {
    try {
      const href = a.href && a.href.trim();
      if (!href) continue;
      // Skip internal anchors, mailto, tel, javascript
      if (href.startsWith("javascript:") || href.startsWith("mailto:") || href.startsWith("tel:") || href.startsWith("#")) continue;
      // Skip already scanned
      if (href.length < 8) continue;
      // Only http(s)
      if (!href.startsWith("http://") && !href.startsWith("https://")) continue;
      // Use URL to normalize and skip same-origin # fragments etc? Keep all for now
      urls.add(href);
    } catch (_) {}
  }

  // Also scan visible text for URL patterns that aren't <a> (e.g., pasted phishing link)
  const text = document.body ? document.body.innerText : "";
  const urlRe = /https?:\/\/[^\s<>"']+/gi;
  let m;
  while ((m = urlRe.exec(text)) !== null) {
    let u = m[0].replace(/[.,!;:)}\]"']+$/, "");
    if (u.length > 10) urls.add(u);
  }

  return Array.from(urls);
}

function badgeForRisk(risk) {
  const r = (risk || "low").toLowerCase();
  if (r === "high") return "high";
  if (r === "medium") return "medium";
  return "low";
}

async function checkUrl(url, backendUrl) {
  // Try /scan-url first (extension helper), fallback to /analyze
  try {
    const resp = await fetch(`${backendUrl.replace(/\/$/, "")}/scan-url`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    });
    if (resp.ok) {
      const data = await resp.json();
      // Normalize to { risk, reason }
      const urlRisk = data.urlRisk || {};
      return {
        risk: data.overallRisk || urlRisk.risk || "unknown",
        reason: urlRisk.reason || data.ledger?.riskLevel || "",
        raw: data,
      };
    }
  } catch (e) {
    console.warn("[ScamShield] /scan-url failed, trying /analyze", e);
  }
  // Fallback: POST /analyze with text=url
  try {
    const resp = await fetch(`${backendUrl.replace(/\/$/, "")}/analyze`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text: url }),
    });
    if (resp.ok) {
      const data = await resp.json();
      const urlDetail = (data.details || []).find(d => d.source === "url") || {};
      const textDetail = (data.details || []).find(d => d.source === "text") || {};
      return {
        risk: urlDetail.risk || data.overallRisk || textDetail.risk || "unknown",
        reason: urlDetail.reason || textDetail.reason || "",
        raw: data,
      };
    }
  } catch (e) {
    console.warn("[ScamShield] /analyze fallback failed", e);
  }
  return { risk: "unknown", reason: "backend unreachable", raw: null };
}

function highlightLink(anchor, result) {
  const risk = badgeForRisk(result.risk);
  if (anchor.dataset.scamshieldChecked) return;
  anchor.dataset.scamshieldChecked = "1";

  // Only highlight medium/high to avoid noise; still badge low if you want quiet check
  if (risk === "high") anchor.classList.add("scamshield-high");
  else if (risk === "medium") anchor.classList.add("scamshield-medium");
  else return; // don't annotate low

  // Add badge next to link
  const badge = document.createElement("span");
  badge.className = `scamshield-badge scamshield-badge-${risk}`;
  badge.textContent = risk === "high" ? "⚠ scam risk: high" : "scam risk: medium";
  badge.title = result.reason || "";
  // Click shows tooltip with reason + report action
  badge.style.cursor = "pointer";
  badge.addEventListener("click", (e) => {
    e.preventDefault();
    e.stopPropagation();
    showTooltip(badge, result, anchor.href);
  });
  // Insert after anchor
  try {
    anchor.insertAdjacentElement("afterend", badge);
  } catch (_) {
    anchor.parentNode && anchor.parentNode.appendChild(badge);
  }
  anchor.title = (anchor.title ? anchor.title + " — " : "") + `ScamShield: ${risk} — ${result.reason}`;
}

function showTooltip(el, result, url) {
  // Remove existing
  document.querySelectorAll(".scamshield-tooltip").forEach(n => n.remove());
  const tip = document.createElement("div");
  tip.className = "scamshield-tooltip";
  tip.innerHTML = `
    <div style="font-weight:700;margin-bottom:4px;">ScamShield: ${result.risk.toUpperCase()}</div>
    <div style="opacity:0.9;margin-bottom:6px;">${escapeHtml(result.reason || "No reason")}</div>
    <div style="font-size:11px;opacity:0.7;word-break:break-all;margin-bottom:8px;">${escapeHtml(url)}</div>
    <button style="background:#fff;color:#1e1e1e;border:0;padding:4px 8px;border-radius:6px;font-size:11px;cursor:pointer;">Report as scam</button>
    <span style="float:right;cursor:pointer;opacity:0.6;">✕</span>
  `;
  const rect = el.getBoundingClientRect();
  tip.style.left = Math.min(window.scrollX + rect.left, window.scrollX + window.innerWidth - 300) + "px";
  tip.style.top = window.scrollY + rect.bottom + 6 + "px";
  const close = tip.querySelector("span");
  close.onclick = () => tip.remove();
  const btn = tip.querySelector("button");
  btn.onclick = async () => {
    btn.textContent = "Reporting...";
    const cfg = await getConfig();
    try {
      const r = await fetch(`${cfg.backendUrl.replace(/\/$/, "")}/report`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ numberOrUrl: url, category: "phishing link" }),
      });
      if (r.ok) {
        btn.textContent = "Reported ✓";
        btn.disabled = true;
      } else {
        btn.textContent = "Failed — retry";
      }
    } catch {
      btn.textContent = "Backend unreachable";
    }
  };
  document.body.appendChild(tip);
  setTimeout(() => {
    const handler = (e) => {
      if (!tip.contains(e.target) && e.target !== el) {
        tip.remove();
        document.removeEventListener("click", handler);
      }
    };
    setTimeout(() => document.addEventListener("click", handler), 100);
  }, 0);
}

function escapeHtml(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

async function scanOnce() {
  if (isScanning) return;
  const cfg = await getConfig();
  if (!cfg.enabled) {
    console.log("[ScamShield] scanning disabled (toggle OFF)");
    return;
  }
  const urls = collectLinks();
  const fresh = urls.filter(u => !scannedUrls.has(u));
  if (fresh.length === 0) return;
  isScanning = true;
  console.log(`[ScamShield] scanning ${fresh.length} new URLs via ${cfg.backendUrl}`);
  // Batch with concurrency limit 4 to avoid hammering backend
  const queue = [...fresh];
  const workers = Array.from({ length: Math.min(4, queue.length) }, async () => {
    while (queue.length) {
      const url = queue.shift();
      scannedUrls.add(url);
      const result = await checkUrl(url, cfg.backendUrl);
      // Find all anchors matching this url and highlight
      document.querySelectorAll(`a[href]`).forEach(a => {
        try {
          if (a.href === url || a.href.replace(/\/$/, "") === url.replace(/\/$/, "")) {
            highlightLink(a, result);
          }
        } catch (_) {}
      });
      // Also notify background for badge count
      try {
        chrome.runtime.sendMessage({ type: "SCAMSHIELD_RESULT", url, result });
      } catch (_) {}
      await new Promise(r => setTimeout(r, 80));
    }
  });
  await Promise.all(workers);
  isScanning = false;
  console.log(`[ScamShield] scan complete — checked ${fresh.length} URLs`);
}

// Initial scan + observe SPA navigations and DOM mutations
let debounceTimer = null;
function scheduleScan() {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(scanOnce, 900);
}

scanOnce();
window.addEventListener("load", scheduleScan);

// Detect SPA route changes
let lastHref = location.href;
setInterval(() => {
  if (location.href !== lastHref) {
    lastHref = location.href;
    scannedUrls.clear(); // re-scan on new page
    scheduleScan();
  }
}, 1000);

// Observe DOM for newly added links
const obs = new MutationObserver(scheduleScan);
try {
  obs.observe(document.documentElement, { childList: true, subtree: true });
} catch (_) {}

// Listen for popup toggle messages
chrome.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === "SCAMSHIELD_RESCAN") {
    if (msg.clear) scannedUrls.clear();
    // remove existing highlights if re-scan requested
    document.querySelectorAll(".scamshield-badge").forEach(n => n.remove());
    document.querySelectorAll("[data-scamshield-checked]").forEach(n => {
      n.classList.remove("scamshield-high", "scamshield-medium");
      delete n.dataset.scamshieldChecked;
    });
    scanOnce();
  }
  if (msg && msg.type === "SCAMSHIELD_TOGGLE") {
    if (!msg.enabled) {
      document.querySelectorAll(".scamshield-badge").forEach(n => n.remove());
      document.querySelectorAll("[data-scamshield-checked]").forEach(n => {
        n.classList.remove("scamshield-high", "scamshield-medium");
      });
    } else {
      scannedUrls.clear();
      scanOnce();
    }
  }
});
