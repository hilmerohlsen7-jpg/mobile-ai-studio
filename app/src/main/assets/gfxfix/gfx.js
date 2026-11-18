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
