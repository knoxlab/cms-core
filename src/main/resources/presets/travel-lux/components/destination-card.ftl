<article class="tl-destination-card">
  <div class="tl-destination-image">
    <img src="${content.image_url_s!""}" alt="${content.title!"Travel destination"}" loading="lazy">
    <#if content.badge_s?has_content><span class="tl-badge">${content.badge_s}</span></#if>
  </div>
  <div class="tl-destination-copy">
    <#if content.location_s?has_content><p class="tl-location">${editableText("location_s", "minimal", "html")?no_esc}</p></#if>
    <h3>${editableText("title", "minimal", "html")?no_esc}</h3>
    <#if content.summary_t?has_content><p>${editableText("summary_t", "minimal", "html")?no_esc}</p></#if>
    <a class="tl-card-link" href="${content.target_url_s!"#"}">EXPLORE →</a>
  </div>
</article>
