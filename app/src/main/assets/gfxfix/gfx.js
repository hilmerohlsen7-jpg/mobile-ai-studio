(function () {
  var W = window.wrappedJSObject, asked = 0, badge = null;
  function show() {
    try {
      if (!badge) {
        badge = document.createElement('div');
        badge.style.cssText = 'position:fixed;left:6px;bottom:6px;z-index:2147483647;background:#D97757;color:#fff;font:11px sans-serif;padding:3px 7px;border-radius:8px;pointer-events:none';
        (document.documentElement || document.body).appendChild(badge);
        setTimeout(function () { if (badge) badge.remove(); badge = null; }, 12000);
      }
      badge.textContent = 'gfx fix: page asked ' + asked + 'x (' + (window === window.top ? 'main page' : 'frame') + ')';
    } catch (e) {}
  }
  function patch(C) {
    if (!C) return;
    var proto = C.prototype, orig = proto.getParameter;
    proto.getParameter = exportFunction(function (p) {
      var v = orig.call(this, p);
      if ((p === 0x8DFB || p === 0x8DFD) && typeof v === 'number') { asked++; show(); if (v < 264) return 264; }
      return v;
    }, window);
  }
  patch(W.WebGLRenderingContext);
  patch(W.WebGL2RenderingContext);

  // background workers: start them with the same fix applied first
  try {
    var prelude = "(function(){['WebGLRenderingContext','WebGL2RenderingContext'].forEach(function(n){var C=self[n];if(!C)return;var o=C.prototype.getParameter;C.prototype.getParameter=function(p){var v=o.call(this,p);if((p===0x8DFB||p===0x8DFD)&&typeof v==='number'&&v<264)return 264;return v;};});})();\n";
    var OrigWorker = W.Worker;
    W.Worker = exportFunction(function (url, opts) {
      try {
        var abs = new URL(String(url), location.href).href;
        var mod = opts && opts.type === 'module';
        var src = prelude + (mod ? "import " + JSON.stringify(abs) + ";" : "importScripts(" + JSON.stringify(abs) + ");");
        var blobUrl = URL.createObjectURL(new Blob([src], { type: 'text/javascript' }));
        return new OrigWorker(blobUrl, opts);
      } catch (e) { return new OrigWorker(url, opts); }
    }, window);
    W.Worker.prototype = OrigWorker.prototype;
  } catch (e) {}
})();
