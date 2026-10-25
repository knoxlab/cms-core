(function () {
  var path = window.location.pathname || '/';
  document.querySelectorAll('.tl-nav a').forEach(function (link) {
    var href = link.getAttribute('href') || '';
    if (href === path || (path === '/' && (href === '/' || href === '#top'))) {
      link.classList.add('is-active');
    }
  });
})();
