// Hosted backend so the extension works on a fresh install with nothing running locally.
const DEFAULT_BACKEND = "https://scanshield-ii9n.onrender.com";
const toggleEl = document.getElementById("toggle");
const backendInput = document.getElementById("backendUrl");
const saveBtn = document.getElementById("saveBtn");
const rescanBtn = document.getElementById("rescanBtn");
const statusEl = document.getElementById("status");
const testUrl = document.getElementById("testUrl");
const testBtn = document.getElementById("testBtn");
const testOut = document.getElementById("testOut");

let enabled = true;

function setStatus(msg, ok = true) {
  statusEl.textContent = msg;
  statusEl.className = "status " + (ok ? "ok" : "err");
  setTimeout(() => (statusEl.className = "status"), 3500);
}

function renderToggle() {
  toggleEl.classList.toggle("on", enabled);
  toggleEl.setAttribute("aria-checked", String(enabled));
}

chrome.storage.sync.get({ enabled: true, backendUrl: DEFAULT_BACKEND }, (cfg) => {
  enabled = cfg.enabled;
  backendInput.value = cfg.backendUrl;
  renderToggle();
});

toggleEl.addEventListener("click", () => {
  enabled = !enabled;
  renderToggle();
  chrome.storage.sync.set({ enabled });
  // notify content scripts
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs[0]) chrome.tabs.sendMessage(tabs[0].id, { type: "SCAMSHIELD_TOGGLE", enabled }).catch(()=>{});
  });
  setStatus(enabled ? "Scanning enabled" : "Scanning disabled");
});
toggleEl.addEventListener("keydown", (e) => {
  if (e.key === "Enter" || e.key === " ") {
    e.preventDefault();
    toggleEl.click();
  }
});

saveBtn.addEventListener("click", () => {
  const url = backendInput.value.trim() || DEFAULT_BACKEND;
  // basic validation
  try {
    new URL(url);
  } catch {
    setStatus("Invalid URL — must be http(s)://...", false);
    return;
  }
  chrome.storage.sync.set({ backendUrl: url, enabled }, () => {
    setStatus("Saved — rescanning page…");
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs[0]) {
        chrome.tabs.sendMessage(tabs[0].id, { type: "SCAMSHIELD_RESCAN", clear: true }).catch(() => {
          // content script may not be injected on chrome:// — reload fallback
          chrome.tabs.reload(tabs[0].id);
        });
      }
    });
  });
});

rescanBtn.addEventListener("click", () => {
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs[0]) {
      chrome.tabs.sendMessage(tabs[0].id, { type: "SCAMSHIELD_RESCAN", clear: true }).catch(() => {
        if (tabs[0].url.startsWith("http")) setStatus("Could not reach page — try reload", false);
      });
      setStatus("Re-scan triggered");
    }
  });
});

testBtn.addEventListener("click", async () => {
  const backend = (backendInput.value.trim() || DEFAULT_BACKEND).replace(/\/$/, "");
  const url = testUrl.value.trim();
  if (!url) return;
  testBtn.textContent = "Checking…";
  testOut.style.display = "block";
  testOut.textContent = `POST ${backend}/scan-url\n{ url: ${url} }\n…`;
  try {
    const resp = await fetch(`${backend}/scan-url`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    });
    const data = await resp.json();
    testOut.textContent = JSON.stringify(data, null, 2);
    if (!resp.ok) testOut.textContent = `HTTP ${resp.status}\n` + testOut.textContent;
  } catch (e) {
    testOut.textContent = `Failed: ${e.message}\nIs backend running at ${backend}?`;
  } finally {
    testBtn.textContent = "Test this URL";
  }
});
