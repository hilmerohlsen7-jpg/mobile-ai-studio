// Adds the graphics fix to the start of every background-worker script as it downloads,
// so the worker keeps its normal address (and its relative file requests keep working).
var prelude = "(function(){['WebGLRenderingContext','WebGL2RenderingContext'].forEach(function(n){var C=self[n];if(!C)return;var o=C.prototype.getParameter;C.prototype.getParameter=function(p){var v=o.call(this,p);if((p===0x8DFB||p===0x8DFD)&&typeof v==='number'&&v<264)return 264;return v;};});})();\n";
var enc = new TextEncoder();
var workerReqs = new Set();
browser.webRequest.onBeforeSendHeaders.addListener(function (d) {
  (d.requestHeaders || []).forEach(function (h) {
    if (h.name.toLowerCase() === 'sec-fetch-dest' && /worker/i.test(h.value || '')) workerReqs.add(d.requestId);
  });
}, { urls: ["<all_urls>"] }, ["requestHeaders"]);
browser.webRequest.onHeadersReceived.addListener(function (d) {
  if (!workerReqs.has(d.requestId)) return;
  workerReqs.delete(d.requestId);
  if (d.statusCode !== 200) return;
  var f = browser.webRequest.filterResponseData(d.requestId), first = true;
  f.ondata = function (e) { if (first) { first = false; f.write(enc.encode(prelude)); } f.write(e.data); };
  f.onstop = function () { f.close(); };
}, { urls: ["<all_urls>"] }, ["blocking", "responseHeaders"]);
