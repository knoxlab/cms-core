/*
 * PowerCMS UVE bridge - injected into edit-mode preview pages only.
 * Talks to the admin visual editor via postMessage:
 *   up:   pcms:ready, pcms:bounds, pcms:component-clicked, pcms:component-hovered
 *   down: pcms:init { nonce }, pcms:flush-bounds, pcms:reload
 * The bridge stays inert until it receives pcms:init whose nonce matches the
 * uveNonce query param of this page URL; afterwards it only accepts messages
 * from that origin.
 */
(function () {
  'use strict';
  if (window.__pcmsUve) return;
  window.__pcmsUve = true;

  var nonce = new URLSearchParams(window.location.search).get('uveNonce') || '';
  var parentOrigin = null;
  var boundsQueued = false;
  var lastHoverId = null;
  var inlineEditors = [];
  var editingComponentId = null;

  function post(name, payload) {
    if (!parentOrigin) return;
    try {
      window.parent.postMessage({ name: name, payload: payload === undefined ? null : payload, nonce: nonce }, parentOrigin);
    } catch (err) { /* parent gone */ }
  }

  /* display:contents wrappers have no box; union children rects instead. */
  function rectOf(el) {
    var rect = el.getBoundingClientRect();
    if (rect.width > 0.5 || rect.height > 0.5) return rect;
    var left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity, found = false;
    for (var i = 0; i < el.children.length; i++) {
      var c = el.children[i].getBoundingClientRect();
      if (c.width < 0.5 && c.height < 0.5) continue;
      found = true;
      if (c.left < left) left = c.left;
      if (c.top < top) top = c.top;
      if (c.right > right) right = c.right;
      if (c.bottom > bottom) bottom = c.bottom;
    }
    if (!found) return rect;
    return { left: left, top: top, width: right - left, height: bottom - top };
  }

  function zoneArea(el) {
    var rect = rectOf(el);
    var comps = el.querySelectorAll('[data-pcms-component]');
    var areas = [];
    for (var i = 0; i < comps.length; i++) areas.push(componentArea(comps[i]));
    return { zoneKey: el.getAttribute('data-pcms-zone-root'), x: rect.left, y: rect.top,
      width: rect.width, height: rect.height, components: areas };
  }

  function componentArea(el) {
    var rect = rectOf(el);
    return {
      componentId: el.getAttribute('data-pcms-component'),
      zoneKey: el.getAttribute('data-pcms-zone'),
      index: parseInt(el.getAttribute('data-pcms-index') || '0', 10),
      kind: el.getAttribute('data-pcms-kind') || 'EMBEDDED',
      x: rect.left, y: rect.top, width: rect.width, height: rect.height
    };
  }

  function collectBounds() {
    var zones = [];
    var roots = document.querySelectorAll('[data-pcms-zone-root]');
    for (var i = 0; i < roots.length; i++) {
      var root = roots[i];
      var rect = rectOf(root);
      var comps = root.querySelectorAll('[data-pcms-component]');
      var areas = [];
      for (var j = 0; j < comps.length; j++) areas.push(componentArea(comps[j]));
      zones.push({
        zoneKey: root.getAttribute('data-pcms-zone-root'),
        x: rect.left, y: rect.top, width: rect.width, height: rect.height,
        components: areas
      });
    }
    return zones;
  }

  function emitBounds() {
    if (boundsQueued) return;
    boundsQueued = true;
    window.requestAnimationFrame(function () {
      boundsQueued = false;
      post('pcms:bounds', collectBounds());
    });
  }

  /* CSP blocks inline style attributes, so styling happens here via CSSOM. */
  function styleEditNodes() {
    var comps = document.querySelectorAll('[data-pcms-component]');
    for (var i = 0; i < comps.length; i++) comps[i].style.display = 'contents';
    var empties = document.querySelectorAll('[data-pcms-zone-empty]');
    for (var j = 0; j < empties.length; j++) {
      var el = empties[j];
      el.style.minHeight = '56px';
      el.style.display = 'flex';
      el.style.alignItems = 'center';
      el.style.justifyContent = 'center';
      el.style.border = '1px dashed #94a3b8';
      el.style.borderRadius = '6px';
      el.style.margin = '8px 0';
      el.style.color = '#64748b';
      el.style.fontSize = '12px';
      el.style.fontFamily = 'sans-serif';
      el.style.background = 'rgba(148,163,184,0.08)';
    }
  }

  function closestComponent(target) {
    return target && target.closest ? target.closest('[data-pcms-component]') : null;
  }

  function editablePayload(el, value) {
    var component = closestComponent(el);
    return {
      componentId: el.getAttribute('data-pcms-editable-component') || (component && component.getAttribute('data-pcms-component')),
      fieldName: el.getAttribute('data-pcms-editable-field'),
      format: el.getAttribute('data-pcms-editable-format') || 'text',
      contentItemId: el.getAttribute('data-pcms-content-item') || null,
      contentVersion: parseInt(el.getAttribute('data-pcms-content-version') || '0', 10),
      value: value
    };
  }

  function toolbarFor(mode) {
    if (mode === 'plain') return false;
    if (mode === 'full') return 'undo redo | blocks | bold italic underline | bullist numlist | link removeformat';
    return 'bold italic underline | link';
  }

  function initInlineEditors(root) {
    if (!window.tinymce || !window.tinymce.init) return;
    var scope = root && root.querySelectorAll ? root : document;
    var nodes = scope.querySelectorAll('[data-pcms-editable-field]:not([data-pcms-editor-ready])');
    for (var i = 0; i < nodes.length; i++) {
      (function (el) {
        el.setAttribute('data-pcms-editor-ready', 'true');
        var mode = el.getAttribute('data-pcms-editable-mode') || 'minimal';
        var format = el.getAttribute('data-pcms-editable-format') || 'text';
        var original = format === 'html' ? el.innerHTML : el.textContent;
        window.tinymce.init({
          target: el,
          inline: true,
          menubar: false,
          branding: false,
          promotion: false,
          license_key: 'gpl',
          toolbar: toolbarFor(mode),
          plugins: mode === 'plain' ? [] : ['link', 'lists'],
          quickbars_selection_toolbar: false,
          toolbar_mode: 'floating',
          base_url: '/api/v1/preview/tinymce',
          skin_url: '/api/v1/preview/tinymce/skins/ui/oxide',
          content_css: false,
          setup: function (editor) {
            inlineEditors.push(editor);
            editor.on('focus', function () {
              editingComponentId = editablePayload(el, '').componentId;
              el.classList.add('pcms-inline-active');
              post('pcms:inline-edit-started', editablePayload(el, original));
            });
            editor.on('blur', function () {
              el.classList.remove('pcms-inline-active');
              editingComponentId = null;
              var value = format === 'html' ? editor.getContent() : editor.getContent({ format: 'text' });
              if (value !== original) {
                original = value;
                post('pcms:inline-edit-save', editablePayload(el, value));
              }
              post('pcms:inline-edit-ended', editablePayload(el, value));
              emitBounds();
            });
          }
        });
      })(nodes[i]);
    }
  }

  function hookImages() {
    var images = document.querySelectorAll('img');
    for (var i = 0; i < images.length; i++) {
      if (!images[i].complete) images[i].addEventListener('load', emitBounds, { once: true });
    }
  }

  /* Replace a component wrapper in place after a partial re-render. */
  function replaceComponent(payload) {
    var componentId = payload && payload.componentId;
    var html = payload && payload.html;
    if (!componentId || typeof html !== 'string') { emitBounds(); return; }
    var current = document.querySelector('[data-pcms-component="' + componentId + '"]');
    if (!current) { emitBounds(); return; }
    for (var i = inlineEditors.length - 1; i >= 0; i--) {
      var editor = inlineEditors[i];
      if (current.contains(editor.getElement())) { editor.remove(); inlineEditors.splice(i, 1); }
    }
    current.outerHTML = html;
    styleEditNodes();
    initInlineEditors(document);
    hookImages();
    emitBounds();
  }

  function activate() {
    styleEditNodes();
    initInlineEditors(document);
    document.addEventListener('click', function (event) {
      var editable = event.target && event.target.closest ? event.target.closest('[data-pcms-editable-field], .tox, .tox-tinymce-aux') : null;
      if (editable) {
        event.stopPropagation();
        var editableComponent = closestComponent(event.target);
        if (editableComponent) post('pcms:component-clicked', componentArea(editableComponent));
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      var el = closestComponent(event.target);
      if (el) post('pcms:component-clicked', componentArea(el));
      else {
        var zone = event.target && event.target.closest ? event.target.closest('[data-pcms-zone-root]') : null;
        if (zone) post('pcms:zone-clicked', zoneArea(zone));
        else post('pcms:component-clicked', null);
      }
    }, true);
    document.addEventListener('mouseover', function (event) {
      var el = closestComponent(event.target);
      var id = el ? el.getAttribute('data-pcms-component') : null;
      if (editingComponentId) return;
      if (id === lastHoverId) return;
      lastHoverId = id;
      post('pcms:component-hovered', el ? componentArea(el) : null);
    }, true);
    document.documentElement.addEventListener('mouseleave', function () {
      lastHoverId = null;
      post('pcms:component-hovered', null);
    });
    window.addEventListener('scroll', emitBounds, true);
    window.addEventListener('resize', emitBounds);
    if (window.ResizeObserver) {
      try { new ResizeObserver(emitBounds).observe(document.documentElement); } catch (err) { /* ignore */ }
    }
    hookImages();
  }

  window.addEventListener('message', function (event) {
    var data = event.data || {};
    if (data.name === 'pcms:init') {
      if (nonce && data.nonce !== nonce) return;
      var first = parentOrigin === null;
      parentOrigin = event.origin;
      if (first) activate();
      post('pcms:ready', {});
      emitBounds();
      return;
    }
    if (!parentOrigin || event.origin !== parentOrigin) return;
    if (data.name === 'pcms:flush-bounds') emitBounds();
    else if (data.name === 'pcms:reload') window.location.reload();
    else if (data.name === 'pcms:replace-component') replaceComponent(data.payload);
    else if (data.name === 'pcms:scroll-by') window.scrollBy(0, (data.payload && data.payload.top) || 0);
  });
})();
