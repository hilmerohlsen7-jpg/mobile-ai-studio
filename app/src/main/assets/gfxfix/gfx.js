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
  var logLines = [], logEl = null;
  function logShow(t) {
    try {
      logLines.push(t); if (logLines.length > 7) logLines.shift();
      if (!logEl) {
        logEl = document.createElement('div');
        logEl.style.cssText = 'position:fixed;left:6px;top:6px;z-index:2147483647;background:rgba(0,0,0,.75);color:#fff;font:10px monospace;padding:4px 6px;border-radius:6px;pointer-events:none;max-width:90vw;white-space:pre-wrap;word-break:break-all';
        (document.documentElement || document.body).appendChild(logEl);
      }
      logEl.textContent = 'worker downloads:\n' + logLines.join('\n');
    } catch (e) {}
  }
  if (window === window.top) {
    try {
      var bc = new W.BroadcastChannel('gfxfix');
      bc.onmessage = exportFunction(function (e) {
        try { var o = JSON.parse(e.data); logShow(o.s + ' ' + String(o.u).replace(/^https?:\/\/[^\/]+/, '') + (o.l ? ' len=' + o.l : '') + (o.e ? ' enc=' + o.e : '')); } catch (x) {}
      }, window);
    } catch (e) {}
  }
  patch(W.WebGLRenderingContext);
  patch(W.WebGL2RenderingContext);


  // background workers: start them with the fix applied first, and keep their file requests pointing at the real page address
  try {
    var prelude = "(function(B){function R(u){try{if(typeof u==='string'||u instanceof URL)return new URL(String(u),B).href;}catch(e){}return u;}" +
      "['WebGLRenderingContext','WebGL2RenderingContext'].forEach(function(n){var C=self[n];if(!C)return;var o=C.prototype.getParameter;C.prototype.getParameter=function(p){var v=o.call(this,p);if((p===0x8DFB||p===0x8DFD)&&typeof v==='number'&&v<264)return 264;return v;};});" +
      "var BC;try{BC=new BroadcastChannel('gfxfix');}catch(e){}function L(x){try{BC&&BC.postMessage(JSON.stringify(x));}catch(e){}}" +
      "var F=self.fetch;if(F)self.fetch=function(u,o){var q=(u instanceof Request)?u.url:R(u);var p=(u instanceof Request)?F.call(self,u,o):F.call(self,q,o);return p.then(function(r){L({u:String(r.url||q),s:r.status,l:r.headers.get('content-length'),e:r.headers.get('content-encoding')});return r;},function(e){L({u:String(q),s:'ERR '+e});throw e;});};" +
      "if(self.XMLHttpRequest){var XO=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){arguments[1]=R(u);return XO.apply(this,arguments);};}" +
      "var IS=self.importScripts;if(IS)self.importScripts=function(){return IS.apply(self,Array.prototype.map.call(arguments,R));};" +
      "})(%BASE%);\n";
    var OrigWorker = W.Worker;
    W.Worker = exportFunction(function (url, opts) {
      try {
        var abs = new URL(String(url), location.href).href;
        var mod = opts && opts.type === 'module';
        var src = prelude.replace('%BASE%', JSON.stringify(abs)) + (mod ? "import " + JSON.stringify(abs) + ";" : "importScripts(" + JSON.stringify(abs) + ");");
        var blobUrl = URL.createObjectURL(new Blob([src], { type: 'text/javascript' }));
        return new OrigWorker(blobUrl, opts);
      } catch (e) { return new OrigWorker(url, opts); }
    }, window);
    W.Worker.prototype = OrigWorker.prototype;
  } catch (e) {}
})();
