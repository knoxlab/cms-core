<!doctype html>
<html lang="${locale!"zh-CN"}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="${content.seo_description_t!"Curated journeys, outdoor experiences and travel gear."}">
  <title>${content.seo_title_s!"TravelLux · Explore the world"}</title>
  ${htmlHead?no_esc}
</head>
<body>
  <a class="tl-skip-link" href="#main-content">Skip to content</a>
  ${header?no_esc}
  <div id="main-content">${body?no_esc}</div>
  ${footer?no_esc}
  <a class="tl-back-top" href="#top" aria-label="Back to top">↑</a>
</body>
</html>
