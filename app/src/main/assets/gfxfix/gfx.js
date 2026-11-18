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

})();
