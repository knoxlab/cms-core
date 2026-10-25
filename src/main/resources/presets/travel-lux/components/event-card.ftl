<article class="tl-event-card">
  <a class="tl-event-media" href="${content.target_url_s!"#"}">
    <img src="${content.image_url_s!""}" alt="${content.title!"Travel event"}" loading="lazy">
  </a>
  <div class="tl-event-date"><strong>${editableText("date_day_s", "plain", "text")?no_esc}</strong><span>${editableText("date_month_s", "plain", "text")?no_esc}</span></div>
  <div class="tl-event-copy">
    <#if content.location_s?has_content><p class="tl-location">${editableText("location_s", "minimal", "html")?no_esc}</p></#if>
    <h3>${editableText("title", "minimal", "html")?no_esc}</h3>
    <#if content.summary_t?has_content><p>${editableText("summary_t", "minimal", "html")?no_esc}</p></#if>
  </div>
</article>
