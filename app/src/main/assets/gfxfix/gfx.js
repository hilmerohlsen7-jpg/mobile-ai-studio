(function () {
  function patch(C) {
    if (!C) return;
    var proto = C.prototype, orig = proto.getParameter;
    proto.getParameter = exportFunction(function (p) {
      var v = orig.call(this, p);
      if ((p === 0x8DFB || p === 0x8DFD) && typeof v === 'number' && v < 264) return 264;
      return v;
    }, window);
  }
  patch(window.wrappedJSObject.WebGLRenderingContext);
  patch(window.wrappedJSObject.WebGL2RenderingContext);
})();

// self-test: open any page with #gfxtest on the end to see what that page is told
if (location.hash === '#gfxtest') {
  document.addEventListener('DOMContentLoaded', function () {
    var d = window.wrappedJSObject.document, out = 'Workaround add-on is RUNNING\n';
    ['webgl', 'webgl2'].forEach(function (v) {
      var g = d.createElement('canvas').getContext(v);
      out += v + ': ' + (g ? 'MAX_VERTEX=' + g.getParameter(g.MAX_VERTEX_UNIFORM_VECTORS) + ' MAX_FRAGMENT=' + g.getParameter(g.MAX_FRAGMENT_UNIFORM_VECTORS) : 'not available') + '\n';
    });
    var p = document.createElement('pre'); p.textContent = out; p.style.cssText = 'font-size:18px;padding:16px;background:#fff;color:#000;position:fixed;top:0;left:0;right:0;z-index:99999999';
    document.body.appendChild(p);
  });
}
